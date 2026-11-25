package com.grash.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grash.integration.support.FaultInjector;
import com.grash.integration.support.FaultInjector.Operation;
import com.grash.integration.support.TestApi;
import com.grash.integration.support.TestApi.Response;
import com.grash.integration.support.TestOrganizations;
import com.grash.integration.support.TestOrganizations.CommonData;
import com.grash.integration.support.TestRecords;
import com.grash.integration.support.TestWorkOrders;
import com.grash.integration.support.TestWorkOrders.Fixture;
import com.grash.model.Asset;
import com.grash.model.CostCategory;
import com.grash.model.Customer;
import com.grash.model.Location;
import com.grash.model.Meter;
import com.grash.model.Part;
import com.grash.model.PreventiveMaintenance;
import com.grash.model.Request;
import com.grash.model.TimeCategory;
import com.grash.model.WebhookEndpoint;
import com.grash.model.WorkOrder;
import com.grash.model.WorkOrderCategory;
import com.grash.model.WorkOrderMeterTrigger;
import com.grash.model.Workflow;
import com.grash.model.WorkflowAction;
import com.grash.model.WorkflowCondition;
import com.grash.model.enums.Status;
import com.grash.model.enums.WorkOrderMeterTriggerCondition;
import com.grash.model.enums.webhook.WebhookEvent;
import com.grash.model.enums.workflow.WFMainCondition;
import com.grash.model.enums.workflow.WorkOrderAction;
import com.grash.model.enums.workflow.WorkOrderCondition;
import com.grash.repository.CostCategoryRepository;
import com.grash.repository.CustomerRepository;
import com.grash.repository.FileRepository;
import com.grash.repository.MeterRepository;
import com.grash.repository.PartRepository;
import com.grash.repository.PreventiveMaintenanceRepository;
import com.grash.repository.RequestRepository;
import com.grash.repository.TimeCategoryRepository;
import com.grash.repository.WorkOrderCategoryRepository;
import com.grash.repository.WorkOrderMeterTriggerRepository;
import com.grash.repository.WorkOrderRepository;
import com.grash.repository.WorkflowRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;

/**
 * P-12b (PPB-02, PPB-23, PPB-26): a work order category, a cost category, a time category or a customer in use is
 * not deleted, so that the evidence of a work keeps them; an allowed deletion is recorded.
 */
@AutoConfigureMockMvc
class CategoryCustomerDeletionIntegrationTest extends AbstractIntegrationTest {

    private static final String DELETED_BODY = "{\"success\":true,\"message\":\"Deleted successfully\"}";
    private static final String WORK_ORDER_CATEGORY_IN_USE = "WORK_ORDER_CATEGORY_IN_USE";
    private static final String COST_CATEGORY_IN_USE = "COST_CATEGORY_IN_USE";
    private static final String TIME_CATEGORY_IN_USE = "TIME_CATEGORY_IN_USE";
    private static final String CUSTOMER_IN_USE = "CUSTOMER_IN_USE";

    /**
     * Child table, column, parent table of the 9 foreign keys that P-12b protects (NO ACTION).
     */
    private static final List<String[]> FOREIGN_KEYS = List.of(
            new String[]{"preventive_maintenance", "category_id", "work_order_category"},
            new String[]{"request", "category_id", "work_order_category"},
            new String[]{"work_order", "category_id", "work_order_category"},
            new String[]{"work_order_aud", "category_id", "work_order_category"},
            new String[]{"work_order_meter_trigger", "category_id", "work_order_category"},
            new String[]{"workflow_action", "work_order_category_id", "work_order_category"},
            new String[]{"workflow_condition", "work_order_category_id", "work_order_category"},
            new String[]{"additional_cost", "category_id", "cost_category"},
            new String[]{"labor", "time_category_id", "time_category"});

    private final TestOrganizations organizations;
    private final TestRecords records;
    private final TestApi api;
    private final TestWorkOrders workOrders;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;
    private final WorkOrderRepository workOrderRepository;
    private final WorkOrderCategoryRepository workOrderCategoryRepository;
    private final CostCategoryRepository costCategoryRepository;
    private final TimeCategoryRepository timeCategoryRepository;
    private final CustomerRepository customerRepository;
    private final WorkflowRepository workflowRepository;
    private final RequestRepository requestRepository;
    private final PreventiveMaintenanceRepository preventiveMaintenanceRepository;
    private final MeterRepository meterRepository;
    private final WorkOrderMeterTriggerRepository meterTriggerRepository;
    private final FaultInjector faults;
    private final List<CommonData> created = new ArrayList<>();

