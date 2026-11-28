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
import com.grash.model.enums.PartTransactionOrigin;
import com.grash.model.enums.Status;
import com.grash.repository.PartCategoryRepository;
import com.grash.repository.PartQuantityRepository;
import com.grash.repository.PartTransactionRepository;
import com.grash.repository.PurchaseOrderRepository;
import com.grash.repository.PreventiveMaintenanceRepository;
import com.grash.repository.WorkflowRepository;
import com.grash.repository.PurchaseOrderCategoryRepository;
import com.grash.repository.VendorRepository;
import com.grash.repository.MultiPartsRepository;
import com.grash.model.MultiParts;
import com.grash.service.WorkflowService;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import com.grash.model.PurchaseOrderCategory;
import com.grash.model.Vendor;
import com.grash.model.enums.workflow.PurchaseOrderAction;
import com.grash.model.enums.workflow.PurchaseOrderCondition;
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
    @Autowired
    PurchaseOrderCategoryRepository purchaseOrderCategoryRepository;
    @Autowired
    VendorRepository vendorRepository;
    @Autowired
    MultiPartsRepository multiPartsRepository;
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
        // P-16b: a consumption is an exit (negative) with its origin
        PartTransaction movement = new PartTransaction(part, -quantity, workOrder == null
                ? PartTransactionOrigin.RESTOCK : PartTransactionOrigin.CONSUMPTION, workOrder, null, null);
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

    PurchaseOrderCategory purchaseOrderCategory(Company company) {
        PurchaseOrderCategory category = new PurchaseOrderCategory();
        category.setName(TestRecords.unique("P-16 PO category"));
        category.setCompanySettings(company.getCompanySettings());
        return purchaseOrderCategoryRepository.saveAndFlush(category);
    }

    Vendor vendor(Company company) {
        Vendor vendor = new Vendor();
        vendor.setName(TestRecords.unique("P-16 vendor"));
        vendor.setCompanyName(vendor.getName());
        vendor.setCompany(company);
        return vendorRepository.saveAndFlush(vendor);
    }

    /**
     * A workflow on purchase orders with {@code action} (and its vendor or category) and, when {@code condition} is
     * not {@code null}, one condition citing {@code conditionVendor} or {@code conditionCategory}.
     */
    Workflow purchaseOrderWorkflow(Company company, WFMainCondition main, PurchaseOrderAction action,
                                   Vendor actionVendor, PurchaseOrderCategory actionCategory,
                                   PurchaseOrderCondition condition, Vendor conditionVendor,
                                   PurchaseOrderCategory conditionCategory) {
        WorkflowAction workflowAction = new WorkflowAction();
        workflowAction.setCompany(company);
        workflowAction.setPurchaseOrderAction(action);
        workflowAction.setVendor(actionVendor);
        workflowAction.setPurchaseOrderCategory(actionCategory);
        List<WorkflowCondition> conditions = new ArrayList<>();
        if (condition != null) {
            WorkflowCondition workflowCondition = new WorkflowCondition();
            workflowCondition.setCompany(company);
            workflowCondition.setPurchaseOrderCondition(condition);
            workflowCondition.setVendor(conditionVendor);
            workflowCondition.setPurchaseOrderCategory(conditionCategory);
            conditions.add(workflowCondition);
        }
        Workflow workflow = new Workflow();
        workflow.setCompany(company);
        workflow.setTitle(TestRecords.unique("P-16 workflow"));
        workflow.setMainCondition(main);
        workflow.setAction(workflowAction);
        workflow.setSecondaryConditions(conditions);
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

    double stock(Part part) {
        return jdbc.queryForObject("SELECT quantity FROM part WHERE id = ?", Double.class, part.getId());
    }

    String status(Long purchaseOrder) {
        return ApprovalStatus.values()[jdbc.queryForObject("SELECT status FROM purchase_order WHERE id = ?",
                Integer.class, purchaseOrder)].name();
    }

    List<String> lines(PurchaseOrder purchaseOrder) {
        return jdbc.queryForList("SELECT row_to_json(q)::text FROM part_quantity q WHERE purchase_order_id = ? "
                + "ORDER BY id", String.class, purchaseOrder.getId());
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
        void everyDependent_409WithEveryType() {
            // each check reports its type; without one, the others reject before the foreign key and the type is lost
            CommonData data = data();
            Company company = data.o1().company();
            Part part = part(company, 0);
            WorkOrder workOrder = records.workOrder(company, null, Status.OPEN, false);
            line(part, workOrder, null, 1);
            line(part, null, purchaseOrder(company, ApprovalStatus.PENDING), 1);
            movement(part, workOrder, 1);
            partWorkflow(company, part);
            jdbc.update("INSERT INTO t_part_user_associations (id_part, id_user) VALUES (?, ?)", part.getId(),
                    data.o1().technician().getId());
            disable(data.o1().technician());
            Asset asset = records.asset(company);
            jdbc.update("INSERT INTO t_asset_part_associations (id_asset, id_part) VALUES (?, ?)", asset.getId(),
                    part.getId());
            jdbc.update("UPDATE asset SET archived = true WHERE id = ?", asset.getId());
            PreventiveMaintenance preventiveMaintenance = preventiveMaintenance(company);
            jdbc.update("UPDATE schedule SET disabled = true WHERE id = ?",
                    preventiveMaintenance.getSchedule().getId());
            jdbc.update("INSERT INTO part_preventive_maintenances (part_id, preventive_maintenances_id) "
                    + "VALUES (?, ?)", part.getId(), preventiveMaintenance.getId());

            assertConflict(deletePart(part, data.o1().admin()), "PART_IN_USE", "WORK_ORDER", "PURCHASE_ORDER",
                    "PART_TRANSACTION", "WORKFLOW_CONDITION", "USER", "ASSET", "PREVENTIVE_MAINTENANCE");
            assertEquals(1, rows("part", part.getId()));
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
            // P-23 (D-1): before, only the deletion was recorded; the failure of the webhook is now recorded too,
            // against the part, from the thread of the send
            String records = "SELECT action || ' ' || entity_type || ' ' || entity_id FROM audit_log "
                    + "WHERE company_id = ? ORDER BY id";
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (auditRecords(data) < 2 && System.nanoTime() < deadline) Thread.onSpinWait();
            assertEquals(List.of("DELETE PART " + p7.getId(), "WEBHOOK_FAILED PART " + p7.getId()),
                    jdbc.queryForList(records, String.class, company.getId()));
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

    // =====================================================================================================
    // Block 3: purchase order category and vendor
    // =====================================================================================================

    @Nested
    class PurchaseOrderCategoryDeletion {

        private Response deleteCategory(PurchaseOrderCategory category, User user) {
            return call(delete("/purchase-order-categories/" + category.getId()), user);
        }

        @Test
        void ca103_usedByAPurchaseOrder_409PurchaseOrderCategoryInUse_unchanged() {
            CommonData data = data();
            Company company = data.o1().company();
            PurchaseOrderCategory kp1 = purchaseOrderCategory(company);
            PurchaseOrder purchaseOrder = purchaseOrder(company, ApprovalStatus.PENDING);
            jdbc.update("UPDATE purchase_order SET category_id = ? WHERE id = ?", kp1.getId(),
                    purchaseOrder.getId());
            String before = row("purchase_order", purchaseOrder.getId());

            Response response = deleteCategory(kp1, data.o1().admin());

            assertConflict(response, "PURCHASE_ORDER_CATEGORY_IN_USE", "PURCHASE_ORDER");
            assertEquals(1, rows("purchase_order_category", kp1.getId()));
            assertEquals(before, row("purchase_order", purchaseOrder.getId()));
            assertEquals(0, auditRecords(data));
        }

        @Test
        void citedByAWorkflowActionAndCondition_409WithBoth_workflowKept() {
            CommonData data = data();
            Company company = data.o1().company();
            PurchaseOrderCategory category = purchaseOrderCategory(company);
            Workflow workflow = purchaseOrderWorkflow(company, WFMainCondition.PURCHASE_ORDER_CREATED,
                    PurchaseOrderAction.ASSIGN_CATEGORY, null, category, PurchaseOrderCondition.CATEGORY_IS, null,
                    category);

            Response response = deleteCategory(category, data.o1().admin());

            assertConflict(response, "PURCHASE_ORDER_CATEGORY_IN_USE", "WORKFLOW_ACTION", "WORKFLOW_CONDITION");
            assertEquals(1, rows("workflow", workflow.getId()));
            assertEquals(1, rows("purchase_order_category", category.getId()));
        }

        @Test
        void everyDependent_409WithEveryType() {
            CommonData data = data();
            Company company = data.o1().company();
            PurchaseOrderCategory category = purchaseOrderCategory(company);
            PurchaseOrder purchaseOrder = purchaseOrder(company, ApprovalStatus.PENDING);
            jdbc.update("UPDATE purchase_order SET category_id = ? WHERE id = ?", category.getId(),
                    purchaseOrder.getId());
            purchaseOrderWorkflow(company, WFMainCondition.PURCHASE_ORDER_CREATED,
                    PurchaseOrderAction.ASSIGN_CATEGORY, null, category, PurchaseOrderCondition.CATEGORY_IS, null,
                    category);

            assertConflict(deleteCategory(category, data.o1().admin()), "PURCHASE_ORDER_CATEGORY_IN_USE",
                    "PURCHASE_ORDER", "WORKFLOW_ACTION", "WORKFLOW_CONDITION");
        }

        @Test
        void unused_200_deleted_recorded() {
            CommonData data = data();
            PurchaseOrderCategory category = purchaseOrderCategory(data.o1().company());

            Response response = deleteCategory(category, data.o1().admin());

            assertEquals(200, response.status(), response.toString());
            assertEquals(DELETED_BODY, response.body());
            assertEquals(0, rows("purchase_order_category", category.getId()));
            Map<String, Object> record = onlyAuditRecord(data);
            assertAll(
                    () -> assertEquals("PURCHASE_ORDER_CATEGORY", record.get("entity_type")),
                    () -> assertEquals(category.getId(), record.get("entity_id")),
                    () -> assertEquals(category.getName(), record.get("entity_name")),
                    () -> assertEquals("{}", record.get("cascade_rows")));
        }

        @Test
        void purchaseOrderAssignedBetweenCheckAndDeletion_409WithPurchaseOrder_never500() {
            CommonData data = data();
            Company company = data.o1().company();
            PurchaseOrderCategory category = purchaseOrderCategory(company);
            PurchaseOrder purchaseOrder = purchaseOrder(company, ApprovalStatus.PENDING);

            Response response;
            try (Fault fault = faults.runBefore("purchase_order_category", Operation.DELETE,
                    "UPDATE purchase_order SET category_id = " + category.getId() + " WHERE id = "
                            + purchaseOrder.getId())) {
                response = deleteCategory(category, data.o1().admin());
                assertEquals(1, fault.hits());
            }

            assertConflict(response, "PURCHASE_ORDER_CATEGORY_IN_USE", "PURCHASE_ORDER");
            assertEquals(1, rows("purchase_order_category", category.getId()));
            assertEquals(0, auditRecords(data));
        }

        @Test
        void otherOrganization_inUse_403_never409() {
            CommonData data = data();
            Company company = data.o1().company();
            PurchaseOrderCategory category = purchaseOrderCategory(company);
            PurchaseOrder purchaseOrder = purchaseOrder(company, ApprovalStatus.PENDING);
            jdbc.update("UPDATE purchase_order SET category_id = ? WHERE id = ?", category.getId(),
                    purchaseOrder.getId());

            Response response = deleteCategory(category, data.o2().admin());

            assertEquals(403, response.status(), response.toString());
            assertFalse(response.body().contains("IN_USE"), response.toString());
            assertEquals(1, rows("purchase_order_category", category.getId()));
        }

        @Test
        void schema_referencesToPurchaseOrderCategoryNoActionIndexed() {
            assertAll(
                    () -> assertEquals("a true", key("purchase_order", "category_id")),
                    () -> assertEquals("a true", key("workflow_action", "purchase_order_category_id")),
                    () -> assertEquals("a true", key("workflow_condition", "purchase_order_category_id")));
        }
    }

    @Nested
    class VendorDeletion {

        private Response deleteVendor(Vendor vendor, User user) {
            return call(delete("/vendors/" + vendor.getId()), user);
        }

        @Test
        void ca108_citedByAPurchaseOrder_409VendorInUse_unchanged() {
            CommonData data = data();
            Company company = data.o1().company();
            Vendor v1 = vendor(company);
            PurchaseOrder purchaseOrder = purchaseOrder(company, ApprovalStatus.APPROVED);
            jdbc.update("UPDATE purchase_order SET vendor_id = ? WHERE id = ?", v1.getId(), purchaseOrder.getId());
            String before = row("vendor", v1.getId());
            String purchaseOrderBefore = row("purchase_order", purchaseOrder.getId());

            Response response = deleteVendor(v1, data.o1().admin());

            assertConflict(response, "VENDOR_IN_USE", "PURCHASE_ORDER");
            assertEquals(before, row("vendor", v1.getId()));
            assertEquals(purchaseOrderBefore, row("purchase_order", purchaseOrder.getId()));
            assertEquals(0, auditRecords(data));
        }

        @Test
        void citedByAWorkflowActionAndCondition_409WithBoth_workflowKept() {
            CommonData data = data();
            Company company = data.o1().company();
            Vendor vendor = vendor(company);
            Workflow workflow = purchaseOrderWorkflow(company, WFMainCondition.PURCHASE_ORDER_CREATED,
                    PurchaseOrderAction.ASSIGN_VENDOR, vendor, null, PurchaseOrderCondition.VENDOR_IS, vendor, null);

            Response response = deleteVendor(vendor, data.o1().admin());

            assertConflict(response, "VENDOR_IN_USE", "WORKFLOW_ACTION", "WORKFLOW_CONDITION");
            assertEquals(1, rows("workflow", workflow.getId()));
        }

        @Test
        void onAnArchivedAsset_409WithAsset_onAnActiveAsset_200_associationInTheSummary() {
            CommonData data = data();
            Company company = data.o1().company();
            Vendor blocked = vendor(company);
            Vendor free = vendor(company);
            Asset archived = records.asset(company);
            Asset active = records.asset(company);
            jdbc.update("INSERT INTO t_asset_vendor_associations (id_asset, id_vendor) VALUES (?, ?)",
                    archived.getId(), blocked.getId());
            jdbc.update("INSERT INTO t_asset_vendor_associations (id_asset, id_vendor) VALUES (?, ?)",
                    active.getId(), free.getId());
            jdbc.update("UPDATE asset SET archived = true WHERE id = ?", archived.getId());

            assertConflict(deleteVendor(blocked, data.o1().admin()), "VENDOR_IN_USE", "ASSET");
            assertEquals(1, rows("vendor", blocked.getId()));
            Response allowed = deleteVendor(free, data.o1().admin());

            assertEquals(200, allowed.status(), allowed.toString());
            Map<String, Object> record = onlyAuditRecord(data);
            assertAll(
                    () -> assertEquals("VENDOR", record.get("entity_type")),
                    () -> assertEquals(free.getId(), record.get("entity_id")),
                    () -> assertEquals(free.getName(), record.get("entity_name")),
                    () -> assertEquals("{\"t_asset_vendor_associations\": 1}", record.get("cascade_rows")));
        }

        @Test
        void everyDependent_409WithEveryType() {
            CommonData data = data();
            Company company = data.o1().company();
            Vendor vendor = vendor(company);
            PurchaseOrder purchaseOrder = purchaseOrder(company, ApprovalStatus.PENDING);
            jdbc.update("UPDATE purchase_order SET vendor_id = ? WHERE id = ?", vendor.getId(),
                    purchaseOrder.getId());
            purchaseOrderWorkflow(company, WFMainCondition.PURCHASE_ORDER_CREATED,
                    PurchaseOrderAction.ASSIGN_VENDOR, vendor, null, PurchaseOrderCondition.VENDOR_IS, vendor, null);
            Asset asset = records.asset(company);
            jdbc.update("INSERT INTO t_asset_vendor_associations (id_asset, id_vendor) VALUES (?, ?)",
                    asset.getId(), vendor.getId());
            jdbc.update("UPDATE asset SET archived = true WHERE id = ?", asset.getId());

            assertConflict(deleteVendor(vendor, data.o1().admin()), "VENDOR_IN_USE", "PURCHASE_ORDER",
                    "WORKFLOW_ACTION", "WORKFLOW_CONDITION", "ASSET");
        }

        @Test
        void purchaseOrderAssignedBetweenCheckAndDeletion_409WithPurchaseOrder_never500() {
            CommonData data = data();
            Company company = data.o1().company();
            Vendor vendor = vendor(company);
            PurchaseOrder purchaseOrder = purchaseOrder(company, ApprovalStatus.PENDING);

            Response response;
            try (Fault fault = faults.runBefore("vendor", Operation.DELETE, "UPDATE purchase_order SET vendor_id = "
                    + vendor.getId() + " WHERE id = " + purchaseOrder.getId())) {
                response = deleteVendor(vendor, data.o1().admin());
                assertEquals(1, fault.hits());
            }

            assertConflict(response, "VENDOR_IN_USE", "PURCHASE_ORDER");
            assertEquals(1, rows("vendor", vendor.getId()));
            assertEquals(0, auditRecords(data));
        }

        @Test
        void otherOrganization_inUse_403_never409() {
            CommonData data = data();
            Company company = data.o1().company();
            Vendor vendor = vendor(company);
            PurchaseOrder purchaseOrder = purchaseOrder(company, ApprovalStatus.PENDING);
            jdbc.update("UPDATE purchase_order SET vendor_id = ? WHERE id = ?", vendor.getId(),
                    purchaseOrder.getId());

            Response response = deleteVendor(vendor, data.o2().admin());

            assertEquals(403, response.status(), response.toString());
            assertFalse(response.body().contains("IN_USE"), response.toString());
            assertEquals(1, rows("vendor", vendor.getId()));
        }

        @Test
        void schema_referencesToVendorNoActionIndexed() {
            assertAll(
                    () -> assertEquals("a true", key("purchase_order", "vendor_id")),
                    () -> assertEquals("a true", key("workflow_action", "vendor_id")),
                    () -> assertEquals("a true", key("workflow_condition", "vendor_id")));
        }
    }

    // =====================================================================================================
    // Block 4: set of parts
    // =====================================================================================================

    @Nested
    class SetDeletion {

        private MultiParts set(Company company, Part... parts) {
            MultiParts set = new MultiParts();
            set.setName(TestRecords.unique("P-16 set"));
            set.setCompany(company);
            set.setParts(new ArrayList<>(List.of(parts)));
            return multiPartsRepository.saveAndFlush(set);
        }

        @Test
        void ca124_usedOnAWorkOrder_200_workOrderKeepsItsLinesAndMovements_recorded() {
            CommonData data = data();
            Company company = data.o1().company();
            Part first = part(company, 5);
            Part second = part(company, 5);
            MultiParts mp1 = set(company, first, second);
            WorkOrder w24 = records.workOrder(company, null, Status.OPEN, false);
            String admin = api.login(data.o1().admin());
            Response added = api.call(patch("/part-quantities/work-order/" + w24.getId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("[" + first.getId() + "," + second.getId() + "]"), admin);
            assertEquals(200, added.status(), added.toString());
            List<String> linesBefore = jdbc.queryForList("SELECT row_to_json(q)::text FROM part_quantity q "
                    + "WHERE work_order_id = ? ORDER BY id", String.class, w24.getId());
            List<String> movementsBefore = jdbc.queryForList("SELECT row_to_json(t)::text FROM part_transaction t "
                    + "WHERE work_order_id = ? ORDER BY id", String.class, w24.getId());

            Response response = api.call(delete("/multi-parts/" + mp1.getId()), admin);

            assertEquals(200, response.status(), response.toString());
            assertEquals(DELETED_BODY, response.body());
            assertEquals(0, rows("multi_parts", mp1.getId()));
            assertAll(
                    () -> assertEquals(2, linesBefore.size()),
                    () -> assertEquals(linesBefore, jdbc.queryForList("SELECT row_to_json(q)::text FROM "
                            + "part_quantity q WHERE work_order_id = ? ORDER BY id", String.class, w24.getId())),
                    () -> assertEquals(2, movementsBefore.size()),
                    () -> assertEquals(movementsBefore, jdbc.queryForList("SELECT row_to_json(t)::text FROM "
                            + "part_transaction t WHERE work_order_id = ? ORDER BY id", String.class, w24.getId())),
                    () -> assertEquals(1, rows("part", first.getId()) * rows("part", second.getId())));
            Map<String, Object> record = onlyAuditRecord(data);
            assertAll(
                    () -> assertEquals("MULTI_PARTS", record.get("entity_type")),
                    () -> assertEquals(mp1.getId(), record.get("entity_id")),
                    () -> assertEquals(mp1.getName(), record.get("entity_name")),
                    () -> assertEquals("{\"t_multi_parts_part_associations\": 2}", record.get("cascade_rows")));
        }

        @Test
        void otherOrganization_403_kept_noRecord() {
            CommonData data = data();
            MultiParts set = set(data.o1().company());

            Response response = call(delete("/multi-parts/" + set.getId()), data.o2().admin());

            assertEquals(403, response.status(), response.toString());
            assertEquals(1, rows("multi_parts", set.getId()));
            assertEquals(0, auditRecords(data));
        }
    }

    // =====================================================================================================
    // Block 5: purchase order
    // =====================================================================================================

    @Nested
    class PurchaseOrderState {

        private Response respond(PurchaseOrder purchaseOrder, boolean approved, User user) {
            return call(patch("/purchase-orders/" + purchaseOrder.getId() + "/respond")
                    .param("approved", String.valueOf(approved)), user);
        }

        private Response deletePurchaseOrder(PurchaseOrder purchaseOrder, User user) {
            return call(delete("/purchase-orders/" + purchaseOrder.getId()), user);
        }

        /**
         * The purchase order, its lines, the stock of {@code part} and its movements are as {@code before}, and no
         * audit record was written. Before P-16b the part had no movement at all; since P-16b the approval of an
         * earlier answer leaves its own entry, and the rejected operation adds none.
         */
        private void assertUnchanged(PurchaseOrder purchaseOrder, String before, List<String> linesBefore, Part part,
                                     double stockBefore, CommonData data) {
            assertAll(
                    () -> assertEquals(before, row("purchase_order", purchaseOrder.getId())),
                    () -> assertEquals(linesBefore, lines(purchaseOrder)),
                    () -> assertEquals(stockBefore, stock(part)),
                    () -> assertEquals(0, count("SELECT count(*) FROM part_transaction WHERE part_id = ? "
                            + "AND origin <> 'PURCHASE_ORDER'", part.getId())),
                    () -> assertTrue(count("SELECT count(*) FROM part_transaction WHERE part_id = ?",
                            part.getId()) <= 1, "at most the entry of the approval"),
                    () -> assertEquals(0, auditRecords(data)));
        }

        @Test
        void ca020_approvedDeleted_409PurchaseOrderClosed_stockUnchanged_pendingDeleted_200WithItsLines() {
            CommonData data = data();
            Company company = data.o1().company();
            Part part = part(company, 0);
            PurchaseOrder po1 = purchaseOrder(company, ApprovalStatus.PENDING);
            line(part, null, po1, 3);
            assertEquals(200, respond(po1, true, data.o1().admin()).status());
            assertEquals(3, stock(part));
            PurchaseOrder po2 = purchaseOrder(company, ApprovalStatus.PENDING);
            PartQuantity first = line(part(company, 0), null, po2, 1);
            PartQuantity second = line(part(company, 0), null, po2, 2);
            String before = row("purchase_order", po1.getId());
            List<String> linesBefore = lines(po1);

            Response closed = deletePurchaseOrder(po1, data.o1().admin());
            assertConflict(closed, "PURCHASE_ORDER_CLOSED");
            assertUnchanged(po1, before, linesBefore, part, 3, data);
            Response pending = deletePurchaseOrder(po2, data.o1().admin());

            assertEquals(200, pending.status(), pending.toString());
            assertEquals(DELETED_BODY, pending.body());
            assertEquals(0, rows("purchase_order", po2.getId()));
            assertEquals(0, rows("part_quantity", first.getId()) + rows("part_quantity", second.getId()));
            Map<String, Object> record = onlyAuditRecord(data);
            assertAll(
                    () -> assertEquals("PURCHASE_ORDER", record.get("entity_type")),
                    () -> assertEquals(po2.getId(), record.get("entity_id")),
                    () -> assertEquals(po2.getName(), record.get("entity_name")),
                    () -> assertNull(record.get("entity_folio")),
                    () -> assertEquals("{\"part_quantity\": 2}", record.get("cascade_rows")));
        }

        @Test
        void rejectedDeleted_409PurchaseOrderClosed() {
            CommonData data = data();
            Company company = data.o1().company();
            Part part = part(company, 0);
            PurchaseOrder purchaseOrder = purchaseOrder(company, ApprovalStatus.REJECTED);
            line(part, null, purchaseOrder, 1);
            String before = row("purchase_order", purchaseOrder.getId());
            List<String> linesBefore = lines(purchaseOrder);

            assertConflict(deletePurchaseOrder(purchaseOrder, data.o1().admin()), "PURCHASE_ORDER_CLOSED");
            assertUnchanged(purchaseOrder, before, linesBefore, part, 0, data);
        }

        @Test
        void closedDeletedByTechnician_403_never409() {
            CommonData data = data();
            PurchaseOrder purchaseOrder = purchaseOrder(data.o1().company(), ApprovalStatus.APPROVED);

            Response response = deletePurchaseOrder(purchaseOrder, data.o1().technician());

            assertEquals(403, response.status(), response.toString());
            assertFalse(response.body().contains("PURCHASE_ORDER_CLOSED"), response.toString());
        }

        @Test
        void unknownDeleted_404() {
            CommonData data = data();

            Response response = call(delete("/purchase-orders/" + Long.MAX_VALUE), data.o1().admin());

            assertEquals(404, response.status(), response.toString());
        }

        @Test
        void ca085_approvedAnsweredAgain_409PurchaseOrderClosedForBoth_unchanged() {
            CommonData data = data();
            Company company = data.o1().company();
            Part part = part(company, 2);
            PurchaseOrder po4 = purchaseOrder(company, ApprovalStatus.APPROVED);
            line(part, null, po4, 4);
            String before = row("purchase_order", po4.getId());
            List<String> linesBefore = lines(po4);

            assertConflict(respond(po4, false, data.o1().admin()), "PURCHASE_ORDER_CLOSED");
            assertConflict(respond(po4, true, data.o1().admin()), "PURCHASE_ORDER_CLOSED");
            assertUnchanged(po4, before, linesBefore, part, 2, data);
            assertEquals("APPROVED", status(po4.getId()));
        }

        @Test
        void d1_rejectedAnsweredAgain_409PurchaseOrderClosedForBoth_noStockAdded() {
            CommonData data = data();
            Company company = data.o1().company();
            Part part = part(company, 0);
            PurchaseOrder purchaseOrder = purchaseOrder(company, ApprovalStatus.REJECTED);
            line(part, null, purchaseOrder, 4);
            String before = row("purchase_order", purchaseOrder.getId());
            List<String> linesBefore = lines(purchaseOrder);

            assertConflict(respond(purchaseOrder, true, data.o1().admin()), "PURCHASE_ORDER_CLOSED");
            assertConflict(respond(purchaseOrder, false, data.o1().admin()), "PURCHASE_ORDER_CLOSED");
            assertUnchanged(purchaseOrder, before, linesBefore, part, 0, data);
            assertEquals("REJECTED", status(purchaseOrder.getId()));
        }

        @Test
        void pendingApproved_200_stockAdded_pendingRejected_200_stockUnchanged() {
            CommonData data = data();
            Company company = data.o1().company();
            Part part = part(company, 1);
            PurchaseOrder approved = purchaseOrder(company, ApprovalStatus.PENDING);
            line(part, null, approved, 4);
            PurchaseOrder rejected = purchaseOrder(company, ApprovalStatus.PENDING);
            line(part, null, rejected, 9);

            Response approval = respond(approved, true, data.o1().admin());
            Response rejection = respond(rejected, false, data.o1().admin());

            assertEquals(200, approval.status(), approval.toString());
            assertEquals(200, rejection.status(), rejection.toString());
            assertEquals("APPROVED", status(approved.getId()));
            assertEquals("REJECTED", status(rejected.getId()));
            assertEquals(5, stock(part));
        }

        @Test
        void closedAnsweredByTechnician_403_never409() {
            CommonData data = data();
            PurchaseOrder purchaseOrder = purchaseOrder(data.o1().company(), ApprovalStatus.APPROVED);

            Response response = respond(purchaseOrder, true, data.o1().technician());

            assertEquals(403, response.status(), response.toString());
            assertFalse(response.body().contains("PURCHASE_ORDER_CLOSED"), response.toString());
        }

        @Test
        void e6_closedEditedWithStatusPending_200_generalDataChanged_stillClosed() {
            CommonData data = data();
            Company company = data.o1().company();
            Part part = part(company, 0);
            PurchaseOrder purchaseOrder = purchaseOrder(company, ApprovalStatus.APPROVED);
            line(part, null, purchaseOrder, 4);

            Response response = send(patch("/purchase-orders/" + purchaseOrder.getId()),
                    "{\"name\":\"P-16 renamed\",\"status\":\"PENDING\"}", data.o1().admin());

            assertEquals(200, response.status(), response.toString());
            assertEquals("P-16 renamed", jdbc.queryForObject("SELECT name FROM purchase_order WHERE id = ?",
                    String.class, purchaseOrder.getId()));
            assertEquals("APPROVED", status(purchaseOrder.getId()));
            assertEquals(0, stock(part));
        }

        @Test
        void c1_createdWithStatusApprovedOrRejected_pending() {
            CommonData data = data();

            Response approved = send(post("/purchase-orders"), "{\"name\":\"" + TestRecords.unique("P-16 PO")
                    + "\",\"status\":\"APPROVED\"}", data.o1().admin());
            Response rejected = send(post("/purchase-orders"), "{\"name\":\"" + TestRecords.unique("P-16 PO")
                    + "\",\"status\":\"REJECTED\"}", data.o1().admin());

            assertEquals(200, approved.status(), approved.toString());
            assertEquals(200, rejected.status(), rejected.toString());
            assertEquals("PENDING", status(json(approved).get("id").asLong()));
            assertEquals("PENDING", status(json(rejected).get("id").asLong()));
        }

        @Test
        void c1_createdAndApprovedByAWorkflow_approvedAsBefore() {
            // the approval adds no stock: left for P-16b (D-13)
            CommonData data = data();
            purchaseOrderWorkflow(data.o1().company(), WFMainCondition.PURCHASE_ORDER_CREATED,
                    PurchaseOrderAction.APPROVE, null, null, null, null, null);

            Response response = send(post("/purchase-orders"), "{\"name\":\"" + TestRecords.unique("P-16 PO")
                    + "\"}", data.o1().admin());

            assertEquals(200, response.status(), response.toString());
            assertEquals("APPROVED", status(json(response).get("id").asLong()));
        }

        private ListAppender<ILoggingEvent> workflowLog() {
            Logger logger = (Logger) LoggerFactory.getLogger(WorkflowService.class);
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            logger.addAppender(appender);
            return appender;
        }

        private void detach(ListAppender<ILoggingEvent> appender) {
            ((Logger) LoggerFactory.getLogger(WorkflowService.class)).detachAppender(appender);
        }

        @Test
        void d4_rejectedEdited_workflowApproveSkipped_logged() {
            CommonData data = data();
            Company company = data.o1().company();
            PurchaseOrder purchaseOrder = purchaseOrder(company, ApprovalStatus.REJECTED);
            Workflow workflow = purchaseOrderWorkflow(company, WFMainCondition.PURCHASE_ORDER_UPDATED,
                    PurchaseOrderAction.APPROVE, null, null, null, null, null);
            ListAppender<ILoggingEvent> appender = workflowLog();
            Response response;
            try {
                response = send(patch("/purchase-orders/" + purchaseOrder.getId()), "{\"name\":\"P-16 x\"}",
                        data.o1().admin());
            } finally {
                detach(appender);
            }

            assertEquals(200, response.status(), response.toString());
            assertEquals("REJECTED", status(purchaseOrder.getId()));
            assertTrue(appender.list.stream().anyMatch(event -> event.getFormattedMessage().contains("P-16")
                    && event.getFormattedMessage().contains(String.valueOf(workflow.getId()))), appender.list.toString());
        }

        @Test
        void d4_approvedEdited_workflowRejectSkipped() {
            CommonData data = data();
            Company company = data.o1().company();
            PurchaseOrder purchaseOrder = purchaseOrder(company, ApprovalStatus.APPROVED);
            purchaseOrderWorkflow(company, WFMainCondition.PURCHASE_ORDER_UPDATED, PurchaseOrderAction.REJECT, null,
                    null, null, null, null);

            Response response = send(patch("/purchase-orders/" + purchaseOrder.getId()), "{\"name\":\"P-16 x\"}",
                    data.o1().admin());

            assertEquals(200, response.status(), response.toString());
            assertEquals("APPROVED", status(purchaseOrder.getId()));
        }

        @Test
        void d4_closedEdited_workflowAssignVendorStillRuns() {
            CommonData data = data();
            Company company = data.o1().company();
            PurchaseOrder purchaseOrder = purchaseOrder(company, ApprovalStatus.APPROVED);
            Vendor vendor = vendor(company);
            purchaseOrderWorkflow(company, WFMainCondition.PURCHASE_ORDER_UPDATED, PurchaseOrderAction.ASSIGN_VENDOR,
                    vendor, null, null, null, null);

            Response response = send(patch("/purchase-orders/" + purchaseOrder.getId()), "{\"name\":\"P-16 y\"}",
                    data.o1().admin());

            assertEquals(200, response.status(), response.toString());
            assertEquals("APPROVED", status(purchaseOrder.getId()));
            assertEquals(vendor.getId(), jdbc.queryForObject("SELECT vendor_id FROM purchase_order WHERE id = ?",
                    Long.class, purchaseOrder.getId()));
        }

        @Test
        void otherOrganization_closed_deleteAndRespond_403_never409() {
            CommonData data = data();
            PurchaseOrder purchaseOrder = purchaseOrder(data.o1().company(), ApprovalStatus.APPROVED);

            Response deletion = deletePurchaseOrder(purchaseOrder, data.o2().admin());
            Response answer = respond(purchaseOrder, true, data.o2().admin());

            assertEquals(403, deletion.status(), deletion.toString());
            assertEquals(403, answer.status(), answer.toString());
            assertFalse((deletion.body() + answer.body()).contains("CLOSED"));
            assertEquals(1, rows("purchase_order", purchaseOrder.getId()));
        }
    }

    // =====================================================================================================
    // Block 6: disabled users on a part (E-10)
    // =====================================================================================================

    @Nested
    class PartUsers {

        private String partBody(String name, User... users) {
            StringBuilder assigned = new StringBuilder();
            for (User user : users) {
                if (assigned.length() > 0) assigned.append(",");
                assigned.append("{\"id\":").append(user.getId()).append("}");
            }
            return "{\"name\":\"" + name + "\",\"assignedTo\":[" + assigned + "]}";
        }

        private long assigned(Long part, User user) {
            return count("SELECT count(*) FROM t_part_user_associations WHERE id_part = ? AND id_user = ?", part,
                    user.getId());
        }

        @Test
        void disabledUserOnCreate_409UserRetired_noPart() {
            CommonData data = data();
            disable(data.o1().technician());
            String name = TestRecords.unique("P-16 part");

            Response response = send(post("/parts"), partBody(name, data.o1().technician()), data.o1().admin());

            assertConflict(response, "USER_RETIRED");
            assertEquals(0, count("SELECT count(*) FROM part WHERE name = ?", name));
        }

        @Test
        void enabledUserOnCreate_200() {
            CommonData data = data();

            Response response = send(post("/parts"), partBody(TestRecords.unique("P-16 part"),
                    data.o1().technician()), data.o1().admin());

            assertEquals(200, response.status(), response.toString());
            assertEquals(1, assigned(json(response).get("id").asLong(), data.o1().technician()));
        }

        @Test
        void disabledUserAddedOnEdit_409UserRetired_partUnchanged() {
            CommonData data = data();
            Part part = part(data.o1().company(), 0);
            disable(data.o1().technician());
            String before = row("part", part.getId());

            Response response = send(patch("/parts/" + part.getId()), partBody(part.getName(),
                    data.o1().technician()), data.o1().admin());

            assertConflict(response, "USER_RETIRED");
            assertEquals(before, row("part", part.getId()));
            assertEquals(0, assigned(part.getId(), data.o1().technician()));
        }

        @Test
        void userDisabledAfterTheAssignment_keptOnEdit_200() {
            CommonData data = data();
            Part part = part(data.o1().company(), 0);
            jdbc.update("INSERT INTO t_part_user_associations (id_part, id_user) VALUES (?, ?)", part.getId(),
                    data.o1().technician().getId());
            disable(data.o1().technician());

            Response response = send(patch("/parts/" + part.getId()), partBody(part.getName(),
                    data.o1().technician(), data.o1().owner()), data.o1().admin());

            assertEquals(200, response.status(), response.toString());
            assertEquals(1, assigned(part.getId(), data.o1().technician()), "a previous reference is kept");
            assertEquals(1, assigned(part.getId(), data.o1().owner()));
        }

        @Test
        void otherOrganizationAssignsADisabledUserOfO1_403_never409() {
            CommonData data = data();
            disable(data.o1().technician());
            String name = TestRecords.unique("P-16 part");

            Response response = send(post("/parts"), partBody(name, data.o1().technician()), data.o2().admin());

            assertEquals(403, response.status(), response.toString());
            assertFalse(response.body().contains("RETIRED"), response.toString());
            assertEquals(0, count("SELECT count(*) FROM part WHERE name = ?", name));
        }

        @Test
        void otherOrganizationEditsItsPartWithADisabledUserOfO1_403_never409() {
            CommonData data = data();
            Part part = part(data.o2().company(), 0);
            disable(data.o1().technician());
            String before = row("part", part.getId());

            Response response = send(patch("/parts/" + part.getId()), partBody(part.getName(),
                    data.o1().technician()), data.o2().admin());

            assertEquals(403, response.status(), response.toString());
            assertFalse(response.body().contains("RETIRED"), response.toString());
            assertEquals(before, row("part", part.getId()));
        }
    }

    @Nested
    class PurchaseOrderLines {

        private Response replace(PurchaseOrder purchaseOrder, String body, User user) {
            return send(patch("/part-quantities/purchase-order/" + purchaseOrder.getId()), body, user);
        }

        private String lineBody(Part part, double quantity) {
            return "{\"part\":{\"id\":" + part.getId() + "},\"quantity\":" + quantity + "}";
        }

        @Test
        void ca038_pendingLineRemoved_200_oneLineLeft_recorded() {
            CommonData data = data();
            Company company = data.o1().company();
            Part kept = part(company, 0);
            Part removedPart = part(company, 0);
            PurchaseOrder po3 = purchaseOrder(company, ApprovalStatus.PENDING);
            line(kept, null, po3, 1);
            PartQuantity removed = line(removedPart, null, po3, 1);

            Response response = replace(po3, "[" + lineBody(kept, 1) + "]", data.o1().admin());

            assertEquals(200, response.status(), response.toString());
            assertEquals(0, rows("part_quantity", removed.getId()));
            assertEquals(1, lines(po3).size());
            Map<String, Object> record = onlyAuditRecord(data);
            assertAll(
                    () -> assertEquals("PART_QUANTITY", record.get("entity_type")),
                    () -> assertEquals(removed.getId(), record.get("entity_id")),
                    () -> assertEquals(removedPart.getName(), record.get("entity_name")),
                    () -> assertNull(record.get("entity_folio")));
        }

        @Test
        void pendingLinesAddedAndChanged_200() {
            CommonData data = data();
            Company company = data.o1().company();
            Part existing = part(company, 0);
            Part added = part(company, 0);
            PurchaseOrder purchaseOrder = purchaseOrder(company, ApprovalStatus.PENDING);
            line(existing, null, purchaseOrder, 1);

            Response response = replace(purchaseOrder, "[" + lineBody(existing, 5) + "," + lineBody(added, 2) + "]",
                    data.o1().admin());

            assertEquals(200, response.status(), response.toString());
            assertEquals(List.of(2.0, 5.0), jdbc.queryForList("SELECT quantity FROM part_quantity "
                    + "WHERE purchase_order_id = ? ORDER BY quantity", Double.class, purchaseOrder.getId()));
            assertEquals(0, auditRecords(data));
        }

        @Test
        void c2_closedSameLines_200_noRowChanged_noRecord() {
            CommonData data = data();
            Company company = data.o1().company();
            Part first = part(company, 0);
            Part second = part(company, 0);
            PurchaseOrder purchaseOrder = purchaseOrder(company, ApprovalStatus.APPROVED);
            line(first, null, purchaseOrder, 2);
            line(second, null, purchaseOrder, 3);
            List<String> before = lines(purchaseOrder);

            Response response = replace(purchaseOrder, "[" + lineBody(second, 3) + "," + lineBody(first, 2) + "]",
                    data.o1().admin());

            assertEquals(200, response.status(), response.toString());
            assertEquals(before, lines(purchaseOrder));
            assertEquals(0, auditRecords(data));
        }

        @Test
        void c2_closedAnyDifference_409PurchaseOrderClosed_unchanged() {
            CommonData data = data();
            Company company = data.o1().company();
            Part first = part(company, 0);
            Part other = part(company, 0);
            PurchaseOrder purchaseOrder = purchaseOrder(company, ApprovalStatus.REJECTED);
            line(first, null, purchaseOrder, 2);
            List<String> before = lines(purchaseOrder);

            Response added = replace(purchaseOrder, "[" + lineBody(first, 2) + "," + lineBody(other, 1) + "]",
                    data.o1().admin());
            Response removed = replace(purchaseOrder, "[]", data.o1().admin());
            Response changed = replace(purchaseOrder, "[" + lineBody(first, 5) + "]", data.o1().admin());

            assertConflict(added, "PURCHASE_ORDER_CLOSED");
            assertConflict(removed, "PURCHASE_ORDER_CLOSED");
            assertConflict(changed, "PURCHASE_ORDER_CLOSED");
            assertEquals(before, lines(purchaseOrder));
            assertEquals(0, auditRecords(data));
        }

        @Test
        void closedLineCreatedChangedOrDeletedAlone_409PurchaseOrderClosed_unchanged() {
            CommonData data = data();
            Company company = data.o1().company();
            Part part = part(company, 0);
            PurchaseOrder purchaseOrder = purchaseOrder(company, ApprovalStatus.APPROVED);
            PartQuantity line = line(part, null, purchaseOrder, 2);
            List<String> before = lines(purchaseOrder);

            Response created = send(post("/part-quantities"), "{\"part\":{\"id\":" + part(company, 0).getId()
                    + "},\"purchaseOrder\":{\"id\":" + purchaseOrder.getId() + "},\"quantity\":1}",
                    data.o1().admin());
            Response changed = send(patch("/part-quantities/" + line.getId()), "{\"quantity\":7}",
                    data.o1().admin());
            Response deleted = call(delete("/part-quantities/" + line.getId()), data.o1().admin());

            assertConflict(created, "PURCHASE_ORDER_CLOSED");
            assertConflict(changed, "PURCHASE_ORDER_CLOSED");
            assertConflict(deleted, "PURCHASE_ORDER_CLOSED");
            assertEquals(before, lines(purchaseOrder));
            assertEquals(0, auditRecords(data));
        }

        @Test
        void pendingLineDeletedAlone_200_recorded() {
            CommonData data = data();
            Company company = data.o1().company();
            Part part = part(company, 0);
            PurchaseOrder purchaseOrder = purchaseOrder(company, ApprovalStatus.PENDING);
            PartQuantity line = line(part, null, purchaseOrder, 2);

            Response response = call(delete("/part-quantities/" + line.getId()), data.o1().admin());

            assertEquals(200, response.status(), response.toString());
            assertEquals(0, rows("part_quantity", line.getId()));
            assertEquals("PART_QUANTITY", onlyAuditRecord(data).get("entity_type"));
        }

        @Test
        void otherOrganization_closedLines_403_never409() {
            CommonData data = data();
            Company company = data.o1().company();
            Part part = part(company, 0);
            PurchaseOrder purchaseOrder = purchaseOrder(company, ApprovalStatus.APPROVED);
            PartQuantity line = line(part, null, purchaseOrder, 2);
            Part foreignPart = part(data.o2().company(), 0);
            List<String> before = lines(purchaseOrder);
            User o2 = data.o2().admin();

            List<Response> responses = List.of(
                    replace(purchaseOrder, "[]", o2),
                    replace(purchaseOrder, "[" + lineBody(foreignPart, 1) + "]", o2),
                    send(post("/part-quantities"), "{\"part\":{\"id\":" + foreignPart.getId()
                            + "},\"purchaseOrder\":{\"id\":" + purchaseOrder.getId() + "},\"quantity\":1}", o2),
                    send(patch("/part-quantities/" + line.getId()), "{\"quantity\":7}", o2),
                    call(delete("/part-quantities/" + line.getId()), o2));

            responses.forEach(response -> {
                assertEquals(403, response.status(), response.toString());
                assertFalse(response.body().contains("CLOSED"), response.toString());
            });
            assertEquals(before, lines(purchaseOrder));
        }

        @Test
        void ownForeignPartOnAClosedPurchaseOrder_403_never409() {
            // the body cites a part of another organization: 403 before the state of the purchase order
            CommonData data = data();
            PurchaseOrder purchaseOrder = purchaseOrder(data.o1().company(), ApprovalStatus.APPROVED);
            Part foreignPart = part(data.o2().company(), 0);

            Response response = replace(purchaseOrder, "[" + lineBody(foreignPart, 1) + "]", data.o1().admin());

            assertEquals(403, response.status(), response.toString());
            assertFalse(response.body().contains("CLOSED"), response.toString());
        }
    }
}
