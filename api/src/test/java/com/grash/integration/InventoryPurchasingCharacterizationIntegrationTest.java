package com.grash.integration;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grash.integration.support.FaultInjector;
import com.grash.integration.support.FaultInjector.Operation;
import com.grash.integration.support.TestApi;
import com.grash.integration.support.TestApi.Response;
import com.grash.integration.support.TestOrganizations;
import com.grash.integration.support.TestOrganizations.CommonData;
import com.grash.integration.support.TestRecords;
import com.grash.model.Asset;
import com.grash.model.Company;
import com.grash.model.MultiParts;
import com.grash.model.Part;
import com.grash.model.PartCategory;
import com.grash.model.PartQuantity;
import com.grash.model.PartTransaction;
import com.grash.model.PreventiveMaintenance;
import com.grash.model.PurchaseOrder;
import com.grash.model.PurchaseOrderCategory;
import com.grash.model.User;
import com.grash.model.Vendor;
import com.grash.model.WorkOrder;
import com.grash.model.Workflow;
import com.grash.model.WorkflowAction;
import com.grash.model.WorkflowCondition;
import com.grash.model.enums.ApprovalStatus;
import com.grash.model.enums.Status;
import com.grash.model.enums.webhook.WebhookEvent;
import com.grash.model.enums.workflow.PartCondition;
import com.grash.model.enums.workflow.PurchaseOrderAction;
import com.grash.model.enums.workflow.PurchaseOrderCondition;
import com.grash.model.enums.workflow.WFMainCondition;
import com.grash.repository.MultiPartsRepository;
import com.grash.repository.PartCategoryRepository;
import com.grash.repository.PartQuantityRepository;
import com.grash.repository.PartRepository;
import com.grash.repository.PartTransactionRepository;
import com.grash.repository.PreventiveMaintenanceRepository;
import com.grash.repository.PurchaseOrderCategoryRepository;
import com.grash.repository.PurchaseOrderRepository;
import com.grash.repository.VendorRepository;
import com.grash.repository.WorkflowRepository;
import com.grash.service.WebhookDispatchService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * P-16 characterization: fixes how parts, part categories, purchase order categories, vendors, sets of parts and
 * purchase orders were deleted before any change, how a purchase order and its lines changed in each state, when the
 * webhook of a part deletion was sent, how a disabled user was accepted on a part, how the demo purge removed them,
 * and the foreign keys that point to them. The 403 and 404 answers must keep passing after P-16; the tests that show
 * a cascade, a reference set to null, a closed purchase order deleted, answered or changed, the 406 of an approved
 * purchase order, the webhook sent before the deletion, a deletion without audit record or a disabled user accepted
 * are updated to the contract of P-16, as step 8 of the order allows.
 */
@AutoConfigureMockMvc
class InventoryPurchasingCharacterizationIntegrationTest extends AbstractIntegrationTest {

    private static final String DELETED_BODY = "{\"success\":true,\"message\":\"Deleted successfully\"}";

    private final TestOrganizations organizations;
    private final TestRecords records;
    private final TestApi api;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;
    private final PartRepository partRepository;
    private final PartCategoryRepository partCategoryRepository;
    private final PartQuantityRepository partQuantityRepository;
    private final PartTransactionRepository partTransactionRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderCategoryRepository purchaseOrderCategoryRepository;
    private final VendorRepository vendorRepository;
    private final MultiPartsRepository multiPartsRepository;
    private final WorkflowRepository workflowRepository;
    private final PreventiveMaintenanceRepository preventiveMaintenanceRepository;
    private final FaultInjector faults;
    private final List<CommonData> created = new ArrayList<>();

    @Autowired
    InventoryPurchasingCharacterizationIntegrationTest(TestOrganizations organizations, TestRecords records,
                                                       MockMvc mockMvc, ObjectMapper objectMapper, JdbcTemplate jdbc,
                                                       PartRepository partRepository,
                                                       PartCategoryRepository partCategoryRepository,
                                                       PartQuantityRepository partQuantityRepository,
                                                       PartTransactionRepository partTransactionRepository,
                                                       PurchaseOrderRepository purchaseOrderRepository,
                                                       PurchaseOrderCategoryRepository purchaseOrderCategoryRepository,
                                                       VendorRepository vendorRepository,
                                                       MultiPartsRepository multiPartsRepository,
                                                       WorkflowRepository workflowRepository,
                                                       PreventiveMaintenanceRepository preventiveMaintenanceRepository) {
        this.organizations = organizations;
        this.records = records;
        this.api = new TestApi(mockMvc, objectMapper);
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
        this.partRepository = partRepository;
        this.partCategoryRepository = partCategoryRepository;
        this.partQuantityRepository = partQuantityRepository;
        this.partTransactionRepository = partTransactionRepository;
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.purchaseOrderCategoryRepository = purchaseOrderCategoryRepository;
        this.vendorRepository = vendorRepository;
        this.multiPartsRepository = multiPartsRepository;
        this.workflowRepository = workflowRepository;
        this.preventiveMaintenanceRepository = preventiveMaintenanceRepository;
        this.faults = new FaultInjector(jdbc);
    }

    @AfterEach
    void cleanUp() {
        faults.removeAll();
        created.forEach(organizations::delete);
        created.clear();
    }

    private CommonData data() {
        CommonData data = organizations.create();
        created.add(data);
        return data;
    }

    // --- records ---

    private Part part(Company company, double quantity) {
        Part part = records.part(company, null);
        jdbc.update("UPDATE part SET quantity = ? WHERE id = ?", quantity, part.getId());
        return part;
    }

    private PartCategory partCategory(Company company) {
        PartCategory category = new PartCategory();
        category.setName(TestRecords.unique("P-16 part category"));
        category.setCompanySettings(company.getCompanySettings());
        return partCategoryRepository.saveAndFlush(category);
    }

    private PurchaseOrderCategory purchaseOrderCategory(Company company) {
        PurchaseOrderCategory category = new PurchaseOrderCategory();
        category.setName(TestRecords.unique("P-16 PO category"));
        category.setCompanySettings(company.getCompanySettings());
        return purchaseOrderCategoryRepository.saveAndFlush(category);
    }

    private Vendor vendor(Company company) {
        Vendor vendor = new Vendor();
        vendor.setName(TestRecords.unique("P-16 vendor"));
        vendor.setCompanyName(vendor.getName());
        vendor.setCompany(company);
        return vendorRepository.saveAndFlush(vendor);
    }

