package com.grash.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.grash.dto.imports.WorkOrderImportDTO;
import com.grash.integration.support.TestApi;
import com.grash.integration.support.TestApi.Response;
import com.grash.integration.support.TestOrganizations;
import com.grash.integration.support.TestOrganizations.CommonData;
import com.grash.integration.support.TestRecords;
import com.grash.integration.support.TestWorkOrders;
import com.grash.integration.support.TestWorkOrders.Fixture;
import com.grash.model.User;
import com.grash.model.Workflow;
import com.grash.model.WorkflowAction;
import com.grash.model.WorkOrder;
import com.grash.model.enums.Status;
import com.grash.model.enums.workflow.WFMainCondition;
import com.grash.model.enums.workflow.WorkOrderAction;
import com.grash.repository.FileRepository;
import com.grash.repository.PartRepository;
import com.grash.repository.WorkOrderRepository;
import com.grash.repository.WorkflowRepository;
import com.grash.service.ImportService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

/**
 * P-12c: the routes that, according to the independent audit H-1, still changed a closed or archived work order
 * (workload planning, replacements without difference, moving a relation, import and automations).
 */
@AutoConfigureMockMvc
class WorkOrderIndirectRoutesIntegrationTest extends AbstractIntegrationTest {

    private final TestOrganizations organizations;
    private final TestRecords records;
    private final TestApi api;
    private final TestWorkOrders workOrders;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;
    private final WorkflowRepository workflowRepository;
    private final ImportService importService;
    private final List<CommonData> created = new ArrayList<>();

