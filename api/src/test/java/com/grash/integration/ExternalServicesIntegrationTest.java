package com.grash.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.grash.integration.support.FaultInjector;
import com.grash.integration.support.TestApi;
import com.grash.integration.support.TestOrganizations;
import com.grash.integration.support.TestOrganizations.CommonData;
import com.grash.integration.support.TestRecords;
import com.grash.model.enums.AuditSource;
import com.grash.model.enums.EntityType;
import com.grash.service.AuditTarget;
import com.grash.service.ExternalSendService;
import com.grash.service.ExternalSendService.Channel;
import com.grash.utils.AfterCommit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * P-23: the contract of mail, notifications and webhooks. If they fail or take too long, the operation of the user
 * is neither rolled back nor held, and the failure is recorded in the audit log. Nothing leaves the machine.
 */
@AutoConfigureMockMvc
@Timeout(value = 2, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class ExternalServicesIntegrationTest extends AbstractIntegrationTest {

    private final TestOrganizations organizations;
    private final TestApi api;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;
    private final TestRecords records;
    private final ExternalSendService externalSendService;
    private final TransactionTemplate transaction;
    private final ApplicationContext context;
    private final FaultInjector faults;
    private final List<CommonData> created = new ArrayList<>();

    @Autowired
    ExternalServicesIntegrationTest(TestOrganizations organizations, TestRecords records, MockMvc mockMvc,
                                    ObjectMapper objectMapper, JdbcTemplate jdbc,
                                    ExternalSendService externalSendService, TransactionTemplate transaction,
                                    ApplicationContext context) {
        this.organizations = organizations;
        this.records = records;
        this.api = new TestApi(mockMvc, objectMapper);
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
        this.externalSendService = externalSendService;
        this.transaction = transaction;
        this.context = context;
        this.faults = new FaultInjector(jdbc);
    }

    @AfterEach
    void cleanUp() {
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

    private AuditTarget companyTarget(CommonData data) {
        return new AuditTarget(EntityType.COMPANY, data.o1().company().getId(), null,
                data.o1().company().getName(), data.o1().company().getId());
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
}