    private PurchaseOrder purchaseOrder(Company company, ApprovalStatus status) {
        PurchaseOrder purchaseOrder = new PurchaseOrder();
        purchaseOrder.setName(TestRecords.unique("P-16 PO"));
        purchaseOrder.setStatus(status);
        purchaseOrder.setCompany(company);
        return purchaseOrderRepository.saveAndFlush(purchaseOrder);
    }

    private PartQuantity line(Part part, WorkOrder workOrder, PurchaseOrder purchaseOrder, double quantity) {
        PartQuantity line = new PartQuantity(part, workOrder, purchaseOrder, quantity);
        line.setCompany(part.getCompany());
        return partQuantityRepository.saveAndFlush(line);
    }

    private PartTransaction movement(Part part, WorkOrder workOrder, double quantity) {
        PartTransaction movement = new PartTransaction(part, workOrder, quantity);
        movement.setCompany(part.getCompany());
        return partTransactionRepository.saveAndFlush(movement);
    }

    private MultiParts set(Company company, Part... parts) {
        MultiParts set = new MultiParts();
        set.setName(TestRecords.unique("P-16 set"));
        set.setCompany(company);
        set.setParts(new ArrayList<>(List.of(parts)));
        return multiPartsRepository.saveAndFlush(set);
    }