    @Autowired
    WorkOrderIndirectRoutesIntegrationTest(TestOrganizations organizations, TestRecords records, MockMvc mockMvc,
                                           ObjectMapper objectMapper, JdbcTemplate jdbc,
                                           WorkOrderRepository workOrderRepository, PartRepository partRepository,
                                           FileRepository fileRepository, WorkflowRepository workflowRepository,
                                           ImportService importService) {
        this.organizations = organizations;
        this.records = records;
        this.api = new TestApi(mockMvc, objectMapper);
        this.workOrders = new TestWorkOrders(api, objectMapper, records, workOrderRepository, partRepository,
                fileRepository);
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
        this.workflowRepository = workflowRepository;
        this.importService = importService;
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

    private static final String CLOSED = "WORK_ORDER_CLOSED";
    private static final String RETIRED = "WORK_ORDER_RETIRED";

    private JsonNode json(Response response) {
        try {
            return objectMapper.readTree(response.body());
        } catch (Exception e) {
            throw new AssertionError("not JSON: " + response, e);
        }
    }

    private void assertConflict(Response response, String code) {
        assertEquals(409, response.status(), response.toString());
        JsonNode body = json(response);
        assertFalse(body.get("success").asBoolean(), response.toString());
        assertEquals(code, body.get("code").asText(), response.toString());
        assertFalse(body.get("message").asText().isBlank(), response.toString());
    }

    private long auditRecords(CommonData data) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE company_id = ?", Long.class,
                data.o1().company().getId());
    }

    private Map<String, Object> rows(Long id) {
        return TestWorkOrders.rows(jdbc, id, null);
    }

    private void disable(User user) {
        jdbc.update("UPDATE own_user SET enabled = false WHERE id = ?", user.getId());
    }

    private Status status(Long id) {
        return Status.values()[jdbc.queryForObject("SELECT status FROM work_order WHERE id = ?", Integer.class, id)];
    }

    // =====================================================================================================
    // The five cases of the audit (fierabras/pruebas/H-1/H1PersistenceIntegrationTest.java), with their original
    // assertions
    // =====================================================================================================

    @Nested
    class Audit {

        @Test
        void h1_01_importedReopeningHasAuditRecord() {
            CommonData data = data();
            WorkOrder order = records.workOrder(data.o1().company(), null, Status.COMPLETE, false);

            importService.importWorkOrders(List.of(WorkOrderImportDTO.builder().id(order.getId()).title("H1 import")
                    .status("Open").build()), data.o1().company(), data.o1().admin());

            int events = jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE company_id = ? AND entity_id = ? "
                    + "AND action = 'REOPEN'", Integer.class, data.o1().company().getId(), order.getId());
            assertEquals(Status.OPEN, status(order.getId()));
            assertEquals(1, events);
        }

        @Test
        void h1_03_archivedWorkOrderKeepsSchedule() {
            CommonData data = data();
            String admin = api.login(data.o1().admin());
            WorkOrder order = records.workOrder(data.o1().company(), null, Status.OPEN, false);
            workOrders.moveTo(order.getId(), Status.OPEN, true);

            Response result = workOrders.call(patch("/workload/work-orders/" + order.getId() + "/schedule"), admin,
                    Map.of("primaryUserId", data.o1().technician().getId()));

            boolean changed = Boolean.TRUE.equals(jdbc.queryForObject("SELECT primary_user_id IS NOT NULL FROM "
                    + "work_order WHERE id = ? AND archived", Boolean.class, order.getId()));
            assertEquals(409, result.status(), result.toString());
            assertFalse(changed);
        }

        @Test
        void h1_04_identicalPartsPreserveArchivedOrder() {
            CommonData data = data();
            String admin = api.login(data.o1().admin());
            WorkOrder order = records.workOrder(data.o1().company(), null, Status.OPEN, false);
            workOrders.moveTo(order.getId(), Status.OPEN, true);
            String sql = "SELECT first_time_to_react FROM work_order WHERE id = ?";
            assertNull(jdbc.queryForObject(sql, Timestamp.class, order.getId()));

            Response result = workOrders.call(patch("/part-quantities/work-order/" + order.getId()), admin,
                    List.of());

            assertEquals(200, result.status(), result.toString());
            assertNull(jdbc.queryForObject(sql, Timestamp.class, order.getId()), "reaction time changed");
        }

        @Test
        void h1_05_relationOwnerMustAdmitChildren() {
            CommonData data = data();
            String admin = api.login(data.o1().admin());
            Fixture f = workOrders.create(data.o1().admin(), data.o1().technician(), Status.OPEN, false);
            WorkOrder closed = records.workOrder(data.o1().company(), null, Status.COMPLETE, false);

            Response result = workOrders.call(patch("/relations/" + f.relation()), admin, Map.of(
                    "parent", Map.of("id", closed.getId()), "child", Map.of("id", f.relatedWorkOrder())));

            boolean moved = closed.getId().equals(jdbc.queryForObject("SELECT parent_id FROM relation WHERE id = ?",
                    Long.class, f.relation()));
            assertEquals(409, result.status(), result.toString());
            assertFalse(moved);
        }

        @Test
        void h1_12_archivedWorkflowPreservesAssignee() {
            CommonData data = data();
            String admin = api.login(data.o1().admin());
            WorkflowAction action = new WorkflowAction();
            action.setCompany(data.o1().company());
            action.setWorkOrderAction(WorkOrderAction.ASSIGN_USER);
            action.setUser(data.o1().technician());
            Workflow workflow = new Workflow();
            workflow.setCompany(data.o1().company());
            workflow.setTitle("H1 ordinary archive");
            workflow.setMainCondition(WFMainCondition.WORK_ORDER_ARCHIVED);
            workflow.setAction(action);
            workflowRepository.saveAndFlush(workflow);
            WorkOrder order = records.workOrder(data.o1().company(), null, Status.OPEN, false);
            ObjectNode shown = workOrders.shown(order.getId(), admin);
            shown.put("archived", true);

            Response result = workOrders.call(patch("/work-orders/" + order.getId()), admin, shown);

            Long assigned = jdbc.queryForObject("SELECT primary_user_id FROM work_order WHERE id = ? AND archived",
                    Long.class, order.getId());
            assertEquals(200, result.status(), result.toString());
            assertNull(assigned);
        }
    }

    // =====================================================================================================
    // CA-P12C-01: workload planning (H1-03)
    // =====================================================================================================

    @Nested
    class Planning {

        private Response schedule(String token, Long id, Map<String, Object> body) {
            return workOrders.call(patch("/workload/work-orders/" + id + "/schedule"), token, body);
        }

        @Test
        void archived_409Retired_rowIdentical_noRecord() {
            CommonData data = data();
            String admin = api.login(data.o1().admin());
            Fixture f = workOrders.create(data.o1().admin(), data.o1().technician(), Status.OPEN, true);
            Map<String, Object> before = rows(f.id());
            long records = auditRecords(data);

            Response assign = schedule(admin, f.id(), Map.of("primaryUserId", data.o1().admin().getId()));
            Response unschedule = schedule(admin, f.id(), new java.util.HashMap<>());

            assertConflict(assign, RETIRED);
            assertConflict(unschedule, RETIRED);
            assertEquals(before, rows(f.id()));
            assertEquals(records, auditRecords(data));
        }

        @Test
        void closed_200_ownFieldsChange() {
            CommonData data = data();
            String admin = api.login(data.o1().admin());
            WorkOrder closed = records.workOrder(data.o1().company(), null, Status.COMPLETE, false);

            Response response = schedule(admin, closed.getId(),
                    Map.of("primaryUserId", data.o1().technician().getId()));

            assertEquals(200, response.status(), response.toString());
            assertEquals(data.o1().technician().getId(), jdbc.queryForObject(
                    "SELECT primary_user_id FROM work_order WHERE id = ?", Long.class, closed.getId()));
        }

        @Test
        void disabledUser_409UserRetired_noChanges() {
            CommonData data = data();
            String admin = api.login(data.o1().admin());
            WorkOrder open = records.workOrder(data.o1().company(), null, Status.OPEN, false);
            disable(data.o1().technician());
            Map<String, Object> before = rows(open.getId());

            Response response = schedule(admin, open.getId(),
                    Map.of("primaryUserId", data.o1().technician().getId()));

            assertConflict(response, "USER_RETIRED");
            assertEquals(before, rows(open.getId()));
        }

        @Test
        void open_200_asToday() {
            CommonData data = data();
            String admin = api.login(data.o1().admin());
            WorkOrder open = records.workOrder(data.o1().company(), null, Status.OPEN, false);

            Response response = schedule(admin, open.getId(),
                    Map.of("primaryUserId", data.o1().technician().getId()));

            assertEquals(200, response.status(), response.toString());
            assertEquals(data.o1().technician().getId(), jdbc.queryForObject(
                    "SELECT primary_user_id FROM work_order WHERE id = ?", Long.class, open.getId()));
        }
    }

    // =====================================================================================================
    // CA-P12C-02: a replacement without difference writes nothing (H1-04)
    // =====================================================================================================

    @Nested
    class IdenticalReplacements {

        /**
         * Sends back the parts and the tasks the work order already has, as the web app does when it saves.
         */
        private void assertSameReplacementsChangeNothing(CommonData data, Long id) {
            String admin = api.login(data.o1().admin());
            List<Long> parts = jdbc.queryForList("SELECT part_id FROM part_quantity WHERE work_order_id = ?",
                    Long.class, id);
            List<Map<String, Object>> tasks = new ArrayList<>();
            json(api.call(get("/tasks/work-order/" + id), admin)).forEach(task -> tasks.add(Map.of(
                    "label", task.get("taskBase").get("label").asText(),
                    "taskType", task.get("taskBase").get("taskType").asText(),
                    "options", List.of())));
            jdbc.update("UPDATE work_order SET first_time_to_react = NULL WHERE id = ?", id);
            Map<String, Object> before = rows(id);

            Response partsResponse = workOrders.call(patch("/part-quantities/work-order/" + id), admin, parts);
            Response tasksResponse = workOrders.call(patch("/tasks/work-order/" + id), admin, tasks);

            assertEquals(200, partsResponse.status(), partsResponse.toString());
            assertEquals(200, tasksResponse.status(), tasksResponse.toString());
            assertEquals(before, rows(id));
        }

        @Test
        void closedAndArchived_withChildren_200_rowsIdentical() {
            CommonData data = data();
            for (boolean archived : List.of(false, true)) {
                Fixture f = workOrders.create(data.o1().admin(), data.o1().technician(),
                        archived ? Status.OPEN : Status.COMPLETE, archived);
                assertSameReplacementsChangeNothing(data, f.id());
            }
        }

        @Test
        void closedAndArchived_withoutChildren_200_rowsIdentical() {
            CommonData data = data();
            for (boolean archived : List.of(false, true)) {
                WorkOrder workOrder = records.workOrder(data.o1().company(), null,
                        archived ? Status.OPEN : Status.COMPLETE, false);
                workOrders.moveTo(workOrder.getId(), workOrder.getStatus(), archived);
                assertSameReplacementsChangeNothing(data, workOrder.getId());
            }
        }

        @Test
        void open_identicalReplacement_stillRecordsTheFirstReaction() {
            CommonData data = data();
            WorkOrder open = records.workOrder(data.o1().company(), null, Status.OPEN, false);

            Response response = workOrders.call(patch("/part-quantities/work-order/" + open.getId()),
                    api.login(data.o1().admin()), List.of());

            assertEquals(200, response.status(), response.toString());
            assertNotNull(jdbc.queryForObject("SELECT first_time_to_react FROM work_order WHERE id = ?",
                    Timestamp.class, open.getId()));
        }
    }
}