    @Autowired
    CategoryCustomerDeletionIntegrationTest(TestOrganizations organizations, TestRecords records, MockMvc mockMvc,
                                            ObjectMapper objectMapper, JdbcTemplate jdbc,
                                            WorkOrderRepository workOrderRepository, PartRepository partRepository,
                                            FileRepository fileRepository,
                                            WorkOrderCategoryRepository workOrderCategoryRepository,
                                            CostCategoryRepository costCategoryRepository,
                                            TimeCategoryRepository timeCategoryRepository,
                                            CustomerRepository customerRepository,
                                            WorkflowRepository workflowRepository,
                                            RequestRepository requestRepository,
                                            PreventiveMaintenanceRepository preventiveMaintenanceRepository,
                                            MeterRepository meterRepository,
                                            WorkOrderMeterTriggerRepository meterTriggerRepository) {
        this.organizations = organizations;
        this.records = records;
        this.api = new TestApi(mockMvc, objectMapper);
        this.workOrders = new TestWorkOrders(api, objectMapper, records, workOrderRepository, partRepository,
                fileRepository);
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
        this.faults = new FaultInjector(jdbc);
        this.workOrderRepository = workOrderRepository;
        this.workOrderCategoryRepository = workOrderCategoryRepository;
        this.costCategoryRepository = costCategoryRepository;
        this.timeCategoryRepository = timeCategoryRepository;
        this.customerRepository = customerRepository;
        this.workflowRepository = workflowRepository;
        this.requestRepository = requestRepository;
        this.preventiveMaintenanceRepository = preventiveMaintenanceRepository;
        this.meterRepository = meterRepository;
        this.meterTriggerRepository = meterTriggerRepository;
    }

    @AfterEach
    void cleanUp() {
        faults.removeAll();
        created.forEach(organizations::delete);
        created.clear();
    }

    // =====================================================================================================
    // Helpers
    // =====================================================================================================

    private CommonData data() {
        CommonData data = organizations.create();
        created.add(data);
        return data;
    }

    private JsonNode json(Response response) {
        try {
            return objectMapper.readTree(response.body());
        } catch (Exception e) {
            throw new AssertionError("not JSON: " + response, e);
        }
    }

    /**
     * Checks the 409 with its code and English message, and returns its blocking types.
     */
    private List<String> assertInUse(Response response, String code, String message) {
        JsonNode body = json(response);
        assertAll(
                () -> assertEquals(409, response.status(), response.toString()),
                () -> assertFalse(body.get("success").asBoolean(), response.toString()),
                () -> assertEquals(code, body.get("code").asText(), response.toString()),
                () -> assertEquals(message, body.get("message").asText(), response.toString()),
                () -> assertFalse(response.body().toLowerCase().contains("constraint"), response.toString()),
                () -> assertFalse(response.body().toLowerCase().contains("sql"), response.toString()));
        List<String> types = new ArrayList<>();
        if (body.has("blockingTypes")) body.get("blockingTypes").forEach(type -> types.add(type.asText()));
        return types;
    }

    private static String categoryMessage(String kind) {
        return "This " + kind + " category is used by other records and cannot be deleted";
    }

    private static final String CUSTOMER_MESSAGE =
            "This customer is associated with closed or retired records and cannot be deleted";

    private List<String> audit(CommonData data) {
        return jdbc.queryForList("SELECT action || ' ' || entity_type || ' ' || entity_id FROM audit_log "
                + "WHERE company_id = ? ORDER BY id", String.class, data.o1().company().getId());
    }

    private String cascade(CommonData data) {
        return jdbc.queryForObject("SELECT cascade_rows::text FROM audit_log WHERE company_id = ?", String.class,
                data.o1().company().getId());
    }