    /**
     * A workflow on purchase orders: its action is {@code action} (with {@code actionVendor} for ASSIGN_VENDOR), its
     * condition, when {@code condition} is not {@code null}, cites {@code conditionVendor} or {@code conditionCategory}.
     */
    private Workflow purchaseOrderWorkflow(Company company, WFMainCondition main, PurchaseOrderAction action,
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

    private Workflow partWorkflow(Company company, Part part) {
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

    // --- queries ---

    private long count(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, Long.class, arguments);
    }

    private long rows(String table, Long id) {
        return count("SELECT count(*) FROM " + table + " WHERE id = ?", id);
    }

    private Long reference(String table, String column, Long id) {
        return jdbc.queryForObject("SELECT " + column + " FROM " + table + " WHERE id = ?", Long.class, id);
    }

    private double stock(Part part) {
        return jdbc.queryForObject("SELECT quantity FROM part WHERE id = ?", Double.class, part.getId());
    }

    private String status(Long purchaseOrder) {
        return ApprovalStatus.values()[jdbc.queryForObject("SELECT status FROM purchase_order WHERE id = ?",
                Integer.class, purchaseOrder)].name();
    }

    private long auditRecords(CommonData data) {
        return count("SELECT count(*) FROM audit_log WHERE company_id = ?", data.o1().company().getId());
    }

    private void disable(User user) {
        jdbc.update("UPDATE own_user SET enabled = false WHERE id = ?", user.getId());
    }

    private Response call(MockHttpServletRequestBuilder request, User user) {
        return api.call(request, api.login(user));
    }

    private Response send(MockHttpServletRequestBuilder request, String body, User user) {
        return call(request.contentType(MediaType.APPLICATION_JSON).content(body), user);
    }

    private JsonNode json(Response response) {
        try {
            return objectMapper.readTree(response.body());
        } catch (Exception e) {
            throw new AssertionError("not JSON: " + response, e);
        }
    }

    // =====================================================================================================

    @Nested
    class PartCategoryDeletion {

        @Test
        void usedByAPartWithAMovement_409_partLinesAndMovementsKept() {
            // before P-16 (C-03): 200, the category took its parts, their lines and their movements with it
            CommonData data = data();
            Company company = data.o1().company();
            PartCategory category = partCategory(company);
            Part part = part(company, 5);
            jdbc.update("UPDATE part SET category_id = ? WHERE id = ?", category.getId(), part.getId());
            WorkOrder workOrder = records.workOrder(company, null, Status.OPEN, false);
            PartQuantity line = line(part, workOrder, null, 1);
            PartTransaction movement = movement(part, workOrder, 1);

            Response response = call(delete("/part-categories/" + category.getId()), data.o1().admin());

            assertAll(
                    () -> assertEquals(409, response.status(), response.toString()),
                    () -> assertTrue(response.body().contains("PART_CATEGORY_IN_USE"), response.toString()),
                    () -> assertEquals(1, rows("part_category", category.getId())),
                    () -> assertEquals(1, rows("part", part.getId()), "the part is kept"),
                    () -> assertEquals(1, rows("part_quantity", line.getId())),
                    () -> assertEquals(1, rows("part_transaction", movement.getId())),
                    () -> assertEquals(1, rows("work_order", workOrder.getId())),
                    () -> assertEquals(0, auditRecords(data), "no audit record"));
        }

        @Test
        void unused_200_recorded() {
            CommonData data = data();
            PartCategory category = partCategory(data.o1().company());

            Response response = call(delete("/part-categories/" + category.getId()), data.o1().admin());

            assertEquals(200, response.status(), response.toString());
            assertEquals(0, rows("part_category", category.getId()));
            assertEquals(1, auditRecords(data), "before P-16: no audit record");
        }

        @Test
        void technician_403_kept() {
            CommonData data = data();
            PartCategory category = partCategory(data.o1().company());

            Response response = call(delete("/part-categories/" + category.getId()), data.o1().technician());

            assertEquals(403, response.status(), response.toString());
            assertEquals(1, rows("part_category", category.getId()));
        }

        @Test
        void otherOrganization_403_kept() {
            CommonData data = data();
            PartCategory category = partCategory(data.o1().company());

            Response response = call(delete("/part-categories/" + category.getId()), data.o2().admin());

            assertEquals(403, response.status(), response.toString());
            assertEquals(1, rows("part_category", category.getId()));
        }

        @Test
        void unknown_404() {
            CommonData data = data();

            Response response = call(delete("/part-categories/" + Long.MAX_VALUE), data.o1().admin());

            assertEquals(404, response.status(), response.toString());
        }
    }

    @Nested
    class PartDeletion {

        @Test
        void withAWorkOrderLineAndAMovement_409_bothKept_noWebhook() {
            // CA-PPB-009; before P-16: 200, both deleted in cascade, PART_DELETE sent
            CommonData data = data();
            Company company = data.o1().company();
            records.endpoint(company, WebhookEvent.PART_DELETE);
            Part part = part(company, 5);
            WorkOrder workOrder = records.workOrder(company, null, Status.OPEN, false);
            PartQuantity line = line(part, workOrder, null, 1);
            PartTransaction movement = movement(part, workOrder, 1);

            Response response = call(delete("/parts/" + part.getId()), data.o1().admin());

            assertAll(
                    () -> assertEquals(409, response.status(), response.toString()),
                    () -> assertTrue(response.body().contains("PART_IN_USE"), response.toString()),
                    () -> assertEquals(1, rows("part", part.getId())),
                    () -> assertEquals(1, rows("part_quantity", line.getId())),
                    () -> assertEquals(1, rows("part_transaction", movement.getId())),
                    () -> assertEquals(0, auditRecords(data)));
            assertTrue(webhookDouble.calls().isEmpty(), "a rejection sends no PART_DELETE");
        }

        @Test
        void withAPurchaseOrderLine_409_lineKept() {
            // before P-16: 200, the line deleted in cascade
            CommonData data = data();
            Company company = data.o1().company();
            Part part = part(company, 0);
            PurchaseOrder purchaseOrder = purchaseOrder(company, ApprovalStatus.PENDING);
            PartQuantity line = line(part, null, purchaseOrder, 2);

            Response response = call(delete("/parts/" + part.getId()), data.o1().admin());

            assertEquals(409, response.status(), response.toString());
            assertEquals(1, rows("part_quantity", line.getId()));
            assertEquals(1, rows("purchase_order", purchaseOrder.getId()));
        }

        @Test
        void citedByAWorkflowCondition_409_conditionKept() {
            // before P-16: 200, the condition deleted in cascade
            CommonData data = data();
            Company company = data.o1().company();
            Part part = part(company, 0);
            Workflow workflow = partWorkflow(company, part);
            Long condition = workflow.getSecondaryConditions().iterator().next().getId();

            Response response = call(delete("/parts/" + part.getId()), data.o1().admin());

            assertEquals(409, response.status(), response.toString());
            assertEquals(1, rows("workflow_condition", condition));
            assertEquals(1, rows("workflow", workflow.getId()));
        }

        @Test
        void assignedToADisabledUser_409_associationKept() {
            // before P-16: 200, the association deleted
            CommonData data = data();
            Company company = data.o1().company();
            Part part = part(company, 0);
            User user = data.o1().technician();
            jdbc.update("INSERT INTO t_part_user_associations (id_part, id_user) VALUES (?, ?)", part.getId(),
                    user.getId());
            disable(user);

            Response response = call(delete("/parts/" + part.getId()), data.o1().admin());

            assertEquals(409, response.status(), response.toString());
            assertEquals(1, count("SELECT count(*) FROM t_part_user_associations WHERE id_part = ?", part.getId()));
        }

        @Test
        void onAnArchivedAsset_409_associationKept() {
            // before P-16: 200, the association deleted
            CommonData data = data();
            Company company = data.o1().company();
            Part part = part(company, 0);
            Asset asset = records.asset(company);
            jdbc.update("INSERT INTO t_asset_part_associations (id_asset, id_part) VALUES (?, ?)", asset.getId(),
                    part.getId());
            jdbc.update("UPDATE asset SET archived = true WHERE id = ?", asset.getId());

            Response response = call(delete("/parts/" + part.getId()), data.o1().admin());

            assertEquals(409, response.status(), response.toString());
            assertEquals(1, count("SELECT count(*) FROM t_asset_part_associations WHERE id_part = ?",
                    part.getId()));
        }

        @Test
        void onADisabledPreventiveMaintenance_200_associationDeleted() {
            CommonData data = data();
            Company company = data.o1().company();
            Part part = part(company, 0);
            PreventiveMaintenance preventiveMaintenance = new PreventiveMaintenance();
            preventiveMaintenance.setName(TestRecords.unique("P-16 PM"));
            preventiveMaintenance.setTitle(preventiveMaintenance.getName());
            preventiveMaintenance.setCompany(company);
            Long pm = preventiveMaintenanceRepository.saveAndFlush(preventiveMaintenance).getId();
            jdbc.update("INSERT INTO part_preventive_maintenances (part_id, preventive_maintenances_id) "
                    + "VALUES (?, ?)", part.getId(), pm);

            Response response = call(delete("/parts/" + part.getId()), data.o1().admin());

            assertEquals(200, response.status(), response.toString());
            assertEquals(0, count("SELECT count(*) FROM part_preventive_maintenances WHERE part_id = ?",
                    part.getId()));
        }

        @Test
        void deletionRolledBack_noWebhook() {
            // before P-16: the webhook left before the deletion, so a deletion that failed still announced it
            CommonData data = data();
            Company company = data.o1().company();
            records.endpoint(company, WebhookEvent.PART_DELETE);
            Part part = part(company, 0);

            Response response;
            try (FaultInjector.Fault fault = faults.failOn("audit_log", Operation.INSERT)) {
                response = call(delete("/parts/" + part.getId()), data.o1().admin());
                assertEquals(1, fault.hits());
            }

            assertEquals(500, response.status(), response.toString());
            assertEquals(1, rows("part", part.getId()), "the part is kept");
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            assertTrue(webhookDouble.calls().isEmpty(), "PART_DELETE is not sent when nothing was deleted");
        }

        @Test
        void webhookFails_200_partDeleted_failureLoggedWithTheAddress() {
            CommonData data = data();
            Company company = data.o1().company();
            String url = records.endpoint(company, WebhookEvent.PART_DELETE).getUrl();
            Part part = part(company, 0);
            webhookDouble.failOn(WebhookEvent.PART_DELETE.name(), new IllegalStateException("P-16 webhook down"));
            Logger logger = (Logger) LoggerFactory.getLogger(WebhookDispatchService.class);
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            logger.addAppender(appender);
            Response response;
            try {
                response = call(delete("/parts/" + part.getId()), data.o1().admin());
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
            assertEquals(0, rows("part", part.getId()));
            assertEquals(1, failures.size(), appender.list.toString());
            assertTrue(failures.get(0).getFormattedMessage().contains(url), "today the log carries the address, "
                    + "with its query: " + failures.get(0).getFormattedMessage());
            assertNotNull(failures.get(0).getThrowableProxy(), "today the log carries the trace");
        }

        @Test
        void otherOrganization_403_kept_noWebhook() {
            CommonData data = data();
            Company company = data.o1().company();
            records.endpoint(company, WebhookEvent.PART_DELETE);
            Part part = part(company, 0);

            Response response = call(delete("/parts/" + part.getId()), data.o2().admin());

            assertEquals(403, response.status(), response.toString());
            assertEquals(1, rows("part", part.getId()));
            assertTrue(webhookDouble.calls().isEmpty());
        }
    }

    @Nested
    class PartUsers {

        @Test
        void disabledUserAssignedOnCreate_200() {
            CommonData data = data();
            User user = data.o1().technician();
            disable(user);

            Response response = send(post("/parts"), "{\"name\":\"" + TestRecords.unique("P-16 part")
                    + "\",\"assignedTo\":[{\"id\":" + user.getId() + "}]}", data.o1().admin());

            assertEquals(200, response.status(), response.toString());
            assertEquals(1, count("SELECT count(*) FROM t_part_user_associations WHERE id_part = ? AND id_user = ?",
                    json(response).get("id").asLong(), user.getId()));
        }

        @Test
        void disabledUserAssignedOnEdit_200() {
            CommonData data = data();
            Part part = part(data.o1().company(), 0);
            User user = data.o1().technician();
            disable(user);

            Response response = send(patch("/parts/" + part.getId()), "{\"name\":\"" + part.getName()
                    + "\",\"assignedTo\":[{\"id\":" + user.getId() + "}]}", data.o1().admin());

            assertEquals(200, response.status(), response.toString());
            assertEquals(1, count("SELECT count(*) FROM t_part_user_associations WHERE id_part = ? AND id_user = ?",
                    part.getId(), user.getId()));
        }
    }

    @Nested
    class PurchaseOrderCategoryDeletion {

        @Test
        void usedByAPurchaseOrder_409_referenceKept() {
            // before P-16: 200, the reference set to null
            CommonData data = data();
            Company company = data.o1().company();
            PurchaseOrderCategory category = purchaseOrderCategory(company);
            PurchaseOrder purchaseOrder = purchaseOrder(company, ApprovalStatus.PENDING);
            jdbc.update("UPDATE purchase_order SET category_id = ? WHERE id = ?", category.getId(),
                    purchaseOrder.getId());

            Response response = call(delete("/purchase-order-categories/" + category.getId()), data.o1().admin());

            assertEquals(409, response.status(), response.toString());
            assertEquals(1, rows("purchase_order", purchaseOrder.getId()));
            assertEquals(category.getId(), reference("purchase_order", "category_id", purchaseOrder.getId()));
            assertEquals(0, auditRecords(data));
        }

        @Test
        void citedByAWorkflow_409_workflowKept() {
            // before P-16: 200, the action and the condition deleted, and the workflow with them
            CommonData data = data();
            Company company = data.o1().company();
            PurchaseOrderCategory category = purchaseOrderCategory(company);
            Workflow workflow = purchaseOrderWorkflow(company, WFMainCondition.PURCHASE_ORDER_CREATED,
                    PurchaseOrderAction.ASSIGN_CATEGORY, null, category, PurchaseOrderCondition.CATEGORY_IS, null,
                    category);

            Response response = call(delete("/purchase-order-categories/" + category.getId()), data.o1().admin());

            assertEquals(409, response.status(), response.toString());
            assertEquals(1, rows("workflow", workflow.getId()));
        }

        @Test
        void otherOrganization_403_kept() {
            CommonData data = data();
            PurchaseOrderCategory category = purchaseOrderCategory(data.o1().company());

            Response response = call(delete("/purchase-order-categories/" + category.getId()), data.o2().admin());

            assertEquals(403, response.status(), response.toString());
            assertEquals(1, rows("purchase_order_category", category.getId()));
        }
    }

    @Nested
    class VendorDeletion {

        @Test
        void citedByAPurchaseOrder_409_referenceKept() {
            // CA-PPB-108; before P-16: 200, the reference set to null
            CommonData data = data();
            Company company = data.o1().company();
            Vendor vendor = vendor(company);
            PurchaseOrder purchaseOrder = purchaseOrder(company, ApprovalStatus.APPROVED);
            jdbc.update("UPDATE purchase_order SET vendor_id = ? WHERE id = ?", vendor.getId(),
                    purchaseOrder.getId());

            Response response = call(delete("/vendors/" + vendor.getId()), data.o1().admin());

            assertEquals(409, response.status(), response.toString());
            assertEquals(vendor.getId(), reference("purchase_order", "vendor_id", purchaseOrder.getId()));
            assertEquals(0, auditRecords(data));
        }

        @Test
        void citedByAVendorWorkflow_409_workflowKept() {
            // before P-16: 200, the workflow deleted
            CommonData data = data();
            Company company = data.o1().company();
            Vendor vendor = vendor(company);
            Workflow workflow = purchaseOrderWorkflow(company, WFMainCondition.PURCHASE_ORDER_CREATED,
                    PurchaseOrderAction.ASSIGN_VENDOR, vendor, null, PurchaseOrderCondition.VENDOR_IS, vendor, null);

            Response response = call(delete("/vendors/" + vendor.getId()), data.o1().admin());

            assertEquals(409, response.status(), response.toString());
            assertEquals(1, rows("workflow", workflow.getId()));
        }

        @Test
        void onAnArchivedAsset_409_associationKept() {
            // before P-16: 200, the association deleted
            CommonData data = data();
            Company company = data.o1().company();
            Vendor vendor = vendor(company);
            Asset asset = records.asset(company);
            jdbc.update("INSERT INTO t_asset_vendor_associations (id_asset, id_vendor) VALUES (?, ?)",
                    asset.getId(), vendor.getId());
            jdbc.update("UPDATE asset SET archived = true WHERE id = ?", asset.getId());

            Response response = call(delete("/vendors/" + vendor.getId()), data.o1().admin());

            assertEquals(409, response.status(), response.toString());
            assertEquals(1, count("SELECT count(*) FROM t_asset_vendor_associations WHERE id_vendor = ?",
                    vendor.getId()));
        }

        @Test
        void otherOrganization_403_kept() {
            CommonData data = data();
            Vendor vendor = vendor(data.o1().company());

            Response response = call(delete("/vendors/" + vendor.getId()), data.o2().admin());

            assertEquals(403, response.status(), response.toString());
            assertEquals(1, rows("vendor", vendor.getId()));
        }
    }

    @Nested
    class SetDeletion {

        @Test
        void usedOnAWorkOrder_200_linesAndMovementsKept_recorded() {
            // CA-PPB-124: the set is expanded into plain lines; the work order never cites it
            CommonData data = data();
            Company company = data.o1().company();
            Part first = part(company, 5);
            Part second = part(company, 5);
            MultiParts set = set(company, first, second);
            WorkOrder workOrder = records.workOrder(company, null, Status.OPEN, false);
            String admin = api.login(data.o1().admin());
            Response added = api.call(patch("/part-quantities/work-order/" + workOrder.getId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("[" + first.getId() + "," + second.getId() + "]"), admin);
            assertEquals(200, added.status(), added.toString());

            Response response = api.call(delete("/multi-parts/" + set.getId()), admin);

            assertAll(
                    () -> assertEquals(200, response.status(), response.toString()),
                    () -> assertEquals(0, rows("multi_parts", set.getId())),
                    () -> assertEquals(2, count("SELECT count(*) FROM part_quantity WHERE work_order_id = ?",
                            workOrder.getId())),
                    () -> assertEquals(2, count("SELECT count(*) FROM part_transaction WHERE work_order_id = ?",
                            workOrder.getId())),
                    () -> assertEquals(2, count("SELECT count(*) FROM part WHERE id IN (?, ?)", first.getId(),
                            second.getId())),
                    () -> assertEquals(1, auditRecords(data), "before P-16: no audit record"));
        }

        @Test
        void otherOrganization_403_kept() {
            CommonData data = data();
            MultiParts set = set(data.o1().company());

            Response response = call(delete("/multi-parts/" + set.getId()), data.o2().admin());

            assertEquals(403, response.status(), response.toString());
            assertEquals(1, rows("multi_parts", set.getId()));
        }
    }

    @Nested
    class PurchaseOrderDeletion {

        @Test
        void approved_200_deletedWithItsLines_stockKept() {
            // CA-PPB-020 (PO1) before P-16
            CommonData data = data();
            Company company = data.o1().company();
            Part part = part(company, 0);
            PurchaseOrder purchaseOrder = purchaseOrder(company, ApprovalStatus.PENDING);
            PartQuantity line = line(part, null, purchaseOrder, 3);
            String admin = api.login(data.o1().admin());
            assertEquals(200, api.call(patch("/purchase-orders/" + purchaseOrder.getId() + "/respond")
                    .param("approved", "true"), admin).status());
            assertEquals(3, stock(part));

            Response response = api.call(delete("/purchase-orders/" + purchaseOrder.getId()), admin);

            assertEquals(200, response.status(), response.toString());
            assertEquals(0, rows("purchase_order", purchaseOrder.getId()));
            assertEquals(0, rows("part_quantity", line.getId()));
            assertEquals(3, stock(part), "the stock it brought stays");
            assertEquals(0, auditRecords(data));
        }

        @Test
        void rejected_200_deleted() {
            CommonData data = data();
            PurchaseOrder purchaseOrder = purchaseOrder(data.o1().company(), ApprovalStatus.REJECTED);

            Response response = call(delete("/purchase-orders/" + purchaseOrder.getId()), data.o1().admin());

            assertEquals(200, response.status(), response.toString());
            assertEquals(0, rows("purchase_order", purchaseOrder.getId()));
        }

        @Test
        void pending_200_deletedWithItsLines_noAuditRecord() {
            CommonData data = data();
            Company company = data.o1().company();
            PurchaseOrder purchaseOrder = purchaseOrder(company, ApprovalStatus.PENDING);
            PartQuantity first = line(part(company, 0), null, purchaseOrder, 1);
            PartQuantity second = line(part(company, 0), null, purchaseOrder, 1);

            Response response = call(delete("/purchase-orders/" + purchaseOrder.getId()), data.o1().admin());

            assertEquals(200, response.status(), response.toString());
            assertEquals(0, rows("part_quantity", first.getId()) + rows("part_quantity", second.getId()));
            assertEquals(0, auditRecords(data));
        }

        @Test
        void technician_403_kept() {
            CommonData data = data();
            PurchaseOrder purchaseOrder = purchaseOrder(data.o1().company(), ApprovalStatus.APPROVED);

            Response response = call(delete("/purchase-orders/" + purchaseOrder.getId()), data.o1().technician());

            assertEquals(403, response.status(), response.toString());
            assertEquals(1, rows("purchase_order", purchaseOrder.getId()));
        }

        @Test
        void otherOrganization_403_kept() {
            CommonData data = data();
            PurchaseOrder purchaseOrder = purchaseOrder(data.o1().company(), ApprovalStatus.APPROVED);

            Response response = call(delete("/purchase-orders/" + purchaseOrder.getId()), data.o2().admin());

            assertEquals(403, response.status(), response.toString());
            assertEquals(1, rows("purchase_order", purchaseOrder.getId()));
        }

        @Test
        void unknown_404() {
            CommonData data = data();

            Response response = call(delete("/purchase-orders/" + Long.MAX_VALUE), data.o1().admin());

            assertEquals(404, response.status(), response.toString());
        }
    }

    @Nested
    class PurchaseOrderResponse {

        private Response respond(PurchaseOrder purchaseOrder, boolean approved, User user) {
            return call(patch("/purchase-orders/" + purchaseOrder.getId() + "/respond")
                    .param("approved", String.valueOf(approved)), user);
        }

        @Test
        void pendingApproved_200_stockAdded_noMovement() {
            CommonData data = data();
            Company company = data.o1().company();
            Part part = part(company, 1);
            PurchaseOrder purchaseOrder = purchaseOrder(company, ApprovalStatus.PENDING);
            line(part, null, purchaseOrder, 4);

            Response response = respond(purchaseOrder, true, data.o1().admin());

            assertEquals(200, response.status(), response.toString());
            assertEquals("APPROVED", status(purchaseOrder.getId()));
            assertEquals(5, stock(part));
            assertEquals(0, count("SELECT count(*) FROM part_transaction WHERE part_id = ?", part.getId()),
                    "no movement (P-16b)");
        }

        @Test
        void approvedAnsweredAgain_406_unchanged() {
            // CA-PPB-085 before P-16: 406 for both answers
            CommonData data = data();
            Company company = data.o1().company();
            Part part = part(company, 0);
            PurchaseOrder purchaseOrder = purchaseOrder(company, ApprovalStatus.APPROVED);
            line(part, null, purchaseOrder, 4);

            Response rejected = respond(purchaseOrder, false, data.o1().admin());
            Response approved = respond(purchaseOrder, true, data.o1().admin());

            assertEquals(406, rejected.status(), rejected.toString());
            assertEquals(406, approved.status(), approved.toString());
            assertEquals("APPROVED", status(purchaseOrder.getId()));
            assertEquals(0, stock(part));
        }

        @Test
        void rejectedApprovedLater_200_stockAdded() {
            // D-1: a rejected purchase order can still be approved, and that adds stock
            CommonData data = data();
            Company company = data.o1().company();
            Part part = part(company, 0);
            PurchaseOrder purchaseOrder = purchaseOrder(company, ApprovalStatus.REJECTED);
            line(part, null, purchaseOrder, 4);

            Response response = respond(purchaseOrder, true, data.o1().admin());

            assertEquals(200, response.status(), response.toString());
            assertEquals("APPROVED", status(purchaseOrder.getId()));
            assertEquals(4, stock(part));
        }

        @Test
        void rejectedRejectedAgain_200() {
            CommonData data = data();
            PurchaseOrder purchaseOrder = purchaseOrder(data.o1().company(), ApprovalStatus.REJECTED);

            Response response = respond(purchaseOrder, false, data.o1().admin());

            assertEquals(200, response.status(), response.toString());
            assertEquals("REJECTED", status(purchaseOrder.getId()));
        }

        @Test
        void technician_403() {
            CommonData data = data();
            PurchaseOrder purchaseOrder = purchaseOrder(data.o1().company(), ApprovalStatus.PENDING);

            Response response = respond(purchaseOrder, true, data.o1().technician());

            assertEquals(403, response.status(), response.toString());
            assertEquals("PENDING", status(purchaseOrder.getId()));
        }

        @Test
        void otherOrganization_403() {
            CommonData data = data();
            PurchaseOrder purchaseOrder = purchaseOrder(data.o1().company(), ApprovalStatus.PENDING);

            Response response = respond(purchaseOrder, true, data.o2().admin());

            assertEquals(403, response.status(), response.toString());
            assertEquals("PENDING", status(purchaseOrder.getId()));
        }
    }

    @Nested
    class PurchaseOrderStateRoutes {

        @Test
        void createdWithStatusApproved_200_approvedWithoutStock() {
            CommonData data = data();

            Response response = send(post("/purchase-orders"), "{\"name\":\"" + TestRecords.unique("P-16 PO")
                    + "\",\"status\":\"APPROVED\"}", data.o1().admin());

            assertEquals(200, response.status(), response.toString());
            assertEquals("APPROVED", status(json(response).get("id").asLong()), "the body sets the state");
        }

        @Test
        void closedEdited_200_generalDataChanged_stateAndStockKept() {
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
            assertEquals("APPROVED", status(purchaseOrder.getId()), "PATCH does not change the state");
            assertEquals(0, stock(part));
        }

        @Test
        void rejectedEdited_workflowApproves_approvedWithoutStock() {
            // the automation approves a rejected purchase order, without adding stock
            CommonData data = data();
            Company company = data.o1().company();
            Part part = part(company, 0);
            PurchaseOrder purchaseOrder = purchaseOrder(company, ApprovalStatus.REJECTED);
            line(part, null, purchaseOrder, 4);
            purchaseOrderWorkflow(company, WFMainCondition.PURCHASE_ORDER_UPDATED, PurchaseOrderAction.APPROVE,
                    null, null, null, null, null);

            Response response = send(patch("/purchase-orders/" + purchaseOrder.getId()), "{\"name\":\"P-16 x\"}",
                    data.o1().admin());

            assertEquals(200, response.status(), response.toString());
            assertEquals("APPROVED", status(purchaseOrder.getId()));
            assertEquals(0, stock(part));
        }

        @Test
        void approvedEdited_workflowRejects_rejected() {
            // the automation rejects an approved purchase order; the stock it brought stays
            CommonData data = data();
            Company company = data.o1().company();
            PurchaseOrder purchaseOrder = purchaseOrder(company, ApprovalStatus.APPROVED);
            purchaseOrderWorkflow(company, WFMainCondition.PURCHASE_ORDER_UPDATED, PurchaseOrderAction.REJECT,
                    null, null, null, null, null);

            Response response = send(patch("/purchase-orders/" + purchaseOrder.getId()), "{\"name\":\"P-16 x\"}",
                    data.o1().admin());

            assertEquals(200, response.status(), response.toString());
            assertEquals("REJECTED", status(purchaseOrder.getId()));
        }

        @Test
        void createdAndApprovedByAWorkflow_approvedWithoutStock() {
            CommonData data = data();
            purchaseOrderWorkflow(data.o1().company(), WFMainCondition.PURCHASE_ORDER_CREATED,
                    PurchaseOrderAction.APPROVE, null, null, null, null, null);

            Response response = send(post("/purchase-orders"), "{\"name\":\"" + TestRecords.unique("P-16 PO")
                    + "\"}", data.o1().admin());

            assertEquals(200, response.status(), response.toString());
            assertEquals("APPROVED", status(json(response).get("id").asLong()));
        }
    }

    @Nested
    class PurchaseOrderLines {

        private PurchaseOrder approvedWithALine(Company company, Part part) {
            PurchaseOrder purchaseOrder = purchaseOrder(company, ApprovalStatus.APPROVED);
            line(part, null, purchaseOrder, 2);
            return purchaseOrder;
        }

        @Test
        void pendingLineRemoved_200_oneLineLeft() {
            // CA-PPB-038: passes today
            CommonData data = data();
            Company company = data.o1().company();
            Part kept = part(company, 0);
            PurchaseOrder purchaseOrder = purchaseOrder(company, ApprovalStatus.PENDING);
            line(kept, null, purchaseOrder, 1);
            PartQuantity removed = line(part(company, 0), null, purchaseOrder, 1);

            Response response = send(patch("/part-quantities/purchase-order/" + purchaseOrder.getId()),
                    "[{\"part\":{\"id\":" + kept.getId() + "},\"quantity\":1}]", data.o1().admin());

            assertEquals(200, response.status(), response.toString());
            assertEquals(0, rows("part_quantity", removed.getId()));
            assertEquals(1, count("SELECT count(*) FROM part_quantity WHERE purchase_order_id = ?",
                    purchaseOrder.getId()));
            assertEquals(0, auditRecords(data), "a line of a purchase order is removed without audit record");
        }

        @Test
        void closedLinesReplaced_200_added_changed_removed() {
            CommonData data = data();
            Company company = data.o1().company();
            Part existing = part(company, 0);
            Part added = part(company, 0);
            PurchaseOrder purchaseOrder = approvedWithALine(company, existing);

            Response changed = send(patch("/part-quantities/purchase-order/" + purchaseOrder.getId()),
                    "[{\"part\":{\"id\":" + existing.getId() + "},\"quantity\":9},{\"part\":{\"id\":"
                            + added.getId() + "},\"quantity\":1}]", data.o1().admin());
            Response removed = send(patch("/part-quantities/purchase-order/" + purchaseOrder.getId()), "[]",
                    data.o1().admin());

            assertEquals(200, changed.status(), changed.toString());
            assertEquals(200, removed.status(), removed.toString());
            assertEquals(0, count("SELECT count(*) FROM part_quantity WHERE purchase_order_id = ?",
                    purchaseOrder.getId()));
        }

        @Test
        void closedLineChangedAlone_200() {
            CommonData data = data();
            Company company = data.o1().company();
            Part part = part(company, 0);
            PurchaseOrder purchaseOrder = approvedWithALine(company, part);
            Long line = jdbc.queryForObject("SELECT id FROM part_quantity WHERE purchase_order_id = ?", Long.class,
                    purchaseOrder.getId());

            Response response = send(patch("/part-quantities/" + line), "{\"quantity\":7}", data.o1().admin());

            assertEquals(200, response.status(), response.toString());
            assertEquals(7, jdbc.queryForObject("SELECT quantity FROM part_quantity WHERE id = ?", Double.class,
                    line));
        }

        @Test
        void closedLineDeletedAlone_200() {
            CommonData data = data();
            Company company = data.o1().company();
            PurchaseOrder purchaseOrder = approvedWithALine(company, part(company, 0));
            Long line = jdbc.queryForObject("SELECT id FROM part_quantity WHERE purchase_order_id = ?", Long.class,
                    purchaseOrder.getId());

            Response response = call(delete("/part-quantities/" + line), data.o1().admin());

            assertEquals(200, response.status(), response.toString());
            assertEquals(0, rows("part_quantity", line));
        }

        @Test
        void closedLineCreated_200() {
            CommonData data = data();
            Company company = data.o1().company();
            Part part = part(company, 0);
            PurchaseOrder purchaseOrder = purchaseOrder(company, ApprovalStatus.REJECTED);

            Response response = send(post("/part-quantities"), "{\"part\":{\"id\":" + part.getId()
                    + "},\"purchaseOrder\":{\"id\":" + purchaseOrder.getId() + "},\"quantity\":1}", data.o1().admin());

            assertEquals(200, response.status(), response.toString());
            assertEquals(1, count("SELECT count(*) FROM part_quantity WHERE purchase_order_id = ?",
                    purchaseOrder.getId()));
        }

        @Test
        void otherOrganizationReplacesLines_403() {
            CommonData data = data();
            Company company = data.o1().company();
            PurchaseOrder purchaseOrder = purchaseOrder(company, ApprovalStatus.PENDING);
            line(part(company, 0), null, purchaseOrder, 1);

            Response response = send(patch("/part-quantities/purchase-order/" + purchaseOrder.getId()), "[]",
                    data.o2().admin());

            assertEquals(403, response.status(), response.toString());
            assertEquals(1, count("SELECT count(*) FROM part_quantity WHERE purchase_order_id = ?",
                    purchaseOrder.getId()));
        }

        @Test
        void otherOrganizationCreatesALineOnIt_403() {
            CommonData data = data();
            PurchaseOrder purchaseOrder = purchaseOrder(data.o1().company(), ApprovalStatus.PENDING);
            Part foreignPart = part(data.o2().company(), 0);

            Response response = send(post("/part-quantities"), "{\"part\":{\"id\":" + foreignPart.getId()
                    + "},\"purchaseOrder\":{\"id\":" + purchaseOrder.getId() + "},\"quantity\":1}", data.o2().admin());

            assertEquals(403, response.status(), response.toString());
            assertEquals(0, count("SELECT count(*) FROM part_quantity WHERE purchase_order_id = ?",
                    purchaseOrder.getId()));
        }
    }

    @Nested
    class DemoPurge {

        @Test
        void demoInventory_200_allDeleted() {
            CommonData data = data();
            Company company = data.o1().company();
            PartCategory category = partCategory(company);
            PurchaseOrderCategory purchaseOrderCategory = purchaseOrderCategory(company);
            Vendor vendor = vendor(company);
            Part part = part(company, 3);
            WorkOrder workOrder = records.workOrder(company, null, Status.OPEN, true);
            PartQuantity line = line(part, workOrder, null, 1);
            jdbc.update("UPDATE part SET is_demo = true, category_id = ? WHERE id = ?", category.getId(),
                    part.getId());
            jdbc.update("UPDATE part_category SET is_demo = true WHERE id = ?", category.getId());
            jdbc.update("UPDATE purchase_order_category SET is_demo = true WHERE id = ?",
                    purchaseOrderCategory.getId());
            jdbc.update("UPDATE vendor SET is_demo = true WHERE id = ?", vendor.getId());
            jdbc.update("UPDATE part_quantity SET is_demo = true WHERE id = ?", line.getId());

            Response response = call(delete("/demo/demo-data"), data.o1().admin());

            assertAll(
                    () -> assertEquals(200, response.status(), response.toString()),
                    () -> assertEquals(0, rows("part", part.getId())),
                    () -> assertEquals(0, rows("part_category", category.getId())),
                    () -> assertEquals(0, rows("purchase_order_category", purchaseOrderCategory.getId())),
                    () -> assertEquals(0, rows("vendor", vendor.getId())));
        }

        @Test
        void demoPartRestocked_409RecordInUse_nothingDeleted() {
            // before P-16: 200, the movement deleted with the part. D-P16-6: the movement is not demo, so the whole
            // purge is rejected (PPB-20 d); P-18 gives the code DEMO_DATA_IN_USE
            CommonData data = data();
            Company company = data.o1().company();
            Part part = part(company, 0);
            jdbc.update("UPDATE part SET is_demo = true WHERE id = ?", part.getId());
            Response restock = send(post("/parts/" + part.getId() + "/restock"),
                    "{\"quantity\":2,\"description\":\"P-16\"}", data.o1().admin());
            assertEquals(200, restock.status(), restock.toString());

            Response response = call(delete("/demo/demo-data"), data.o1().admin());

            assertEquals(409, response.status(), response.toString());
            assertTrue(response.body().contains("RECORD_IN_USE"), response.toString());
            assertEquals(1, rows("part", part.getId()));
            assertEquals(1, count("SELECT count(*) FROM part_transaction WHERE part_id = ?", part.getId()));
        }

        @Test
        void demoVendorCitedByAPurchaseOrder_409RecordInUse_nothingDeleted() {
            // before P-16: 200, the reference set to null. D-P16-6: the purchase order is not demo, so the whole purge
            // is rejected (PPB-20 d); P-18 gives the code DEMO_DATA_IN_USE
            CommonData data = data();
            Company company = data.o1().company();
            Vendor vendor = vendor(company);
            jdbc.update("UPDATE vendor SET is_demo = true WHERE id = ?", vendor.getId());
            PurchaseOrder purchaseOrder = purchaseOrder(company, ApprovalStatus.PENDING);
            jdbc.update("UPDATE purchase_order SET vendor_id = ? WHERE id = ?", vendor.getId(),
                    purchaseOrder.getId());

            Response response = call(delete("/demo/demo-data"), data.o1().admin());

            assertEquals(409, response.status(), response.toString());
            assertTrue(response.body().contains("RECORD_IN_USE"), response.toString());
            assertEquals(1, rows("vendor", vendor.getId()));
            assertEquals(vendor.getId(), reference("purchase_order", "vendor_id", purchaseOrder.getId()));
        }
    }

    @Nested
    class Schema {

        @Test
        void foreignKeysToInventoryAndPurchasing() {
            // child table.column -> parent, constraint, ON DELETE (a NO ACTION, c CASCADE, n SET NULL), indexed
            List<String> keys = jdbc.queryForList("""
                    SELECT c.conrelid::regclass::text || '.' || a.attname || ' -> ' || c.confrelid::regclass::text
                           || ' ' || c.conname || ' ' || c.confdeltype::text || ' '
                           || (EXISTS (SELECT 1 FROM pg_index i WHERE i.indrelid = c.conrelid
                               AND i.indkey[0] = c.conkey[1] AND i.indisvalid AND i.indpred IS NULL))::text
                    FROM pg_constraint c
                    JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = c.conkey[1]
                    WHERE c.contype = 'f' AND c.confrelid IN ('part'::regclass, 'part_category'::regclass,
                        'purchase_order'::regclass, 'purchase_order_category'::regclass, 'vendor'::regclass,
                        'multi_parts'::regclass, 'part_quantity'::regclass, 'part_transaction'::regclass)
                    ORDER BY 1""", String.class);
            assertEquals(List.of(
                    "custom_field_value.part_id -> part fk_custom_field_value_part c true",
                    "custom_field_value.vendor_id -> vendor fk_custom_field_value_vendor c true",
                    "part.category_id -> part_category fk_part_category a true",
                    "part_preventive_maintenances.part_id -> part fkcec0nx6ooubsuiwcwg23jod93 c false",
                    "part_quantity.part_id -> part fk21q7kq7mtnvshipi5oym9t2b3 a true",
                    "part_quantity.purchase_order_id -> purchase_order fkb8l63n0csjq7q33wwbkvsj61u c false",
                    "part_transaction.part_id -> part fkbx2tggt11xc2ofuwui2xsxkg0 a true",
                    "purchase_order.category_id -> purchase_order_category fk29o4jh7n4ttmsfh50ulu11hjg a true",
                    "purchase_order.vendor_id -> vendor fk20jcn7pw6hvx0uo0sh4y1d9xv a true",
                    "t_asset_part_associations.id_part -> part fkno24a5qbnjjn9xl0x2goppspd c true",
                    "t_asset_vendor_associations.id_vendor -> vendor fkrqd179yspkkgkqn96oggb7lhp c true",
                    "t_location_vendor_associations.id_vendor -> vendor fk8qdsmekvft0a8cjricw0fyjeq c true",
                    "t_multi_parts_part_associations.id_multi_parts -> multi_parts fk68kd07kt2lpb0oach23f1spmm c true",
                    "t_multi_parts_part_associations.id_part -> part fkh2jvrtqx4b25nfqafdlc92aek c true",
                    "t_part_customer_associations.id_part -> part fk1lqox0hm74ii88ptyp0ks4hx6 c true",
                    "t_part_file_associations.id_part -> part fkmjt71i7hevju3uswyyumdgttb c true",
                    "t_part_team_associations.id_part -> part fkp3whk5onr642t9ts5p2jmuda9 c true",
                    "t_part_user_associations.id_part -> part fk690l6jljarx0xx5mq51qhgq64 c true",
                    "t_part_vendor_associations.id_part -> part fk7d9ha6pxi5gajdqeds5ekdcw5 c true",
                    "t_part_vendor_associations.id_vendor -> vendor fklhnjg0qhqks9ob72oybq4xk73 c true",
                    "workflow_action.purchase_order_category_id -> purchase_order_category fkk62qy20rgsjmy2l8jvehrew58 a true",
                    "workflow_action.vendor_id -> vendor fkonpcm48v2k8p83xkj6gf5wwip a true",
                    "workflow_condition.part_id -> part fkbn902y2gyu5mrf43spdiw8duo a true",
                    "workflow_condition.purchase_order_category_id -> purchase_order_category fkr0f6t6jua29he3jhq40ty7wkc a true",
                    "workflow_condition.vendor_id -> vendor fkexm8hsr4hficvbx66p66asyhs a true"), keys);
        }

        @Test
        void columnsCitingInventoryWithoutForeignKey() {
            List<String> columns = jdbc.queryForList("""
                    SELECT c.table_name || '.' || c.column_name FROM information_schema.columns c
                    WHERE c.table_schema = current_schema()
                      AND c.column_name IN ('part_id', 'part_category_id', 'purchase_order_id',
                          'purchase_order_category_id', 'vendor_id', 'multi_parts_id', 'id_part', 'id_vendor',
                          'id_multi_parts')
                      AND NOT EXISTS (SELECT 1 FROM pg_constraint k
                          JOIN pg_attribute a ON a.attrelid = k.conrelid AND a.attnum = k.conkey[1]
                          WHERE k.contype = 'f' AND k.conrelid = (c.table_name)::regclass
                            AND a.attname = c.column_name)
                    ORDER BY 1""", String.class);

            assertEquals(List.of(), columns, "printed: " + columns);
        }
    }
}
