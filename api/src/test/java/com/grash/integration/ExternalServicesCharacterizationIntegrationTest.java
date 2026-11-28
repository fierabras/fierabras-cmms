package com.grash.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.grash.integration.support.FaultInjector;
import com.grash.integration.support.FaultInjector.Operation;
import com.grash.integration.support.LoopbackRequestFactory;
import com.grash.integration.support.SilentServer;
import com.grash.integration.support.TestApi;
import com.grash.integration.support.TestApi.Response;
import com.grash.integration.support.TestOrganizations;
import com.grash.integration.support.TestOrganizations.CommonData;
import com.grash.integration.support.TestRecords;
import com.grash.integration.support.TestWorkOrders;
import com.grash.model.User;
import com.grash.model.WorkOrder;
import com.grash.model.enums.Status;
import com.grash.model.enums.webhook.WebhookEvent;
import com.grash.repository.FileRepository;
import com.grash.repository.PartRepository;
import com.grash.repository.WorkOrderRepository;
import com.grash.service.EmailService2;
import com.grash.service.ExternalSendService;
import com.grash.service.IntercomService;
import com.grash.service.WebhookDispatchService;
import com.grash.integration.support.CallRecorder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * P-23 characterization: fixes how mail, notifications and webhooks behave before any change, when the external
 * service hangs or fails, when the asynchronous executor is full, when the operation is rolled back and when mail is
 * disabled. Nothing leaves the machine: the real mail service talks to a {@link SilentServer} on the loopback
 * address, and the webhooks go to the webhook double or, through {@link LoopbackRequestFactory}, to another one.
 */
