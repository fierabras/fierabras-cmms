package com.grash.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grash.integration.support.FaultInjector;
import com.grash.integration.support.FaultInjector.Fault;
import com.grash.integration.support.FaultInjector.Operation;
import com.grash.integration.support.TestApi;
import com.grash.integration.support.TestApi.Response;
import com.grash.integration.support.TestOrganizations;
import com.grash.integration.support.TestOrganizations.CommonData;
import com.grash.integration.support.TestRecords;
import com.grash.model.Company;
import com.grash.model.Part;
import com.grash.model.PartCategory;
import com.grash.model.PartQuantity;
import com.grash.model.PartTransaction;
import com.grash.model.PurchaseOrder;
import com.grash.model.User;
import com.grash.model.WorkOrder;
import com.grash.model.enums.ApprovalStatus;
import com.grash.model.enums.Status;
import com.grash.repository.PartCategoryRepository;
import com.grash.repository.PartQuantityRepository;
import com.grash.repository.PartTransactionRepository;
import com.grash.repository.PurchaseOrderRepository;
import com.grash.repository.PreventiveMaintenanceRepository;
import com.grash.repository.WorkflowRepository;
import com.grash.service.WebhookDispatchService;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.grash.integration.support.CallRecorder.Call;
import com.grash.model.Asset;
import com.grash.model.PreventiveMaintenance;
import com.grash.model.Workflow;
import com.grash.model.WorkflowAction;
import com.grash.model.WorkflowCondition;
import com.grash.model.enums.webhook.WebhookEvent;
import com.grash.model.enums.workflow.PartCondition;
import com.grash.model.enums.workflow.WFMainCondition;
import org.slf4j.LoggerFactory;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;

/**
 * P-16: deletion of parts, part categories, purchase order categories, vendors and sets of parts, and the closed
 * state of a purchase order (PPB-02, 05, 08, 14, 15, 17, 21 to 23, 25 to 27; CA-PPB-007, 008, 009, 020, 038, 069,
 * 070, 085, 103, 108, 124).
 */
@AutoConfigureMockMvc
class InventoryPurchasingDeletionIntegrationTest extends AbstractIntegrationTest {

    static final String DELETED_BODY = "{\"success\":true,\"message\":\"Deleted successfully\"}";

    final TestOrganizations organizations;
    final TestRecords records;
    final TestApi api;
    final ObjectMapper objectMapper;
    final JdbcTemplate jdbc;
    final PartCategoryRepository partCategoryRepository;
    final PartQuantityRepository partQuantityRepository;
    final PartTransactionRepository partTransactionRepository;
    final PurchaseOrderRepository purchaseOrderRepository;
    @Autowired
    WorkflowRepository workflowRepository;
    @Autowired
    PreventiveMaintenanceRepository preventiveMaintenanceRepository;
    final FaultInjector faults;
    private final List<CommonData> created = new ArrayList<>();

    @Autowired
    InventoryPurchasingDeletionIntegrationTest(TestOrganizations organizations, TestRecords records,
                                              MockMvc mockMvc, ObjectMapper objectMapper, JdbcTemplate jdbc,
                                              PartCategoryRepository partCategoryRepository,
                                              PartQuantityRepository partQuantityRepository,
                                              PartTransactionRepository partTransactionRepository,
                                              PurchaseOrderRepository purchaseOrderRepository) {
        this.organizations = organizations;
        this.records = records;
        this.api = new TestApi(mockMvc, objectMapper);
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
        this.partCategoryRepository = partCategoryRepository;
        this.partQuantityRepository = partQuantityRepository;
        this.partTransactionRepository = partTransactionRepository;
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.faults = new FaultInjector(jdbc);
    }

    @AfterEach
    void cleanUp() {
        faults.removeAll();
        created.forEach(organizations::delete);
        created.clear();
    }

    CommonData data() {
        CommonData data = organizations.create();
        created.add(data);
        return data;
    }

    // --- records ---

    Part part(Company company, double quantity) {
        Part part = records.part(company, null);
        jdbc.update("UPDATE part SET quantity = ? WHERE id = ?", quantity, part.getId());
        return part;
    }

    PartCategory partCategory(Company company) {
        PartCategory category = new PartCategory();
        category.setName(TestRecords.unique("P-16 part category"));
        category.setCompanySettings(company.getCompanySettings());
        return partCategoryRepository.saveAndFlush(category);
    }

