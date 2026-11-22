package com.grash.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.grash.integration.support.TestApi;
import com.grash.integration.support.TestApi.Response;
import com.grash.integration.support.TestOrganizations;
import com.grash.integration.support.TestOrganizations.CommonData;
import com.grash.integration.support.TestRecords;
import com.grash.integration.support.TestWorkOrders;
import com.grash.integration.support.TestWorkOrders.Fixture;
import com.grash.model.CostCategory;
import com.grash.model.Customer;
import com.grash.model.TimeCategory;
import com.grash.model.User;
import com.grash.model.WebhookEndpoint;
import com.grash.model.WorkOrder;
import com.grash.model.WorkOrderCategory;
import com.grash.model.Workflow;
import com.grash.model.WorkflowAction;
import com.grash.model.enums.Status;
import com.grash.model.enums.webhook.WebhookEvent;
import com.grash.model.enums.workflow.WFMainCondition;
import com.grash.model.enums.workflow.WorkOrderAction;
import com.grash.repository.CostCategoryRepository;
import com.grash.repository.CustomerRepository;
import com.grash.repository.FileRepository;
import com.grash.repository.PartRepository;
import com.grash.repository.TimeCategoryRepository;
import com.grash.repository.WorkOrderCategoryRepository;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;

/**
 * P-12b characterization: fixes how the deletion of a work order category, a cost category, a time category and a
 * customer behaves before any change. The 403 and 404 answers must keep passing after P-12b. The tests that showed
 * a category or customer in use being deleted (200) now fix the contract of P-12b (409, nothing changed), as step 8
 * of the order allows.
 */
@AutoConfigureMockMvc
class CategoryCustomerCharacterizationIntegrationTest extends AbstractIntegrationTest {

    private static final String DELETED_BODY = "{\"success\":true,\"message\":\"Deleted successfully\"}";

    private final TestOrganizations organizations;
    private final TestRecords records;
    private final TestApi api;
    private final TestWorkOrders workOrders;
    private final JdbcTemplate jdbc;
    private final WorkOrderCategoryRepository workOrderCategoryRepository;
    private final CostCategoryRepository costCategoryRepository;
    private final TimeCategoryRepository timeCategoryRepository;
    private final CustomerRepository customerRepository;
    private final WorkflowRepository workflowRepository;
    private final List<CommonData> created = new ArrayList<>();

    @Autowired
    CategoryCustomerCharacterizationIntegrationTest(TestOrganizations organizations, TestRecords records,
                                                    MockMvc mockMvc, ObjectMapper objectMapper, JdbcTemplate jdbc,
                                                    WorkOrderRepository workOrderRepository,
                                                    PartRepository partRepository, FileRepository fileRepository,
                                                    WorkOrderCategoryRepository workOrderCategoryRepository,
                                                    CostCategoryRepository costCategoryRepository,
                                                    TimeCategoryRepository timeCategoryRepository,
                                                    CustomerRepository customerRepository,
                                                    WorkflowRepository workflowRepository) {
        this.organizations = organizations;
        this.records = records;
        this.api = new TestApi(mockMvc, objectMapper);
        this.workOrders = new TestWorkOrders(api, objectMapper, records, workOrderRepository, partRepository,
                fileRepository);
        this.jdbc = jdbc;
        this.workOrderCategoryRepository = workOrderCategoryRepository;
        this.costCategoryRepository = costCategoryRepository;
        this.timeCategoryRepository = timeCategoryRepository;
        this.customerRepository = customerRepository;
        this.workflowRepository = workflowRepository;
    }

    @AfterEach
    void cleanUp() {
        created.forEach(organizations::delete);
        created.clear();
    }

    private CommonData data() {
        CommonData data = organizations.create();
        created.add(data);
        return data;
    }