    private long count(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, Long.class, arguments);
    }

    private List<Map<String, Object>> row(String table, Long id) {
        return jdbc.queryForList("SELECT * FROM " + table + " WHERE id = ?", id);
    }

    private void assertNoSideEffects() {
        assertTrue(mailDouble.calls().isEmpty(), "no mail");
        assertTrue(webhookDouble.calls().isEmpty(), "no webhook");
    }

    private WorkOrderCategory workOrderCategory(CommonData data) {
        WorkOrderCategory category = new WorkOrderCategory(TestRecords.unique("P-12b category"),
                data.o1().company().getCompanySettings());
        category.setCreatedBy(data.o1().admin().getId());
        return workOrderCategoryRepository.saveAndFlush(category);
    }

    private CostCategory costCategory(CommonData data) {
        CostCategory category = new CostCategory(TestRecords.unique("P-12b cost"),
                data.o1().company().getCompanySettings());
        category.setCreatedBy(data.o1().admin().getId());
        return costCategoryRepository.saveAndFlush(category);
    }

    private TimeCategory timeCategory(CommonData data) {
        TimeCategory category = new TimeCategory(TestRecords.unique("P-12b time"),
                data.o1().company().getCompanySettings());
        category.setCreatedBy(data.o1().admin().getId());
        return timeCategoryRepository.saveAndFlush(category);
    }

    private Customer customer(CommonData data) {
        Customer customer = new Customer();
        customer.setName(TestRecords.unique("P-12b customer"));
        customer.setCompany(data.o1().company());
        customer.setCreatedBy(data.o1().admin().getId());
        return customerRepository.saveAndFlush(customer);
    }

    /**
     * A work order with {@code category}, saved with JPA so that its revision cites it too.
     */
    private WorkOrder workOrderWith(CommonData data, WorkOrderCategory category, Status status) {
        WorkOrder workOrder = records.workOrder(data.o1().company(), null, status, false);
        workOrder.setCategory(category);
        return workOrderRepository.saveAndFlush(workOrder);
    }

    private Response deleteWorkOrderCategory(CommonData data, WorkOrderCategory category) {
        return api.call(delete("/work-order-categories/" + category.getId()), api.login(data.o1().admin()));
    }

    private Response deleteCustomer(CommonData data, Customer customer) {
        return api.call(delete("/customers/" + customer.getId()), api.login(data.o1().admin()));
    }

    private void associate(String table, String ownerColumn, Long owner, String customerColumn, Customer customer) {
        jdbc.update("INSERT INTO " + table + " (" + ownerColumn + ", " + customerColumn + ") VALUES (?, ?)", owner,
                customer.getId());
    }

    // =====================================================================================================
    // E-1 · Work order category (CA-PPB-099)
    // =====================================================================================================

    @Nested
    class WorkOrderCategoryInUse {

        @Test
        void ca099_usedByClosedWorkOrder_409WithWorkOrder_workOrderAndWebhookFiltersUnchanged_noRecord() {
            CommonData data = data();
            WorkOrderCategory category = workOrderCategory(data);
            WorkOrder closed = records.workOrder(data.o1().company(), null, Status.COMPLETE, false);
            jdbc.update("UPDATE work_order SET category_id = ? WHERE id = ?", category.getId(), closed.getId());
            WebhookEndpoint endpoint = records.endpoint(data.o1().company(), WebhookEvent.WORK_ORDER_CHANGE);
            jdbc.update("INSERT INTO webhook_endpoint_work_order_categories (webhook_endpoint_id, "
                    + "work_order_categories_id) VALUES (?, ?)", endpoint.getId(), category.getId());
            String filters = "SELECT * FROM webhook_endpoint_work_order_categories ORDER BY 1, 2";
            Map<String, Object> before = TestWorkOrders.rows(jdbc, closed.getId(), null);
            List<Map<String, Object>> filtersBefore = jdbc.queryForList(filters);
            List<Map<String, Object>> categoryBefore = row("work_order_category", category.getId());

            Response response = deleteWorkOrderCategory(data, category);

            assertEquals(List.of("WORK_ORDER"), assertInUse(response, WORK_ORDER_CATEGORY_IN_USE,
                    categoryMessage("work order")));
            assertAll(
                    () -> assertEquals(before, TestWorkOrders.rows(jdbc, closed.getId(), null),
                            "the work order keeps its category and every other value"),
                    () -> assertEquals(filtersBefore, jdbc.queryForList(filters), "webhook filters unchanged"),
                    () -> assertEquals(categoryBefore, row("work_order_category", category.getId())),
                    () -> assertTrue(audit(data).isEmpty(), "no audit record"),
                    () -> assertNoSideEffects());
        }

        @Test
        void citedOnlyByAWorkOrderRevision_409WithWorkOrderHistory() {
            CommonData data = data();
            WorkOrderCategory category = workOrderCategory(data);
            WorkOrder workOrder = workOrderWith(data, category, Status.OPEN);
            workOrder.setCategory(null);
            workOrderRepository.saveAndFlush(workOrder);

            Response response = deleteWorkOrderCategory(data, category);

            assertEquals(List.of("WORK_ORDER_HISTORY"), assertInUse(response, WORK_ORDER_CATEGORY_IN_USE,
                    categoryMessage("work order")));
            assertTrue(workOrderCategoryRepository.existsById(category.getId()));
        }

        @Test
        void citedByRequestPreventiveMaintenanceMeterTriggerAndWorkflow_409WithEachType_allUnchanged() {
            CommonData data = data();
            WorkOrderCategory category = workOrderCategory(data);
            Request request = new Request();
            request.setTitle(TestRecords.unique("P-12b request"));
            request.setCompany(data.o1().company());
            request.setCategory(category);
            request = requestRepository.saveAndFlush(request);
            PreventiveMaintenance preventiveMaintenance = new PreventiveMaintenance();
            preventiveMaintenance.setName(TestRecords.unique("P-12b PM"));
            preventiveMaintenance.setTitle(preventiveMaintenance.getName());
            preventiveMaintenance.setCompany(data.o1().company());
            preventiveMaintenance.setCategory(category);
            preventiveMaintenance = preventiveMaintenanceRepository.saveAndFlush(preventiveMaintenance);
            Meter meter = new Meter();
            meter.setName(TestRecords.unique("P-12b meter"));
            meter.setUpdateFrequency(1);
            meter.setAsset(records.asset(data.o1().company()));
            meter.setCompany(data.o1().company());
            meter = meterRepository.saveAndFlush(meter);
            WorkOrderMeterTrigger trigger = new WorkOrderMeterTrigger();
            trigger.setName(TestRecords.unique("P-12b trigger"));
            trigger.setTitle(trigger.getName());
            trigger.setTriggerCondition(WorkOrderMeterTriggerCondition.MORE_THAN);
            trigger.setValue(10);
            trigger.setMeter(meter);
            trigger.setCompany(data.o1().company());
            trigger.setCategory(category);
            trigger = meterTriggerRepository.saveAndFlush(trigger);
            WorkflowAction action = new WorkflowAction();
            action.setCompany(data.o1().company());
            action.setWorkOrderAction(WorkOrderAction.ASSIGN_CATEGORY);
            action.setWorkOrderCategory(category);
            WorkflowCondition condition = new WorkflowCondition();
            condition.setCompany(data.o1().company());
            condition.setWorkOrderCondition(WorkOrderCondition.CATEGORY_IS);
            condition.setWorkOrderCategory(category);
            Workflow workflow = new Workflow();
            workflow.setCompany(data.o1().company());
            workflow.setTitle(TestRecords.unique("P-12b workflow"));
            workflow.setMainCondition(WFMainCondition.WORK_ORDER_CREATED);
            workflow.setAction(action);
            workflow.setSecondaryConditions(new ArrayList<>(List.of(condition)));
            workflow = workflowRepository.saveAndFlush(workflow);
            Long actionId = workflow.getAction().getId();
            Long conditionId = workflow.getSecondaryConditions().iterator().next().getId();
            Map<String, List<Map<String, Object>>> before = new LinkedHashMap<>();
            before.put("request", row("request", request.getId()));
            before.put("preventive_maintenance", row("preventive_maintenance", preventiveMaintenance.getId()));
            before.put("work_order_meter_trigger", row("work_order_meter_trigger", trigger.getId()));
            before.put("workflow", row("workflow", workflow.getId()));
            before.put("workflow_action", row("workflow_action", actionId));
            before.put("workflow_condition", row("workflow_condition", conditionId));

            Response response = deleteWorkOrderCategory(data, category);

            assertEquals(List.of("PREVENTIVE_MAINTENANCE", "REQUEST", "WORKFLOW_ACTION", "WORKFLOW_CONDITION",
                            "WORK_ORDER_METER_TRIGGER"),
                    assertInUse(response, WORK_ORDER_CATEGORY_IN_USE, categoryMessage("work order")).stream()
                            .sorted().toList());
            Map<String, List<Map<String, Object>>> after = new LinkedHashMap<>();
            after.put("request", row("request", request.getId()));
            after.put("preventive_maintenance", row("preventive_maintenance", preventiveMaintenance.getId()));
            after.put("work_order_meter_trigger", row("work_order_meter_trigger", trigger.getId()));
            after.put("workflow", row("workflow", workflow.getId()));
            after.put("workflow_action", row("workflow_action", actionId));
            after.put("workflow_condition", row("workflow_condition", conditionId));
            assertEquals(before, after);
            assertTrue(audit(data).isEmpty());
        }

        @Test
        void unused_withWebhookFilter_200_deletedAndRecorded_filterInTheSummary() {
            CommonData data = data();
            WorkOrderCategory category = workOrderCategory(data);
            WebhookEndpoint endpoint = records.endpoint(data.o1().company(), WebhookEvent.WORK_ORDER_CHANGE);
            jdbc.update("INSERT INTO webhook_endpoint_work_order_categories (webhook_endpoint_id, "
                    + "work_order_categories_id) VALUES (?, ?)", endpoint.getId(), category.getId());

            Response response = deleteWorkOrderCategory(data, category);

            assertEquals(200, response.status(), response.toString());
            assertEquals(DELETED_BODY, response.body());
            assertFalse(workOrderCategoryRepository.existsById(category.getId()));
            assertEquals(List.of("DELETE WORK_ORDER_CATEGORY " + category.getId()), audit(data));
            assertTrue(cascade(data).contains("\"webhook_endpoint_work_order_categories\": 1"), cascade(data));
            assertEquals(category.getName(), jdbc.queryForObject("SELECT entity_name FROM audit_log WHERE "
                    + "company_id = ?", String.class, data.o1().company().getId()));
        }

        @Test
        void auditWriteFails_categoryStillExists() {
            CommonData data = data();
            WorkOrderCategory category = workOrderCategory(data);

            try (FaultInjector.Fault fault = faults.failOn("audit_log", Operation.INSERT)) {
                Response response = deleteWorkOrderCategory(data, category);
                assertFalse(response.is2xx(), response.toString());
                assertEquals(1, fault.hits());
            }
            assertTrue(workOrderCategoryRepository.existsById(category.getId()));
        }
    }

    // =====================================================================================================
    // E-2 · Cost and time categories (CA-PPB-101)
    // =====================================================================================================

    @Nested
    class CostAndTimeCategoryInUse {

        @Test
        void ca101_costCategoryOfAdditionalCost_409_costUnchanged_noRecord() {
            CommonData data = data();
            Fixture fixture = workOrders.create(data.o1().admin(), data.o1().technician(), Status.OPEN, false);
            CostCategory category = costCategory(data);
            jdbc.update("UPDATE additional_cost SET category_id = ? WHERE id = ?", category.getId(), fixture.cost());
            workOrders.moveTo(fixture.id(), Status.COMPLETE, false);
            Map<String, Object> before = TestWorkOrders.rows(jdbc, fixture.id(), fixture.part());

            Response response = api.call(delete("/cost-categories/" + category.getId()),
                    api.login(data.o1().admin()));

            assertEquals(List.of("ADDITIONAL_COST"), assertInUse(response, COST_CATEGORY_IN_USE,
                    categoryMessage("cost")));
            assertEquals(before, TestWorkOrders.rows(jdbc, fixture.id(), fixture.part()));
            assertTrue(costCategoryRepository.existsById(category.getId()));
            assertTrue(audit(data).isEmpty());
            assertNoSideEffects();
        }

        @Test
        void ca101_timeCategoryOfLabor_409_laborUnchanged_noRecord() {
            CommonData data = data();
            Fixture fixture = workOrders.create(data.o1().admin(), data.o1().technician(), Status.OPEN, false);
            TimeCategory category = timeCategory(data);
            jdbc.update("UPDATE labor SET time_category_id = ? WHERE id = ?", category.getId(), fixture.labor());
            workOrders.moveTo(fixture.id(), Status.COMPLETE, false);
            Map<String, Object> before = TestWorkOrders.rows(jdbc, fixture.id(), fixture.part());

            Response response = api.call(delete("/time-categories/" + category.getId()),
                    api.login(data.o1().admin()));

            assertEquals(List.of("LABOR"), assertInUse(response, TIME_CATEGORY_IN_USE, categoryMessage("time")));
            assertEquals(before, TestWorkOrders.rows(jdbc, fixture.id(), fixture.part()));
            assertTrue(timeCategoryRepository.existsById(category.getId()));
            assertTrue(audit(data).isEmpty());
            assertNoSideEffects();
        }

        @Test
        void unused_200_deletedAndRecorded() {
            CommonData data = data();
            CostCategory cost = costCategory(data);
            TimeCategory time = timeCategory(data);
            String admin = api.login(data.o1().admin());

            assertEquals(DELETED_BODY, api.call(delete("/cost-categories/" + cost.getId()), admin).body());
            assertEquals(DELETED_BODY, api.call(delete("/time-categories/" + time.getId()), admin).body());

            assertFalse(costCategoryRepository.existsById(cost.getId()));
            assertFalse(timeCategoryRepository.existsById(time.getId()));
            assertEquals(List.of("DELETE COST_CATEGORY " + cost.getId(), "DELETE TIME_CATEGORY " + time.getId()),
                    audit(data));
        }
    }

    // =====================================================================================================
    // E-3 · Customer (CA-PPB-109, CA-PPB-110)
    // =====================================================================================================

    @Nested
    class CustomerInUse {

        @Test
        void ca109_associatedWithCompleteWorkOrder_409WithWorkOrder_associationKept_noRecord() {
            CommonData data = data();
            Customer customer = customer(data);
            WorkOrder closed = records.workOrder(data.o1().company(), null, Status.COMPLETE, false);
            associate("work_order_customers", "work_order_id", closed.getId(), "customers_id", customer);
            Map<String, Object> before = TestWorkOrders.rows(jdbc, closed.getId(), null);
            List<Map<String, Object>> customerBefore = row("customer", customer.getId());

            Response response = deleteCustomer(data, customer);

            assertEquals(List.of("WORK_ORDER"), assertInUse(response, CUSTOMER_IN_USE, CUSTOMER_MESSAGE));
            assertEquals(before, TestWorkOrders.rows(jdbc, closed.getId(), null), "the association exists");
            assertEquals(customerBefore, row("customer", customer.getId()));
            assertTrue(audit(data).isEmpty());
            assertNoSideEffects();
        }

        @Test
        void associatedWithArchivedOpenWorkOrder_409WithWorkOrder() {
            CommonData data = data();
            Customer customer = customer(data);
            WorkOrder archived = records.workOrder(data.o1().company(), null, Status.OPEN, false);
            workOrders.moveTo(archived.getId(), Status.OPEN, true);
            associate("work_order_customers", "work_order_id", archived.getId(), "customers_id", customer);

            assertEquals(List.of("WORK_ORDER"), assertInUse(deleteCustomer(data, customer), CUSTOMER_IN_USE,
                    CUSTOMER_MESSAGE));
            assertEquals(1, count("SELECT count(*) FROM work_order_customers WHERE customers_id = ?",
                    customer.getId()));
        }

        @Test
        void ca110_associatedOnlyWithOpenWorkOrder_200_workOrderWithoutTheCustomer_recorded() {
            CommonData data = data();
            Customer customer = customer(data);
            WorkOrder open = records.workOrder(data.o1().company(), null, Status.OPEN, false);
            associate("work_order_customers", "work_order_id", open.getId(), "customers_id", customer);

            Response response = deleteCustomer(data, customer);

            assertEquals(200, response.status(), response.toString());
            assertEquals(DELETED_BODY, response.body());
            assertFalse(customerRepository.existsById(customer.getId()));
            assertEquals(0, count("SELECT count(*) FROM work_order_customers WHERE work_order_id = ?",
                    open.getId()));
            assertEquals(1, count("SELECT count(*) FROM work_order WHERE id = ?", open.getId()));
            assertEquals(List.of("DELETE CUSTOMER " + customer.getId()), audit(data));
            assertTrue(cascade(data).contains("\"work_order_customers\": 1"), cascade(data));
        }

        @Test
        void approvedAndRejectedRequests_409WithRequest_pendingRequest_200() {
            CommonData data = data();
            Customer approvedCustomer = customer(data);
            Customer rejectedCustomer = customer(data);
            Customer pendingCustomer = customer(data);
            WorkOrder approval = records.workOrder(data.o1().company(), null, Status.OPEN, false);
            Request approved = request(data);
            jdbc.update("UPDATE request SET work_order_id = ? WHERE id = ?", approval.getId(), approved.getId());
            Request rejected = request(data);
            jdbc.update("UPDATE request SET cancelled = true WHERE id = ?", rejected.getId());
            Request pending = request(data);
            associate("request_customers", "request_id", approved.getId(), "customers_id", approvedCustomer);
            associate("request_customers", "request_id", rejected.getId(), "customers_id", rejectedCustomer);
            associate("request_customers", "request_id", pending.getId(), "customers_id", pendingCustomer);

            assertEquals(List.of("REQUEST"), assertInUse(deleteCustomer(data, approvedCustomer), CUSTOMER_IN_USE,
                    CUSTOMER_MESSAGE));
            assertEquals(List.of("REQUEST"), assertInUse(deleteCustomer(data, rejectedCustomer), CUSTOMER_IN_USE,
                    CUSTOMER_MESSAGE));
            assertEquals(200, deleteCustomer(data, pendingCustomer).status());
            assertEquals(2, count("SELECT count(*) FROM request_customers WHERE request_id IN (?, ?)",
                    approved.getId(), rejected.getId()));
            assertEquals(0, count("SELECT count(*) FROM request_customers WHERE request_id = ?", pending.getId()));
        }

        private Request request(CommonData data) {
            Request request = new Request();
            request.setTitle(TestRecords.unique("P-12b request"));
            request.setCompany(data.o1().company());
            return requestRepository.saveAndFlush(request);
        }

        @Test
        void archivedAsset_409WithAsset_activeAsset_200() {
            CommonData data = data();
            Customer archivedCustomer = customer(data);
            Customer activeCustomer = customer(data);
            Asset archived = records.asset(data.o1().company());
            jdbc.update("UPDATE asset SET archived = true WHERE id = ?", archived.getId());
            Asset active = records.asset(data.o1().company());
            associate("t_asset_customer_associations", "id_asset", archived.getId(), "id_customer",
                    archivedCustomer);
            associate("t_asset_customer_associations", "id_asset", active.getId(), "id_customer", activeCustomer);

            assertEquals(List.of("ASSET"), assertInUse(deleteCustomer(data, archivedCustomer), CUSTOMER_IN_USE,
                    CUSTOMER_MESSAGE));
            assertEquals(200, deleteCustomer(data, activeCustomer).status());
            assertEquals(1, count("SELECT count(*) FROM t_asset_customer_associations WHERE id_customer = ?",
                    archivedCustomer.getId()));
        }

        @Test
        void disabledPreventiveMaintenance_409WithPreventiveMaintenance_enabled_200() {
            CommonData data = data();
            Customer disabledCustomer = customer(data);
            Customer enabledCustomer = customer(data);
            PreventiveMaintenance disabled = preventiveMaintenance(data);
            jdbc.update("UPDATE schedule SET disabled = true WHERE id = ?", disabled.getSchedule().getId());
            PreventiveMaintenance enabled = preventiveMaintenance(data);
            associate("preventive_maintenance_customers", "preventive_maintenance_id", disabled.getId(),
                    "customers_id", disabledCustomer);
            associate("preventive_maintenance_customers", "preventive_maintenance_id", enabled.getId(),
                    "customers_id", enabledCustomer);

            assertEquals(List.of("PREVENTIVE_MAINTENANCE"), assertInUse(deleteCustomer(data, disabledCustomer),
                    CUSTOMER_IN_USE, CUSTOMER_MESSAGE));
            assertEquals(200, deleteCustomer(data, enabledCustomer).status());
        }

        private PreventiveMaintenance preventiveMaintenance(CommonData data) {
            PreventiveMaintenance preventiveMaintenance = new PreventiveMaintenance();
            preventiveMaintenance.setName(TestRecords.unique("P-12b PM"));
            preventiveMaintenance.setTitle(preventiveMaintenance.getName());
            preventiveMaintenance.setCompany(data.o1().company());
            return preventiveMaintenanceRepository.saveAndFlush(preventiveMaintenance);
        }

        @Test
        void locationAndPart_neverBlock_200_associationsRemoved() {
            CommonData data = data();
            Customer customer = customer(data);
            Location location = records.location(data.o1().company());
            Part part = records.part(data.o1().company(), data.o1().admin());
            associate("t_location_customer_associations", "id_location", location.getId(), "id_customer", customer);
            associate("t_part_customer_associations", "id_part", part.getId(), "id_customer", customer);

            assertEquals(200, deleteCustomer(data, customer).status());
            assertEquals(0, count("SELECT count(*) FROM t_location_customer_associations WHERE id_location = ?",
                    location.getId()));
            assertEquals(0, count("SELECT count(*) FROM t_part_customer_associations WHERE id_part = ?",
                    part.getId()));
        }

        @Test
        void closedWorkOrderAndArchivedAsset_409WithBothTypes() {
            CommonData data = data();
            Customer customer = customer(data);
            WorkOrder closed = records.workOrder(data.o1().company(), null, Status.COMPLETE, false);
            Asset archived = records.asset(data.o1().company());
            jdbc.update("UPDATE asset SET archived = true WHERE id = ?", archived.getId());
            associate("work_order_customers", "work_order_id", closed.getId(), "customers_id", customer);
            associate("t_asset_customer_associations", "id_asset", archived.getId(), "id_customer", customer);

            assertEquals(List.of("ASSET", "WORK_ORDER"), assertInUse(deleteCustomer(data, customer),
                    CUSTOMER_IN_USE, CUSTOMER_MESSAGE).stream().sorted().toList());
        }
    }

    // =====================================================================================================
    // D-P12b-1 · Demo purge with the protected keys
    // =====================================================================================================

    @Nested
    class DemoPurge {

        private WorkOrderCategory demoCategory(CommonData data) {
            WorkOrderCategory category = workOrderCategory(data);
            category.setDemo(true);
            return workOrderCategoryRepository.saveAndFlush(category);
        }

        private WorkOrder demoWorkOrder(CommonData data, WorkOrderCategory category) {
            WorkOrder workOrder = records.workOrder(data.o1().company(), null, Status.OPEN, true);
            workOrder.setCategory(category);
            return workOrderRepository.saveAndFlush(workOrder);
        }

        private Response purge(CommonData data) {
            return api.call(delete("/demo/demo-data"), api.login(data.o1().admin()));
        }

        @Test
        void onlyDemo_200_deletesOnlyTheRevisionsOfTheDemoWorkOrders() {
            CommonData data = data();
            WorkOrderCategory demo = demoCategory(data);
            WorkOrder demoWorkOrder = demoWorkOrder(data, demo);
            WorkOrderCategory real = workOrderCategory(data);
            WorkOrder realWorkOrder = workOrderWith(data, real, Status.OPEN);
            List<Map<String, Object>> realRevisions = jdbc.queryForList("SELECT * FROM work_order_aud WHERE id = ? "
                    + "ORDER BY rev", realWorkOrder.getId());

            Response response = purge(data);

            assertEquals(200, response.status(), response.toString());
            assertFalse(workOrderCategoryRepository.existsById(demo.getId()));
            assertEquals(0, count("SELECT count(*) FROM work_order WHERE id = ?", demoWorkOrder.getId()));
            assertEquals(0, count("SELECT count(*) FROM work_order_aud WHERE id = ? AND category_id IS NOT NULL",
                    demoWorkOrder.getId()));
            assertEquals(realRevisions, jdbc.queryForList("SELECT * FROM work_order_aud WHERE id = ? ORDER BY rev",
                    realWorkOrder.getId()), "the revisions of a real work order are kept");
            assertTrue(workOrderCategoryRepository.existsById(real.getId()));
        }

        @Test
        void realWorkOrderCitesDemoCategory_409_nothingDeleted() {
            // P-18 (E-5, PPB-20 d): before, 409 RECORD_IN_USE from the global handler
            CommonData data = data();
            WorkOrderCategory demo = demoCategory(data);
            WorkOrder demoWorkOrder = demoWorkOrder(data, demo);
            WorkOrder realWorkOrder = workOrderWith(data, demo, Status.OPEN);
            long demoRevisions = count("SELECT count(*) FROM work_order_aud WHERE id = ?", demoWorkOrder.getId());

            Response response = purge(data);

            assertEquals(409, response.status(), response.toString());
            assertEquals("DEMO_DATA_IN_USE", json(response).get("code").asText());
            assertAll(
                    () -> assertTrue(workOrderCategoryRepository.existsById(demo.getId())),
                    () -> assertEquals(1, count("SELECT count(*) FROM work_order WHERE id = ?",
                            demoWorkOrder.getId()), "the purge is rejected whole"),
                    () -> assertEquals(demoRevisions, count("SELECT count(*) FROM work_order_aud WHERE id = ?",
                            demoWorkOrder.getId()), "the demo revisions are kept"),
                    () -> assertEquals(demo.getId(), jdbc.queryForObject("SELECT category_id FROM work_order "
                            + "WHERE id = ?", Long.class, realWorkOrder.getId())));
        }

        @Test
        void realWorkOrderCitedDemoCategoryInARevision_409_nothingDeleted() {
            // P-18 (E-5, PPB-20 d): before, 409 RECORD_IN_USE from the global handler
            CommonData data = data();
            WorkOrderCategory demo = demoCategory(data);
            WorkOrder demoWorkOrder = demoWorkOrder(data, demo);
            WorkOrder realWorkOrder = workOrderWith(data, demo, Status.OPEN);
            realWorkOrder.setCategory(null);
            workOrderRepository.saveAndFlush(realWorkOrder);

            Response response = purge(data);

            assertEquals(409, response.status(), response.toString());
            assertEquals("DEMO_DATA_IN_USE", json(response).get("code").asText());
            assertTrue(workOrderCategoryRepository.existsById(demo.getId()));
            assertEquals(1, count("SELECT count(*) FROM work_order WHERE id = ?", demoWorkOrder.getId()));
        }
    }

    // =====================================================================================================
    // E-4 · Foreign keys (PPB-21, PPB-22)
    // =====================================================================================================

    @Nested
    class Schema {

        @Test
        void the9ForeignKeys_areNoAction_andLeadAValidIndex() {
            List<String> wrong = new ArrayList<>();
            for (String[] fk : FOREIGN_KEYS) {
                List<String> rules = jdbc.queryForList("SELECT c.confdeltype::text FROM pg_constraint c "
                        + "JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = c.conkey[1] "
                        + "WHERE c.contype = 'f' AND c.conrelid = ?::regclass AND c.confrelid = ?::regclass "
                        + "AND a.attname = ?", String.class, fk[0], fk[2], fk[1]);
                Long indexes = jdbc.queryForObject("SELECT count(*) FROM pg_index i "
                        + "JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = i.indkey[0] "
                        + "WHERE i.indrelid = ?::regclass AND a.attname = ? AND i.indisvalid "
                        + "AND i.indpred IS NULL", Long.class, fk[0], fk[1]);
                String name = fk[0] + "." + fk[1] + " -> " + fk[2];
                if (!rules.equals(List.of("a"))) wrong.add(name + ": rule " + rules + ", expected a");
                if (indexes == null || indexes < 1) wrong.add(name + ": no index on the child column");
            }
            assertEquals(9, FOREIGN_KEYS.size());
            assertTrue(wrong.isEmpty(), String.join("\n", wrong));
        }

        @Test
        void theKeysToCustomerAndTheWebhookFilter_keepCascade() {
            List<String> rules = jdbc.queryForList("SELECT DISTINCT confdeltype::text FROM pg_constraint WHERE "
                    + "contype = 'f' AND (confrelid = 'customer'::regclass OR conrelid = "
                    + "'webhook_endpoint_work_order_categories'::regclass)", String.class);
            assertEquals(List.of("c"), rules);
        }
    }

    // =====================================================================================================
    // The case of the audit (fierabras/pruebas/H-1/H1PersistenceIntegrationTest.java), with its original assertion
    // =====================================================================================================

    @Nested
    class Audit {

        @Test
        void h1_11_closedCostKeepsCategory() {
            CommonData data = data();
            String admin = api.login(data.o1().admin());
            var f = workOrders.create(data.o1().admin(), data.o1().technician(), Status.OPEN, false);
            var category = costCategoryRepository.saveAndFlush(new CostCategory("H1 cost",
                    data.o1().company().getCompanySettings()));
            jdbc.update("UPDATE additional_cost SET category_id=? WHERE id=?", category.getId(), f.cost());
            workOrders.moveTo(f.id(), Status.COMPLETE, false);
            Response result = api.call(delete("/cost-categories/" + category.getId()), admin);
            Long after = jdbc.queryForObject("SELECT category_id FROM additional_cost WHERE id=?", Long.class,
                    f.cost());
            System.out.println("H1 cost category: HTTP=" + result.status() + ", retained="
                    + category.getId().equals(after));
            assertEquals(category.getId(), after);
        }
    }
}