@AutoConfigureMockMvc
@Timeout(value = 2, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class ExternalServicesCharacterizationIntegrationTest extends AbstractIntegrationTest {

    /**
     * Long enough to tell "waits without limit" from the short limits of the P-23 tests; never a production limit.
     */
    private static final Duration HANGS = Duration.ofSeconds(3);
    private static final Duration WAIT = Duration.ofSeconds(10);

    private final TestOrganizations organizations;
    private final TestApi api;
    private final TestWorkOrders workOrders;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;
    private final TestRecords records;
    private final EmailService2 emailService;
    private final JavaMailSenderImpl mailSender;
    private final RestTemplate webhookClient;
    private final FaultInjector faults;
    /**
     * The test configuration gives Intercom a token, so a first work order would call it: cleared for each test.
     */
    private final Object intercom;
    private Object intercomToken;
    /**
     * ExternalSendService: mail is enabled for it too, as for the real mail service.
     */
    private final Object externalSend;
    private Object externalSendMailEnabled;
    private final List<CommonData> created = new ArrayList<>();

    private SilentServer smtp;
    private SilentServer destination;
    private String mailHost;
    private int mailPort;
    private Object mailEnabled;
    private Properties mailProperties;

    @Autowired
    ExternalServicesCharacterizationIntegrationTest(TestOrganizations organizations, TestRecords records,
                                                    MockMvc mockMvc, ObjectMapper objectMapper, JdbcTemplate jdbc,
                                                    WorkOrderRepository workOrderRepository,
                                                    PartRepository partRepository, FileRepository fileRepository,
                                                    EmailService2 emailService, JavaMailSenderImpl mailSender,
                                                    WebhookDispatchService webhookDispatchService,
                                                    IntercomService intercomService,
                                                    ExternalSendService externalSendService) {
        this.organizations = organizations;
        this.records = records;
        this.api = new TestApi(mockMvc, objectMapper);
        this.workOrders = new TestWorkOrders(api, objectMapper, records, workOrderRepository, partRepository,
                fileRepository);
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
        this.emailService = emailService;
        this.mailSender = mailSender;
        Object dispatcher = AopTestUtils.getUltimateTargetObject(webhookDispatchService);
        this.webhookClient = (RestTemplate) ReflectionTestUtils.getField(dispatcher, "restTemplate");
        this.faults = new FaultInjector(jdbc);
        this.intercom = AopTestUtils.getUltimateTargetObject(intercomService);
        this.externalSend = AopTestUtils.getUltimateTargetObject(externalSendService);
    }

    /**
     * Points the real mail service at a silent server and enables it, only for this test.
     */
    @BeforeEach
    void routeMailToSilentServer() {
        smtp = new SilentServer();
        destination = new SilentServer();
        mailHost = mailSender.getHost();
        mailPort = mailSender.getPort();
        mailProperties = new Properties();
        mailProperties.putAll(mailSender.getJavaMailProperties());
        mailSender.setHost("127.0.0.1");
        mailSender.setPort(smtp.port());
        mailEnabled = ReflectionTestUtils.getField(mailTarget(), "enableEmails");
        ReflectionTestUtils.setField(mailTarget(), "enableEmails", true);
        intercomToken = ReflectionTestUtils.getField(intercom, "intercomToken");
        ReflectionTestUtils.setField(intercom, "intercomToken", "");
        externalSendMailEnabled = ReflectionTestUtils.getField(externalSend, "mailEnabled");
        ReflectionTestUtils.setField(externalSend, "mailEnabled", true);
    }

    @AfterEach
    void cleanUp() {
        smtp.close();
        destination.close();
        mailSender.setHost(mailHost);
        mailSender.setPort(mailPort);
        mailSender.setJavaMailProperties(mailProperties);
        ReflectionTestUtils.setField(mailTarget(), "enableEmails", mailEnabled);
        ReflectionTestUtils.setField(intercom, "intercomToken", intercomToken);
        ReflectionTestUtils.setField(externalSend, "mailEnabled", externalSendMailEnabled);
        faults.removeAll();
        created.forEach(organizations::delete);
        created.clear();
    }

    private Object mailTarget() {
        Object target = AopTestUtils.getUltimateTargetObject(emailService);
        return target;
    }

    /**
     * The organizations of a test. Their first work order is marked as already created, so creating one does not
     * fire the Intercom activation event, which would leave the machine.
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

    private void useRealMail() {
        when(mailServiceFactory.getMailService()).thenReturn(emailService);
    }

    private void routeWebhooksToSilentServer() {
        webhookClient.setRequestFactory(new LoopbackRequestFactory(WebhookDispatchService.requestFactory(1000, 1000),
                destination.port()));
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

    private void sendMail() {
        emailService.sendMessageUsingThymeleafTemplate(new String[]{"p23@example.test"}, "P-23", Map.of(),
                "new-work-order.html", Locale.ENGLISH, null);
    }

    /**
     * Fills the asynchronous executor with mails that hang on the silent server, until it rejects one.
     *
     * @return the mails accepted before the rejection
     */
    private int fillExecutor() {
        int sent = 0;
        for (int round = 0; round < 10; round++) {
            boolean roomLeft = false;
            for (int i = 0; i < 50; i++) {
                try {
                    sendMail();
                    sent++;
                    roomLeft = true;
                } catch (TaskRejectedException e) {
                    break;
                }
            }
            assertTrue(smtp.awaitAccepted(3, WAIT), "three threads hold a connection");
            // tasks left by an earlier test may still free places: fill again until nothing fits
            if (!roomLeft && round > 0) return sent;
            pause(Duration.ofMillis(300));
        }
        throw new AssertionError("the executor never stayed full");
    }

    private long auditRecords(CommonData data) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE company_id = ?", Long.class,
                data.o1().company().getId());
    }

    /**
     * The records of the organization, as "ACTION ENTITY_TYPE entity_id", sorted.
     */
    private List<String> auditActions(CommonData data) {
        return jdbc.queryForList("SELECT action || ' ' || entity_type || ' ' || entity_id FROM audit_log "
                + "WHERE company_id = ? ORDER BY 1", String.class, data.o1().company().getId());
    }

    private long count(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, Long.class, arguments);
    }

    private static void pause(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // A mail server or a webhook destination that does not answer (CA-P23-01, CA-P23-02)
    // ---------------------------------------------------------------------------------------------------------

    @Test
    void ca01_silentMailServer_creatingAWorkOrder_answersAtOnceAndCommitted() throws Exception {
        CommonData data = data();
        useRealMail();
        String title = TestRecords.unique("P-23 WO");
        ExecutorService user = Executors.newSingleThreadExecutor();
        try {
            Future<Response> request = user.submit(() -> createWorkOrder(data, title));

            // before P-23: WorkOrderService.notify called the default five-argument method of MailService, which
            // called the asynchronous one on itself: the mail went out in the thread of the request, without
            // limit, and the request waited for the mail server (then 500 with the work order committed)
            Response response = request.get(HANGS.toMillis(), TimeUnit.MILLISECONDS);
            assertEquals(200, response.status(), response.toString());
            assertEquals(1, count("SELECT count(*) FROM work_order WHERE title = ? AND company_id = ?", title,
                    data.o1().company().getId()));
            assertTrue(smtp.awaitAccepted(1, WAIT), "the mail of the new work order reached the server");
        } finally {
            smtp.refuse();
            user.shutdownNow();
        }
    }

    @Test
    void ca01_silentMailServer_theMailEndsByItsTimeLimit_andIsRecorded() {
        CommonData data = data();
        useRealMail();
        Properties limits = new Properties();
        limits.putAll(mailProperties);
        limits.setProperty("mail.smtp.connectiontimeout", "1000");
        limits.setProperty("mail.smtp.timeout", "1000");
        limits.setProperty("mail.smtp.writetimeout", "1000");
        mailSender.setJavaMailProperties(limits);
        String title = TestRecords.unique("P-23 WO");

        Response response = createWorkOrder(data, title);

        assertEquals(200, response.status(), response.toString());
        Long id = jdbc.queryForObject("SELECT id FROM work_order WHERE title = ?", Long.class, title);
        assertTrue(smtp.awaitAccepted(1, WAIT), "the mail of the new work order reached the server");
        // before P-23: no time limit, the mail kept its thread while the server was silent
        assertTrue(smtp.awaitClosedByClient(1, WAIT), "the mail gave up at its time limit");
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (auditRecords(data) == 0 && System.nanoTime() < deadline) pause(Duration.ofMillis(50));
        assertEquals(List.of("MAIL_FAILED WORK_ORDER " + id), auditActions(data));
    }

    @Test
    void ca02_silentWebhookDestination_theWorkOrderIsCreated_theWebhookEndsByItsTimeLimit_andIsRecorded() {
        CommonData data = data();
        records.endpoint(data.o1().company(), WebhookEvent.NEW_WORK_ORDER);
        routeWebhooksToSilentServer();
        String title = TestRecords.unique("P-23 WO");

        Response response = createWorkOrder(data, title);

        assertEquals(200, response.status(), response.toString());
        Long id = jdbc.queryForObject("SELECT id FROM work_order WHERE title = ?", Long.class, title);
        assertTrue(destination.awaitAccepted(1, WAIT), "the webhook reached the destination");
        // before P-23: no time limit, the dispatch thread kept waiting
        assertTrue(destination.awaitClosedByClient(1, WAIT), "the webhook gave up at its time limit");
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (auditRecords(data) == 0 && System.nanoTime() < deadline) pause(Duration.ofMillis(50));
        assertEquals(List.of("WEBHOOK_FAILED WORK_ORDER " + id), auditActions(data));
    }

    // ---------------------------------------------------------------------------------------------------------
    // More sends than the executor admits (CA-P23-03, TD-P13-05)
    // ---------------------------------------------------------------------------------------------------------

    @Test
    void fullExecutor_closingAWorkOrder_200AndClosed() {
        CommonData data = data();
        WorkOrder workOrder = workOrders.workOrder(data.o1().company(), data.o1().technician());
        int accepted = fillExecutor();

        Response response = send(patch("/work-orders/" + workOrder.getId() + "/change-status"),
                Map.of("status", "COMPLETE"), data.o1().admin());

        assertTrue(accepted > 0, "the executor took mails before it was full");
        // before P-23: 500 and the closing rolled back, because the webhook and the notifications were submitted
        // before the commit and the rejection reached the request
        assertEquals(200, response.status(), response.toString());
        assertEquals(Status.COMPLETE.ordinal(), jdbc.queryForObject("SELECT status FROM work_order WHERE id = ?",
                Integer.class, workOrder.getId()));
    }

    @Test
    void fullExecutor_creatingAWorkOrder_200() {
        CommonData data = data();
        String title = TestRecords.unique("P-23 WO");
        fillExecutor();

        Response response = createWorkOrder(data, title);

        // before P-23: 500 with the work order committed: the rejection of the notification after the commit
        // reached the request (TD-P13-05)
        assertEquals(200, response.status(), response.toString());
        assertEquals(1, count("SELECT count(*) FROM work_order WHERE title = ? AND company_id = ?", title,
                data.o1().company().getId()));
    }

    @Test
    void ca03_fullExecutor_creatingAWorkOrderThatMails_200_theDropsRecorded() {
        CommonData data = data();
        useRealMail();
        String title = TestRecords.unique("P-23 WO");
        fillExecutor();

        Response response = createWorkOrder(data, title);

        assertEquals(200, response.status(), response.toString());
        Long id = jdbc.queryForObject("SELECT id FROM work_order WHERE title = ?", Long.class, title);
        assertEquals(List.of("MAIL_DISCARDED WORK_ORDER " + id, "NOTIFICATION_DISCARDED WORK_ORDER " + id),
                auditActions(data));
    }

    @Test
    void ca06_mailDisabled_fullExecutor_noMailDrop() {
        CommonData data = data();
        useRealMail();
        String title = TestRecords.unique("P-23 WO");
        fillExecutor();
        ReflectionTestUtils.setField(mailTarget(), "enableEmails", false);
        ReflectionTestUtils.setField(externalSend, "mailEnabled", false);

        Response response = createWorkOrder(data, title);

        assertEquals(200, response.status(), response.toString());
        Long id = jdbc.queryForObject("SELECT id FROM work_order WHERE title = ?", Long.class, title);
        assertEquals(List.of("NOTIFICATION_DISCARDED WORK_ORDER " + id), auditActions(data),
                "a mail that would not have been sent is not a false alarm");
    }

    @Test
    void busyMailThreads_doNotDelayTheExports() {
        CommonData data = data();
        for (int i = 0; i < 3; i++) sendMail();
        assertTrue(smtp.awaitAccepted(3, WAIT), "three mails hold the three threads of the sends");

        Response response = api.call(get("/export/assets").param("uuid", TestRecords.unique("p23")),
                api.login(data.o1().admin()));

        assertEquals(200, response.status(), response.toString());
        // before P-23: the export waited behind the mails, on the one executor
        storageDouble.awaitCalls("upload", 1, WAIT);
        assertEquals(0, smtp.closedByClient(), "the mails still held their threads when the export ended");
    }

    // ---------------------------------------------------------------------------------------------------------
    // A send that fails (CA-P23-04)
    // ---------------------------------------------------------------------------------------------------------

    @Test
    void failingWebhook_recordedInTheAuditLog() {
        CommonData data = data();
        records.endpoint(data.o1().company(), WebhookEvent.NEW_WORK_ORDER);
        webhookDouble.failOn(CallRecorder.ANY, new IllegalStateException("P-23 destination down"));

        Response response = createWorkOrder(data, TestRecords.unique("P-23 WO"));

        assertEquals(200, response.status(), response.toString());
        assertTrue(webhookDouble.awaitCalls("NEW_WORK_ORDER", 1, WAIT).get(0).failed());
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (auditRecords(data) == 0 && System.nanoTime() < deadline) pause(Duration.ofMillis(50));
        // before P-23: the failure was only written to the log (0 records)
        assertEquals(1, auditRecords(data));
    }

    @Test
    void ca04_failingMailServer_creatingAWorkOrder_200_theFailureRecorded() {
        CommonData data = data();
        useRealMail();
        smtp.refuse();
        String title = TestRecords.unique("P-23 WO");

        Response response = createWorkOrder(data, title);

        // before P-23: the mail went out in the request thread and its failure answered 500, with the work order
        // committed; the failure was only written to the log
        assertEquals(200, response.status(), response.toString());
        Long id = jdbc.queryForObject("SELECT id FROM work_order WHERE title = ? AND company_id = ?", Long.class,
                title, data.o1().company().getId());
        assertTrue(smtp.awaitAccepted(1, WAIT), "the mail reached the server, which closed the connection");
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (auditRecords(data) == 0 && System.nanoTime() < deadline) pause(Duration.ofMillis(50));
        assertEquals(List.of("MAIL_FAILED WORK_ORDER " + id), auditActions(data));
    }

    @Test
    void failingMailServer_asynchronousMail_onlyInTheLog_noAuditRecord() {
        CommonData data = data();
        smtp.refuse();

        sendMail();

        assertTrue(smtp.awaitAccepted(1, WAIT), "the mail reached the server, which closed the connection");
        pause(Duration.ofMillis(500));
        // before P-23: the failure of the asynchronous six-argument method is only written to the log
        assertEquals(0, auditRecords(data));
    }

    // ---------------------------------------------------------------------------------------------------------
    // An operation rolled back at commit (CA-P23-05)
    // ---------------------------------------------------------------------------------------------------------

    @Test
    void workOrderEditRolledBack_theWebhookAndTheMailWereAlreadySent() {
        CommonData data = data();
        records.endpoint(data.o1().company(), WebhookEvent.WORK_ORDER_CHANGE);
        WorkOrder workOrder = workOrders.workOrder(data.o1().company(), data.o1().technician());
        String token = api.login(data.o1().admin());
        ObjectNode body = workOrders.shown(workOrder.getId(), token);
        body.put("title", TestRecords.unique("P-23 edited"));
        body.putArray("assignedTo").addObject().put("id", data.o1().admin().getId());

        Response response;
        try (FaultInjector.Fault fault = faults.failAtCommit("work_order", Operation.UPDATE)) {
            response = workOrders.call(patch("/work-orders/" + workOrder.getId()), token, body);
            assertEquals(1, fault.hits(), "the commit failed");
        }

        assertFalse(response.is2xx(), response.toString());
        assertEquals(workOrder.getTitle(), jdbc.queryForObject("SELECT title FROM work_order WHERE id = ?",
                String.class, workOrder.getId()), "the edit was rolled back");
        // before P-23: sent before the commit
        webhookDouble.awaitCalls("WORK_ORDER_CHANGE", 1, WAIT);
        assertEquals(1, mailDouble.calls("sendMessageUsingThymeleafTemplate").size(), "the new assignee's mail");
    }

    @Test
    void requestCreationRolledBack_theWebhookAndTheMailWereAlreadySent() {
        CommonData data = data();
        records.endpoint(data.o1().company(), WebhookEvent.NEW_REQUEST);
        String title = TestRecords.unique("P-23 request");

        Response response;
        try (FaultInjector.Fault fault = faults.failAtCommit("request", Operation.INSERT)) {
            response = send(post("/requests"), Map.of("title", title), data.o1().admin());
            assertEquals(1, fault.hits(), "the commit failed");
        }

        assertFalse(response.is2xx(), response.toString());
        assertEquals(0, count("SELECT count(*) FROM request WHERE title = ?", title), "nothing was created");
        // before P-23: sent before the commit
        webhookDouble.awaitCalls("NEW_REQUEST", 1, WAIT);
        assertEquals(1, mailDouble.calls("sendMessageUsingThymeleafTemplate").size(), "the administrators' mail");
    }

    // ---------------------------------------------------------------------------------------------------------
    // Mail disabled (CA-P23-06)
    // ---------------------------------------------------------------------------------------------------------

    @Test
    void mailDisabled_nothingIsSent_noAuditRecord() {
        CommonData data = data();
        useRealMail();
        ReflectionTestUtils.setField(mailTarget(), "enableEmails", false);

        Response response = createWorkOrder(data, TestRecords.unique("P-23 WO"));

        assertEquals(200, response.status(), response.toString());
        pause(Duration.ofSeconds(1));
        assertEquals(0, smtp.accepted(), "no connection to the mail server");
        assertEquals(0, auditRecords(data));
    }
}
