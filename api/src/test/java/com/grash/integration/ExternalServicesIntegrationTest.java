package com.grash.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.grash.configuration.ExternalSendConfig;
import com.grash.integration.support.CallRecorder;
import com.grash.integration.support.FaultInjector;
import com.grash.integration.support.FaultInjector.Operation;
import com.grash.integration.support.TestApi;
import com.grash.integration.support.TestApi.Response;
import com.grash.integration.support.TestOrganizations;
import com.grash.integration.support.TestOrganizations.CommonData;
import com.grash.integration.support.TestRecords;
import com.grash.model.User;
import com.grash.model.WorkOrder;
import com.grash.model.enums.AuditSource;
import com.grash.model.enums.EntityType;
import com.grash.model.enums.Status;
import com.grash.model.enums.webhook.WebhookEvent;
import com.grash.repository.WorkOrderRepository;
import com.grash.service.AuditTarget;
import com.grash.service.ExternalSendService;
import com.grash.service.ExternalSendService.Channel;
import com.grash.utils.AfterCommit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * P-23: the contract of mail, notifications and webhooks. If they fail or take too long, the operation of the user
 * is neither rolled back nor held, and the failure is recorded in the audit log. Nothing leaves the machine.
 */
@AutoConfigureMockMvc
@Timeout(value = 2, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class ExternalServicesIntegrationTest extends AbstractIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(10);

    private final TestOrganizations organizations;
    private final TestApi api;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;
    private final TestRecords records;
    private final WorkOrderRepository workOrderRepository;
    private final ExternalSendService externalSendService;
    private final ThreadPoolTaskExecutor sendExecutor;
    private final TransactionTemplate transaction;
    private final ApplicationContext context;
    private final FaultInjector faults;
    private final List<CommonData> created = new ArrayList<>();
    private final CountDownLatch release = new CountDownLatch(1);

    @Autowired
    ExternalServicesIntegrationTest(TestOrganizations organizations, TestRecords records, MockMvc mockMvc,
                                    ObjectMapper objectMapper, JdbcTemplate jdbc,
                                    WorkOrderRepository workOrderRepository,
                                    ExternalSendService externalSendService,
                                    @Qualifier(ExternalSendConfig.EXECUTOR) ThreadPoolTaskExecutor sendExecutor,
                                    TransactionTemplate transaction, ApplicationContext context) {
        this.organizations = organizations;
        this.records = records;
        this.api = new TestApi(mockMvc, objectMapper);
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
        this.workOrderRepository = workOrderRepository;
        this.externalSendService = externalSendService;
        this.sendExecutor = sendExecutor;
        this.transaction = transaction;
        this.context = context;
        this.faults = new FaultInjector(jdbc);
    }

    @AfterEach
    void cleanUp() {
        release.countDown();
        faults.removeAll();
        created.forEach(organizations::delete);
        created.clear();
    }

    /**
     * The organizations of a test. Their first work order is marked as already created, so creating one does not
     * fire the Intercom activation event.
     */
    private CommonData data() {
        CommonData data = organizations.create();
        created.add(data);
        jdbc.update("UPDATE company SET first_work_order_created = true WHERE id IN (?, ?)",
                data.o1().company().getId(), data.o2().company().getId());
        data.o1().company().setFirstWorkOrderCreated(true);
        data.o2().company().setFirstWorkOrderCreated(true);
        return data;
    }

    /**
     * The records of the organization, as "ACTION ENTITY_TYPE entity_id actor source", oldest first.
     */
    private List<String> auditRecords(CommonData data) {
        return jdbc.queryForList("SELECT action || ' ' || entity_type || ' ' || entity_id || ' ' "
                + "|| coalesce(actor_id::text, '-') || ' ' || source FROM audit_log WHERE company_id = ? ORDER BY id",
                String.class, data.o1().company().getId());
    }

    /**
     * Waits until the organization has {@code count} records, which the sends write from their own threads.
     */
    private List<String> awaitAuditRecords(CommonData data, int count) {
        long deadline = System.nanoTime() + WAIT.toNanos();
        List<String> found = auditRecords(data);
        while (found.size() < count && System.nanoTime() < deadline) {
            pause(Duration.ofMillis(50));
            found = auditRecords(data);
        }
        return found;
    }

    /**
     * The text columns of the records of the organization that could carry a secret: none may.
     */
    private List<Map<String, Object>> auditText(CommonData data) {
        return jdbc.queryForList("SELECT entity_folio, entity_name, actor_name, actor_email FROM audit_log "
                + "WHERE company_id = ?", data.o1().company().getId());
    }

    private static void pause(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private AuditTarget companyTarget(CommonData data) {
        return new AuditTarget(EntityType.COMPANY, data.o1().company().getId(), null,
                data.o1().company().getName(), data.o1().company().getId());
    }

    private Response send(MockHttpServletRequestBuilder request, Object body, User user) {
        try {
            return api.call(request.contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body)), api.login(user));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Response createWorkOrder(CommonData data, String title) {
        return send(post("/work-orders"), Map.of("title", title,
                "assignedTo", List.of(Map.of("id", data.o1().technician().getId()))), data.o1().admin());
    }

    private Long workOrderId(String title) {
        return jdbc.queryForObject("SELECT id FROM work_order WHERE title = ?", Long.class, title);
    }

    private WorkOrder openWorkOrder(CommonData data) {
        WorkOrder workOrder = records.workOrder(data.o1().company(), null, Status.OPEN, false);
        workOrder.setAssignedTo(new ArrayList<>(List.of(data.o1().technician())));
        return workOrderRepository.saveAndFlush(workOrder);
    }

    /**
     * Fills the executor of the sends with tasks that wait for {@link #release}, until it rejects one.
     */
    private void fillSends() {
        AtomicInteger running = new AtomicInteger();
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                sendExecutor.execute(() -> {
                    running.incrementAndGet();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            } catch (RejectedExecutionException e) {
                // full; it stays full once the tasks of earlier tests are gone and only these hold the threads
                if (running.get() == sendExecutor.getMaxPoolSize()
                        && sendExecutor.getThreadPoolExecutor().getQueue().remainingCapacity() == 0) return;
                pause(Duration.ofMillis(20));
            }
        }
        throw new AssertionError("the executor of the sends did not stay full");
    }

    // ---------------------------------------------------------------------------------------------------------
    // E-3: the record goes in a transaction of its own
    // ---------------------------------------------------------------------------------------------------------

    @Test
    void failureRecord_survivesTheRollbackOfTheSurroundingTransaction() {
        CommonData data = data();
        AuditTarget origin = companyTarget(data);

        transaction.executeWithoutResult(status -> {
            externalSendService.failed(Channel.MAIL, origin, AuditSource.API, new IllegalStateException("down"));
            status.setRollbackOnly();
        });

        assertEquals(List.of("MAIL_FAILED COMPANY " + origin.id() + " - API"), auditRecords(data));
    }

    @Test
    void failureRecord_writtenAfterACommit() {
        CommonData data = data();
        AuditTarget origin = companyTarget(data);

        transaction.executeWithoutResult(status -> AfterCommit.run(() ->
                externalSendService.discarded(Channel.WEBHOOK, origin, AuditSource.JOB)));

        assertEquals(List.of("WEBHOOK_DISCARDED COMPANY " + origin.id() + " - JOB"), auditRecords(data));
    }

    /**
     * The executor of the sends is a bean of type {@code Executor}, which makes Spring Boot leave out its own
     * {@code applicationTaskExecutor}; the WebSocket broker already did, before P-23.
     */
    @Test
    void theDefaultApplicationExecutorIsAbsent() {
        assertFalse(context.containsBean("applicationTaskExecutor"));
        assertFalse(context.containsBean("taskExecutor"));
    }

    // ---------------------------------------------------------------------------------------------------------
    // CA-P23-03: more sends than the system admits
    // ---------------------------------------------------------------------------------------------------------

    @Test
    void ca03_sendsFull_closingAWorkOrder_200AndClosed_theDropsRecorded() {
        CommonData data = data();
        records.endpoint(data.o1().company(), WebhookEvent.WORK_ORDER_CHANGE);
        WorkOrder workOrder = openWorkOrder(data);
        fillSends();

        Response response = send(patch("/work-orders/" + workOrder.getId() + "/change-status"),
                Map.of("status", "COMPLETE"), data.o1().admin());

        // before P-23: 500 and rolled back (characterization)
        assertEquals(200, response.status(), response.toString());
        assertEquals(Status.COMPLETE.ordinal(), jdbc.queryForObject("SELECT status FROM work_order WHERE id = ?",
                Integer.class, workOrder.getId()));
        List<String> found = auditRecords(data);
        assertTrue(found.contains("WEBHOOK_DISCARDED WORK_ORDER " + workOrder.getId() + " - API"), found::toString);
        assertTrue(found.contains("NOTIFICATION_DISCARDED WORK_ORDER " + workOrder.getId() + " - API"),
                found::toString);
        assertEquals(List.of(), auditText(data).stream().flatMap(row -> row.values().stream())
                .filter(value -> value != null).toList(), "no folio, name, user or address");
    }

    @Test
    void ca03_sendsFull_creatingAWorkOrder_200_theDropRecorded() {
        CommonData data = data();
        String title = TestRecords.unique("P-23 WO");
        fillSends();

        Response response = createWorkOrder(data, title);

        // before P-23: 500 with the work order committed (TD-P13-05)
        assertEquals(200, response.status(), response.toString());
        assertEquals(List.of("NOTIFICATION_DISCARDED WORK_ORDER " + workOrderId(title) + " - API"),
                auditRecords(data));
    }

    // ---------------------------------------------------------------------------------------------------------
    // CA-P23-04: a send that fails; CA-P23-07: repeated failures
    // ---------------------------------------------------------------------------------------------------------

    @Test
    void ca04_ca07_failingWebhook_recordedOnceAgainstItsWorkOrder_withoutUrl() {
        CommonData data = data();
        records.endpoint(data.o1().company(), WebhookEvent.NEW_WORK_ORDER);
        webhookDouble.failOn(CallRecorder.ANY, new IllegalStateException("P-23 destination down"));
        String first = TestRecords.unique("P-23 WO");
        String second = TestRecords.unique("P-23 WO");

        Response created = createWorkOrder(data, first);
        List<String> found = awaitAuditRecords(data, 1);
        Response again = createWorkOrder(data, second);
        webhookDouble.awaitCalls("NEW_WORK_ORDER", 2, WAIT);
        pause(Duration.ofMillis(500));

        assertEquals(200, created.status(), created.toString());
        assertEquals(200, again.status(), again.toString());
        // before P-23: only in the log
        assertEquals(List.of("WEBHOOK_FAILED WORK_ORDER " + workOrderId(first) + " - API"), found);
        assertEquals(found, auditRecords(data), "one record per organization and action every 10 minutes");
        assertEquals(List.of(), auditText(data).stream().flatMap(row -> row.values().stream())
                .filter(value -> value != null).toList(), "no URL, address or exception text");
    }

    @Test
    void ca04_failingNotification_recordedAgainstItsWorkOrder() {
        CommonData data = data();
        String title = TestRecords.unique("P-23 WO");

        Response response;
        List<String> found;
        try (FaultInjector.Fault fault = faults.failOn("notification", Operation.INSERT)) {
            response = createWorkOrder(data, title);
            found = awaitAuditRecords(data, 1);
            assertEquals(1, fault.hits(), "the notification failed");
        }

        assertEquals(200, response.status(), response.toString());
        assertEquals(List.of("NOTIFICATION_FAILED WORK_ORDER " + workOrderId(title) + " - API"), found);
    }
}