    private long auditRecords(CommonData data) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE company_id = ?", Long.class,
                data.o1().company().getId());
    }

    private long count(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, Long.class, arguments);
    }

    private WorkOrderCategory workOrderCategory(CommonData data, User creator) {
        WorkOrderCategory category = new WorkOrderCategory(TestRecords.unique("P-12b category"),
                data.o1().company().getCompanySettings());
        category.setCreatedBy(creator == null ? null : creator.getId());
        return workOrderCategoryRepository.saveAndFlush(category);
    }

    private CostCategory costCategory(CommonData data, User creator) {
        CostCategory category = new CostCategory(TestRecords.unique("P-12b cost"),
                data.o1().company().getCompanySettings());
        category.setCreatedBy(creator == null ? null : creator.getId());
        return costCategoryRepository.saveAndFlush(category);
    }

    private TimeCategory timeCategory(CommonData data, User creator) {
        TimeCategory category = new TimeCategory(TestRecords.unique("P-12b time"),
                data.o1().company().getCompanySettings());
        category.setCreatedBy(creator == null ? null : creator.getId());
        return timeCategoryRepository.saveAndFlush(category);
    }

    private Customer customer(CommonData data) {
        Customer customer = new Customer();
        customer.setName(TestRecords.unique("P-12b customer"));
        customer.setCompany(data.o1().company());
        customer.setCreatedBy(data.o1().admin().getId());
        return customerRepository.saveAndFlush(customer);
    }

    @Nested
    class WorkOrderCategories {

        @Test
        void usedByClosedWorkOrderWorkflowAndWebhook_409_referenceWorkflowAndFilterKept() {
            CommonData data = data();
            WorkOrderCategory category = workOrderCategory(data, data.o1().admin());
            WorkOrder closed = records.workOrder(data.o1().company(), null, Status.COMPLETE, false);
            jdbc.update("UPDATE work_order SET category_id = ? WHERE id = ?", category.getId(), closed.getId());
            WorkflowAction action = new WorkflowAction();
            action.setCompany(data.o1().company());
            action.setWorkOrderAction(WorkOrderAction.ASSIGN_CATEGORY);
            action.setWorkOrderCategory(category);
            Workflow workflow = new Workflow();
            workflow.setCompany(data.o1().company());
            workflow.setTitle(TestRecords.unique("P-12b workflow"));
            workflow.setMainCondition(WFMainCondition.WORK_ORDER_CREATED);
            workflow.setAction(action);
            workflow = workflowRepository.saveAndFlush(workflow);
            WebhookEndpoint endpoint = records.endpoint(data.o1().company(), WebhookEvent.WORK_ORDER_CHANGE);
            jdbc.update("INSERT INTO webhook_endpoint_work_order_categories (webhook_endpoint_id, "
                    + "work_order_categories_id) VALUES (?, ?)", endpoint.getId(), category.getId());

            Response response = api.call(delete("/work-order-categories/" + category.getId()),
                    api.login(data.o1().admin()));

            // P-12b: before, 200 with the category nulled, the workflow deleted and the filter removed
            assertEquals(409, response.status(), response.toString());
            assertTrue(response.body().contains("WORK_ORDER_CATEGORY_IN_USE"), response.body());
            Long workflowId = workflow.getId();
            assertAll(
                    () -> assertEquals(1, count("SELECT count(*) FROM work_order_category WHERE id = ?",
                            category.getId())),
                    () -> assertEquals(category.getId(), jdbc.queryForObject("SELECT category_id FROM work_order "
                            + "WHERE id = ?", Long.class, closed.getId()), "the closed work order keeps it"),
                    () -> assertEquals(1, count("SELECT count(*) FROM workflow WHERE id = ?", workflowId),
                            "the workflow is kept"),
                    () -> assertEquals(1, count("SELECT count(*) FROM webhook_endpoint_work_order_categories "
                            + "WHERE webhook_endpoint_id = ?", endpoint.getId()), "the filter keeps it"),
                    () -> assertEquals(0, auditRecords(data), "no audit record"));
        }

        @Test
        void technicianOnCategoryOfAnother_403_exists() {
            CommonData data = data();
            WorkOrderCategory category = workOrderCategory(data, data.o1().admin());

            Response response = api.call(delete("/work-order-categories/" + category.getId()),
                    api.login(data.o1().technician()));

            assertEquals(403, response.status(), response.toString());
            assertEquals(1, count("SELECT count(*) FROM work_order_category WHERE id = ?", category.getId()));
        }

        @Test
        void otherOrganization_403_exists() {
            CommonData data = data();
            WorkOrderCategory category = workOrderCategory(data, data.o1().admin());

            Response response = api.call(delete("/work-order-categories/" + category.getId()),
                    api.login(data.o2().admin()));

            assertEquals(403, response.status(), response.toString());
            assertEquals(1, count("SELECT count(*) FROM work_order_category WHERE id = ?", category.getId()));
        }

        @Test
        void missing_404() {
            CommonData data = data();

            Response response = api.call(delete("/work-order-categories/" + Long.MAX_VALUE),
                    api.login(data.o1().admin()));

            assertEquals(404, response.status(), response.toString());
        }
    }

    @Nested
    class CostAndTimeCategories {

        @Test
        void costCategoryOfCostOfClosedWorkOrder_409_categoryKept() {
            CommonData data = data();
            Fixture fixture = workOrders.create(data.o1().admin(), data.o1().technician(), Status.OPEN, false);
            CostCategory category = costCategory(data, data.o1().admin());
            jdbc.update("UPDATE additional_cost SET category_id = ? WHERE id = ?", category.getId(), fixture.cost());
            workOrders.moveTo(fixture.id(), Status.COMPLETE, false);

            Response response = api.call(delete("/cost-categories/" + category.getId()),
                    api.login(data.o1().admin()));

            // P-12b: before, 200 with the category nulled
            assertEquals(409, response.status(), response.toString());
            assertTrue(response.body().contains("COST_CATEGORY_IN_USE"), response.body());
            assertEquals(category.getId(), jdbc.queryForObject("SELECT category_id FROM additional_cost WHERE id = ?",
                    Long.class, fixture.cost()));
            assertEquals(0, auditRecords(data));
        }

        @Test
        void timeCategoryOfLaborOfClosedWorkOrder_409_categoryKept() {
            CommonData data = data();
            Fixture fixture = workOrders.create(data.o1().admin(), data.o1().technician(), Status.OPEN, false);
            TimeCategory category = timeCategory(data, data.o1().admin());
            jdbc.update("UPDATE labor SET time_category_id = ? WHERE id = ?", category.getId(), fixture.labor());
            workOrders.moveTo(fixture.id(), Status.COMPLETE, false);

            Response response = api.call(delete("/time-categories/" + category.getId()),
                    api.login(data.o1().admin()));

            // P-12b: before, 200 with the category nulled
            assertEquals(409, response.status(), response.toString());
            assertTrue(response.body().contains("TIME_CATEGORY_IN_USE"), response.body());
            assertEquals(category.getId(), jdbc.queryForObject("SELECT time_category_id FROM labor WHERE id = ?",
                    Long.class, fixture.labor()));
            assertEquals(0, auditRecords(data));
        }

        @Test
        void withoutCreator_technicianDeletesBoth_200() {
            // A-6 of the policy (accepted debt): kept as it is
            CommonData data = data();
            CostCategory cost = costCategory(data, null);
            TimeCategory time = timeCategory(data, null);
            String technician = api.login(data.o1().technician());

            assertEquals(200, api.call(delete("/cost-categories/" + cost.getId()), technician).status());
            assertEquals(200, api.call(delete("/time-categories/" + time.getId()), technician).status());
        }

        @Test
        void technicianOnCategoriesOfAnother_403_exist() {
            CommonData data = data();
            CostCategory cost = costCategory(data, data.o1().admin());
            TimeCategory time = timeCategory(data, data.o1().admin());
            String technician = api.login(data.o1().technician());

            assertEquals(403, api.call(delete("/cost-categories/" + cost.getId()), technician).status());
            assertEquals(403, api.call(delete("/time-categories/" + time.getId()), technician).status());
            assertEquals(1, count("SELECT count(*) FROM cost_category WHERE id = ?", cost.getId()));
            assertEquals(1, count("SELECT count(*) FROM time_category WHERE id = ?", time.getId()));
        }

        @Test
        void otherOrganization_403_exist() {
            CommonData data = data();
            CostCategory cost = costCategory(data, data.o1().admin());
            TimeCategory time = timeCategory(data, data.o1().admin());
            String other = api.login(data.o2().admin());

            assertEquals(403, api.call(delete("/cost-categories/" + cost.getId()), other).status());
            assertEquals(403, api.call(delete("/time-categories/" + time.getId()), other).status());
            assertEquals(1, count("SELECT count(*) FROM cost_category WHERE id = ?", cost.getId()));
            assertEquals(1, count("SELECT count(*) FROM time_category WHERE id = ?", time.getId()));
        }

        @Test
        void missing_404() {
            CommonData data = data();
            String admin = api.login(data.o1().admin());

            assertEquals(404, api.call(delete("/cost-categories/" + Long.MAX_VALUE), admin).status());
            assertEquals(404, api.call(delete("/time-categories/" + Long.MAX_VALUE), admin).status());
        }
    }

    @Nested
    class Customers {

        @Test
        void associatedWithClosedWorkOrder_200_associationRemoved() {
            CommonData data = data();
            Customer customer = customer(data);
            WorkOrder closed = records.workOrder(data.o1().company(), null, Status.COMPLETE, false);
            jdbc.update("INSERT INTO work_order_customers (work_order_id, customers_id) VALUES (?, ?)",
                    closed.getId(), customer.getId());

            Response response = api.call(delete("/customers/" + customer.getId()), api.login(data.o1().admin()));

            assertEquals(200, response.status(), response.toString());
            assertEquals(DELETED_BODY, response.body());
            assertEquals(0, count("SELECT count(*) FROM work_order_customers WHERE work_order_id = ?",
                    closed.getId()));
            assertEquals(0, auditRecords(data));
        }

        @Test
        void associatedWithOpenWorkOrder_200_associationRemoved() {
            CommonData data = data();
            Customer customer = customer(data);
            WorkOrder open = records.workOrder(data.o1().company(), null, Status.OPEN, false);
            jdbc.update("INSERT INTO work_order_customers (work_order_id, customers_id) VALUES (?, ?)",
                    open.getId(), customer.getId());

            Response response = api.call(delete("/customers/" + customer.getId()), api.login(data.o1().admin()));

            assertEquals(200, response.status(), response.toString());
            assertEquals(0, count("SELECT count(*) FROM work_order_customers WHERE work_order_id = ?",
                    open.getId()));
            assertEquals(1, count("SELECT count(*) FROM work_order WHERE id = ?", open.getId()));
        }

        @Test
        void technicianOnCustomerOfAnother_403_exists() {
            CommonData data = data();
            Customer customer = customer(data);

            Response response = api.call(delete("/customers/" + customer.getId()),
                    api.login(data.o1().technician()));

            assertEquals(403, response.status(), response.toString());
            assertEquals(1, count("SELECT count(*) FROM customer WHERE id = ?", customer.getId()));
        }

        @Test
        void otherOrganization_403_exists() {
            CommonData data = data();
            Customer customer = customer(data);

            Response response = api.call(delete("/customers/" + customer.getId()), api.login(data.o2().admin()));

            assertEquals(403, response.status(), response.toString());
            assertEquals(1, count("SELECT count(*) FROM customer WHERE id = ?", customer.getId()));
        }

        @Test
        void missing_404() {
            CommonData data = data();

            Response response = api.call(delete("/customers/" + Long.MAX_VALUE), api.login(data.o1().admin()));

            assertEquals(404, response.status(), response.toString());
        }
    }
}