    PurchaseOrder purchaseOrder(Company company, ApprovalStatus status) {
        PurchaseOrder purchaseOrder = new PurchaseOrder();
        purchaseOrder.setName(TestRecords.unique("P-16 PO"));
        purchaseOrder.setStatus(status);
        purchaseOrder.setCompany(company);
        return purchaseOrderRepository.saveAndFlush(purchaseOrder);
    }

    PartQuantity line(Part part, WorkOrder workOrder, PurchaseOrder purchaseOrder, double quantity) {
        PartQuantity line = new PartQuantity(part, workOrder, purchaseOrder, quantity);
        line.setCompany(part.getCompany());
        return partQuantityRepository.saveAndFlush(line);
    }

    PartTransaction movement(Part part, WorkOrder workOrder, double quantity) {
        PartTransaction movement = new PartTransaction(part, workOrder, quantity);
        movement.setCompany(part.getCompany());
        return partTransactionRepository.saveAndFlush(movement);
    }

    PreventiveMaintenance preventiveMaintenance(Company company) {
        PreventiveMaintenance preventiveMaintenance = new PreventiveMaintenance();
        preventiveMaintenance.setName(TestRecords.unique("P-16 PM"));
        preventiveMaintenance.setTitle(preventiveMaintenance.getName());
        preventiveMaintenance.setCompany(company);
        return preventiveMaintenanceRepository.saveAndFlush(preventiveMaintenance);
    }

    Workflow partWorkflow(Company company, Part part) {
        WorkflowAction workflowAction = new WorkflowAction();
        workflowAction.setCompany(company);
        WorkflowCondition workflowCondition = new WorkflowCondition();
        workflowCondition.setCompany(company);
        workflowCondition.setPartCondition(PartCondition.PART_IS);
        workflowCondition.setPart(part);
        Workflow workflow = new Workflow();
        workflow.setCompany(company);
        workflow.setTitle(TestRecords.unique("P-16 workflow"));
        workflow.setMainCondition(WFMainCondition.PART_UPDATED);
        workflow.setAction(workflowAction);
        workflow.setSecondaryConditions(new ArrayList<>(List.of(workflowCondition)));
        return workflowRepository.saveAndFlush(workflow);
    }

    /**
     * Waits two seconds for asynchronous webhooks, then asserts that none was sent.
     */
    void assertNoWebhook() {
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertTrue(webhookDouble.calls().isEmpty(), webhookDouble.calls().toString());
    }

    // --- queries ---

    long count(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, Long.class, arguments);
    }

    long rows(String table, Long id) {
        return count("SELECT count(*) FROM " + table + " WHERE id = ?", id);
    }

    String row(String table, Long id) {
        return jdbc.queryForObject("SELECT row_to_json(t)::text FROM " + table + " t WHERE id = ?", String.class, id);
    }

    long auditRecords(CommonData data) {
        return count("SELECT count(*) FROM audit_log WHERE company_id = ?", data.o1().company().getId());
    }

    Map<String, Object> onlyAuditRecord(CommonData data) {
        return jdbc.queryForMap("SELECT entity_type, entity_id, entity_folio, entity_name, action, source, "
                + "cascade_rows::text AS cascade_rows FROM audit_log WHERE company_id = ?",
                data.o1().company().getId());
    }

    /**
     * The ON DELETE rule (a NO ACTION, c CASCADE, n SET NULL) of the foreign key on {@code table.column}, and whether
     * the column leads a valid, non-partial index.
     */
    String key(String table, String column) {
        return jdbc.queryForObject("""
                SELECT c.confdeltype::text || ' ' || (EXISTS (SELECT 1 FROM pg_index i WHERE i.indrelid = c.conrelid
                       AND i.indkey[0] = c.conkey[1] AND i.indisvalid AND i.indpred IS NULL))::text
                FROM pg_constraint c JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = c.conkey[1]
                WHERE c.contype = 'f' AND c.conrelid = ?::regclass AND a.attname = ?""", String.class, table, column);
    }

    void disable(User user) {
        jdbc.update("UPDATE own_user SET enabled = false WHERE id = ?", user.getId());
    }

    Response call(MockHttpServletRequestBuilder request, User user) {
        return api.call(request, api.login(user));
    }

    Response send(MockHttpServletRequestBuilder request, String body, User user) {
        return call(request.contentType(MediaType.APPLICATION_JSON).content(body), user);
    }

    JsonNode json(Response response) {
        try {
            return objectMapper.readTree(response.body());
        } catch (Exception e) {
            throw new AssertionError("not JSON: " + response, e);
        }
    }

    /**
     * 409 with {@code code}, {@code success = false}, a message and exactly {@code types} as blocking types (none if
     * empty), without SQL or constraint names.
     */
    void assertConflict(Response response, String code, String... types) {
        assertEquals(409, response.status(), response.toString());
        JsonNode body = json(response);
        TreeSet<String> blocking = new TreeSet<>();
        if (body.has("blockingTypes")) body.get("blockingTypes").forEach(type -> blocking.add(type.asText()));
        assertAll(
                () -> assertFalse(body.get("success").asBoolean()),
                () -> assertEquals(code, body.get("code").asText()),
                () -> assertFalse(body.get("message").asText().isBlank()),
                () -> assertEquals(new TreeSet<>(List.of(types)), blocking, response.toString()),
                () -> assertFalse(response.body().toLowerCase().contains("constraint")
                        || response.body().contains("fk") || response.body().contains("SQL"), response.toString()));
    }

    // =====================================================================================================
    // Block 1: part category
    // =====================================================================================================

    @Nested
    class PartCategoryDeletion {

        private Response deleteCategory(PartCategory category, User user) {
            return call(delete("/part-categories/" + category.getId()), user);
        }

        @Test
        void ca007_usedByAPartWithAMovement_409PartCategoryInUse_partAndMovementUnchanged() {
            CommonData data = data();
            Company company = data.o1().company();
            PartCategory k1 = partCategory(company);
            Part p1 = part(company, 5);
            jdbc.update("UPDATE part SET category_id = ? WHERE id = ?", k1.getId(), p1.getId());
            WorkOrder workOrder = records.workOrder(company, null, Status.OPEN, false);
            PartTransaction movement = movement(p1, workOrder, 1);
            String partBefore = row("part", p1.getId());
            String movementBefore = row("part_transaction", movement.getId());

            Response response = deleteCategory(k1, data.o1().admin());

            assertConflict(response, "PART_CATEGORY_IN_USE", "PART");
            assertAll(
                    () -> assertEquals(1, rows("part_category", k1.getId())),
                    () -> assertEquals(partBefore, row("part", p1.getId())),
                    () -> assertEquals(movementBefore, row("part_transaction", movement.getId())),
                    () -> assertEquals(0, auditRecords(data)),
                    () -> assertTrue(webhookDouble.calls().isEmpty()));
        }

        @Test
        void ca008_unused_200_deleted_recorded() {
            CommonData data = data();
            PartCategory k2 = partCategory(data.o1().company());

            Response response = deleteCategory(k2, data.o1().admin());

            assertEquals(200, response.status(), response.toString());
            assertEquals(DELETED_BODY, response.body());
            assertEquals(0, rows("part_category", k2.getId()));
            Map<String, Object> record = onlyAuditRecord(data);
            assertAll(
                    () -> assertEquals("PART_CATEGORY", record.get("entity_type")),
                    () -> assertEquals(k2.getId(), record.get("entity_id")),
                    () -> assertEquals(k2.getName(), record.get("entity_name")),
                    () -> assertNull(record.get("entity_folio")),
                    () -> assertEquals("DELETE", record.get("action")),
                    () -> assertEquals("{}", record.get("cascade_rows")));
        }

        @Test
        void partAssignedBetweenCheckAndDeletion_409WithPart_never500() {
            CommonData data = data();
            Company company = data.o1().company();
            PartCategory category = partCategory(company);
            Part part = part(company, 0);

            Response response;
            try (Fault fault = faults.runBefore("part_category", Operation.DELETE,
                    "UPDATE part SET category_id = " + category.getId() + " WHERE id = " + part.getId())) {
                response = deleteCategory(category, data.o1().admin());
                assertEquals(1, fault.hits());
            }

            assertConflict(response, "PART_CATEGORY_IN_USE", "PART");
            assertEquals(1, rows("part", part.getId()));
            assertEquals(0, auditRecords(data));
        }

        @Test
        void otherOrganization_inUse_403_never409() {
            CommonData data = data();
            Company company = data.o1().company();
            PartCategory category = partCategory(company);
            Part part = part(company, 0);
            jdbc.update("UPDATE part SET category_id = ? WHERE id = ?", category.getId(), part.getId());

            Response response = deleteCategory(category, data.o2().admin());

            assertEquals(403, response.status(), response.toString());
            assertFalse(response.body().contains("IN_USE"), response.toString());
            assertEquals(1, rows("part_category", category.getId()));
        }

        @Test
        void schema_partCategoryIdNoActionIndexed() {
            assertEquals("a true", key("part", "category_id"));
        }
    }

    // =====================================================================================================
    // Block 2: part
    // =====================================================================================================

    @Nested
    class PartDeletion {

        private Response deletePart(Part part, User user) {
            return call(delete("/parts/" + part.getId()), user);
        }

        private void assertPartKept(Part part, String before, CommonData data) {
            assertAll(
                    () -> assertEquals(before, row("part", part.getId())),
                    () -> assertEquals(0, auditRecords(data)));
            assertNoWebhook();
        }

        @Test
        void ca009_withAWorkOrderLineAndAMovement_409PartInUse_unchanged_noPartDeleteWebhook() {
            CommonData data = data();
            Company company = data.o1().company();
            records.endpoint(company, WebhookEvent.PART_DELETE);
            Part p2 = part(company, 5);
            WorkOrder workOrder = records.workOrder(company, null, Status.OPEN, false);
            PartQuantity line = line(p2, workOrder, null, 1);
            PartTransaction movement = movement(p2, workOrder, 1);
            String before = row("part", p2.getId());
            String lineBefore = row("part_quantity", line.getId());
            String movementBefore = row("part_transaction", movement.getId());

            Response response = deletePart(p2, data.o1().admin());

            assertConflict(response, "PART_IN_USE", "WORK_ORDER", "PART_TRANSACTION");
            assertEquals(lineBefore, row("part_quantity", line.getId()));
            assertEquals(movementBefore, row("part_transaction", movement.getId()));
            assertPartKept(p2, before, data);
        }

        @Test
        void lineOfAPurchaseOrder_409WithPurchaseOrder() {
            CommonData data = data();
            Company company = data.o1().company();
            Part part = part(company, 0);
            line(part, null, purchaseOrder(company, ApprovalStatus.PENDING), 2);
            String before = row("part", part.getId());

            assertConflict(deletePart(part, data.o1().admin()), "PART_IN_USE", "PURCHASE_ORDER");
            assertPartKept(part, before, data);
        }

        @Test
        void movementOnly_409WithPartTransaction() {
            CommonData data = data();
            Company company = data.o1().company();
            Part part = part(company, 0);
            movement(part, records.workOrder(company, null, Status.OPEN, false), 1);
            String before = row("part", part.getId());

            assertConflict(deletePart(part, data.o1().admin()), "PART_IN_USE", "PART_TRANSACTION");
            assertPartKept(part, before, data);
        }

        @Test
        void citedByAWorkflowCondition_409WithWorkflowCondition_workflowKept() {
            CommonData data = data();
            Company company = data.o1().company();
            Part part = part(company, 0);
            Workflow workflow = partWorkflow(company, part);
            String before = row("part", part.getId());

            assertConflict(deletePart(part, data.o1().admin()), "PART_IN_USE", "WORKFLOW_CONDITION");
            assertEquals(1, rows("workflow", workflow.getId()));
            assertEquals(1, rows("workflow_condition", workflow.getSecondaryConditions().iterator().next().getId()));
            assertPartKept(part, before, data);
        }

        @Test
        void disabledUserAssigned_409WithUser_enabledUser_200() {
            CommonData data = data();
            Company company = data.o1().company();
            Part blocked = part(company, 0);
            Part free = part(company, 0);
            jdbc.update("INSERT INTO t_part_user_associations (id_part, id_user) VALUES (?, ?)", blocked.getId(),
                    data.o1().technician().getId());
            jdbc.update("INSERT INTO t_part_user_associations (id_part, id_user) VALUES (?, ?)", free.getId(),
                    data.o1().owner().getId());
            disable(data.o1().technician());
            String before = row("part", blocked.getId());

            assertConflict(deletePart(blocked, data.o1().admin()), "PART_IN_USE", "USER");
            assertPartKept(blocked, before, data);
            assertEquals(200, deletePart(free, data.o1().admin()).status());
        }

        @Test
        void onAnArchivedAsset_409WithAsset_activeAsset_200() {
            CommonData data = data();
            Company company = data.o1().company();
            Part blocked = part(company, 0);
            Part free = part(company, 0);
            Asset archived = records.asset(company);
            Asset active = records.asset(company);
            jdbc.update("INSERT INTO t_asset_part_associations (id_asset, id_part) VALUES (?, ?)", archived.getId(),
                    blocked.getId());
            jdbc.update("INSERT INTO t_asset_part_associations (id_asset, id_part) VALUES (?, ?)", active.getId(),
                    free.getId());
            jdbc.update("UPDATE asset SET archived = true WHERE id = ?", archived.getId());
            String before = row("part", blocked.getId());

            assertConflict(deletePart(blocked, data.o1().admin()), "PART_IN_USE", "ASSET");
            assertPartKept(blocked, before, data);
            assertEquals(200, deletePart(free, data.o1().admin()).status());
        }

        @Test
        void onADisabledPreventiveMaintenance_409WithPreventiveMaintenance_enabled_200() {
            CommonData data = data();
            Company company = data.o1().company();
            Part blocked = part(company, 0);
            Part free = part(company, 0);
            PreventiveMaintenance disabled = preventiveMaintenance(company);
            PreventiveMaintenance enabled = preventiveMaintenance(company);
            jdbc.update("UPDATE schedule SET disabled = true WHERE id = ?", disabled.getSchedule().getId());
            jdbc.update("INSERT INTO part_preventive_maintenances (part_id, preventive_maintenances_id) "
                    + "VALUES (?, ?)", blocked.getId(), disabled.getId());
            jdbc.update("INSERT INTO part_preventive_maintenances (part_id, preventive_maintenances_id) "
                    + "VALUES (?, ?)", free.getId(), enabled.getId());
            String before = row("part", blocked.getId());

            assertConflict(deletePart(blocked, data.o1().admin()), "PART_IN_USE", "PREVENTIVE_MAINTENANCE");
            assertPartKept(blocked, before, data);
            assertEquals(200, deletePart(free, data.o1().admin()).status());
        }

        @Test
        void movementAddedBetweenCheckAndDeletion_409WithPartTransaction_never500_noWebhook() {
            CommonData data = data();
            Company company = data.o1().company();
            records.endpoint(company, WebhookEvent.PART_DELETE);
            Part part = part(company, 0);
            PartTransaction other = movement(part(company, 0), records.workOrder(company, null, Status.OPEN, false),
                    1);

            Response response;
            try (Fault fault = faults.runBefore("part", Operation.DELETE, "UPDATE part_transaction SET part_id = "
                    + part.getId() + " WHERE id = " + other.getId())) {
                response = deletePart(part, data.o1().admin());
                assertEquals(1, fault.hits());
            }

            assertConflict(response, "PART_IN_USE", "PART_TRANSACTION");
            assertEquals(1, rows("part", part.getId()));
            assertEquals(0, auditRecords(data));
            assertNoWebhook();
        }

        @Test
        void lineAddedBetweenCheckAndDeletion_409PartInUseWithoutTypes_never500() {
            // D-P16-1: the rejecting table is part_quantity, which is neither WORK_ORDER nor PURCHASE_ORDER
            CommonData data = data();
            Company company = data.o1().company();
            Part part = part(company, 0);
            PartQuantity other = line(part(company, 0), null, purchaseOrder(company, ApprovalStatus.PENDING), 1);

            Response response;
            try (Fault fault = faults.runBefore("part", Operation.DELETE, "UPDATE part_quantity SET part_id = "
                    + part.getId() + " WHERE id = " + other.getId())) {
                response = deletePart(part, data.o1().admin());
                assertEquals(1, fault.hits());
            }

            assertConflict(response, "PART_IN_USE");
            assertEquals(1, rows("part", part.getId()));
        }

        @Test
        void ca069_deletable_200_webhookSentAfterTheCommit() {
            CommonData data = data();
            Company company = data.o1().company();
            records.endpoint(company, WebhookEvent.PART_DELETE);
            Part p6 = part(company, 0);

            Response response = deletePart(p6, data.o1().admin());

            assertEquals(200, response.status(), response.toString());
            assertEquals(DELETED_BODY, response.body());
            assertEquals(0, rows("part", p6.getId()));
            List<Call> sent = webhookDouble.awaitCalls(WebhookEvent.PART_DELETE.name(), 1, Duration.ofSeconds(10));
            assertEquals(1, sent.size());
            assertTrue(String.valueOf(sent.get(0).arguments().get(1)).contains("\"partId\":" + p6.getId()),
                    sent.toString());
            assertEquals("PART", onlyAuditRecord(data).get("entity_type"));
        }

        @Test
        void deletionRolledBack_partKept_noWebhook() {
            // PPB-25: the webhook leaves only after the commit, never when the deletion is rolled back
            CommonData data = data();
            Company company = data.o1().company();
            records.endpoint(company, WebhookEvent.PART_DELETE);
            Part part = part(company, 0);

            Response response;
            try (Fault fault = faults.failOn("audit_log", Operation.INSERT)) {
                response = deletePart(part, data.o1().admin());
                assertEquals(1, fault.hits());
            }

            assertFalse(response.is2xx(), response.toString());
            assertEquals(1, rows("part", part.getId()));
            assertNoWebhook();
        }

        @Test
        void ca070_webhookFails_200_partDeleted_failureLoggedWithoutBodyOrSecret() {
            CommonData data = data();
            Company company = data.o1().company();
            records.endpoint(company, WebhookEvent.PART_DELETE);
            Part p7 = part(company, 0);
            webhookDouble.failOn(WebhookEvent.PART_DELETE.name(), new IllegalStateException("P-16 webhook down"));
            Logger logger = (Logger) LoggerFactory.getLogger(WebhookDispatchService.class);
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            logger.addAppender(appender);
            Response response;
            try {
                response = deletePart(p7, data.o1().admin());
                webhookDouble.awaitCalls(WebhookEvent.PART_DELETE.name(), 1, Duration.ofSeconds(10));
                long limit = System.nanoTime() + Duration.ofSeconds(10).toNanos();
                while (appender.list.stream().noneMatch(event -> event.getLevel() == Level.ERROR)
                        && System.nanoTime() < limit) Thread.onSpinWait();
            } finally {
                logger.detachAppender(appender);
            }
            List<ILoggingEvent> failures = appender.list.stream().filter(event -> event.getLevel() == Level.ERROR)
                    .toList();

            assertEquals(200, response.status(), response.toString());
            assertEquals(0, rows("part", p7.getId()), "the failure does not bring the part back");
            assertEquals("PART", onlyAuditRecord(data).get("entity_type"));
            assertEquals(1, failures.size(), appender.list.toString());
            String logged = failures.get(0).getFormattedMessage() + " "
                    + (failures.get(0).getThrowableProxy() == null ? "" : failures.get(0).getThrowableProxy()
                    .getMessage());
            assertAll(
                    () -> assertFalse(logged.contains("p10-secret"), "no secret of the endpoint: " + logged),
                    () -> assertFalse(logged.contains(p7.getName()), "no body: " + logged),
                    () -> assertFalse(logged.contains("partId"), "no body: " + logged));
        }

        @Test
        void otherOrganization_inUse_403_never409_noWebhook() {
            CommonData data = data();
            Company company = data.o1().company();
            records.endpoint(company, WebhookEvent.PART_DELETE);
            Part part = part(company, 0);
            line(part, null, purchaseOrder(company, ApprovalStatus.PENDING), 1);

            Response response = deletePart(part, data.o2().admin());

            assertEquals(403, response.status(), response.toString());
            assertFalse(response.body().contains("IN_USE"), response.toString());
            assertEquals(1, rows("part", part.getId()));
            assertNoWebhook();
        }

        @Test
        void schema_referencesToPartNoActionIndexed() {
            assertAll(
                    () -> assertEquals("a true", key("part_quantity", "part_id")),
                    () -> assertEquals("a true", key("part_transaction", "part_id")),
                    () -> assertEquals("a true", key("workflow_condition", "part_id")));
        }
    }
}
