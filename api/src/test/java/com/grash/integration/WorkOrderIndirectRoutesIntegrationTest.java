package com.grash.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.grash.dto.imports.ImportRejection;
import com.grash.dto.imports.ImportResponse;
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
        void h1_01_importedRowOfClosedWorkOrder_isRejected_notReopened() {
            // D-P12c-1: the audit expected a reopening with its REOPEN record; PPB-20 b rejects the row instead
            CommonData data = data();
            WorkOrder order = records.workOrder(data.o1().company(), null, Status.COMPLETE, false);

            ImportResponse response = importService.importWorkOrders(List.of(WorkOrderImportDTO.builder()
                    .id(order.getId()).title("H1 import").status("Open").build()), data.o1().company(),
                    data.o1().admin());

            int events = jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE company_id = ? AND entity_id = ? "
                    + "AND action = 'REOPEN'", Integer.class, data.o1().company().getId(), order.getId());
            assertEquals(Status.COMPLETE, status(order.getId()));
            assertEquals(0, events);
            assertEquals(List.of(order.getId()), response.getRejected().stream().map(ImportRejection::getId)
                    .toList());
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

    // =====================================================================================================
    // CA-P12C-03: moving a relation to another work order (H1-05)
    // =====================================================================================================

    @Nested
    class MovedRelation {

        private Map<String, Object> relation(Long id) {
            return jdbc.queryForMap("SELECT * FROM relation WHERE id = ?", id);
        }

        private Response move(String token, Long relation, Long parent, Long child) {
            return workOrders.call(patch("/relations/" + relation), token, Map.of("parent", Map.of("id", parent),
                    "child", Map.of("id", child)));
        }

        @Test
        void toClosedWorkOrder_409Closed_relationUnchanged() {
            CommonData data = data();
            Fixture f = workOrders.create(data.o1().admin(), data.o1().technician(), Status.OPEN, false);
            WorkOrder closed = records.workOrder(data.o1().company(), null, Status.COMPLETE, false);
            Map<String, Object> before = relation(f.relation());

            assertConflict(move(api.login(data.o1().admin()), f.relation(), closed.getId(), f.relatedWorkOrder()),
                    CLOSED);
            assertEquals(before, relation(f.relation()));
        }

        @Test
        void toArchivedWorkOrder_409Retired_relationUnchanged() {
            CommonData data = data();
            Fixture f = workOrders.create(data.o1().admin(), data.o1().technician(), Status.OPEN, false);
            WorkOrder archived = records.workOrder(data.o1().company(), null, Status.OPEN, false);
            workOrders.moveTo(archived.getId(), Status.OPEN, true);
            Map<String, Object> before = relation(f.relation());

            assertConflict(move(api.login(data.o1().admin()), f.relation(), archived.getId(),
                    f.relatedWorkOrder()), RETIRED);
            assertEquals(before, relation(f.relation()));
        }

        @Test
        void outOfClosedWorkOrder_409Closed_relationUnchanged() {
            CommonData data = data();
            Fixture f = workOrders.create(data.o1().admin(), data.o1().technician(), Status.COMPLETE, false);
            WorkOrder open = records.workOrder(data.o1().company(), null, Status.OPEN, false);
            Map<String, Object> before = relation(f.relation());

            assertConflict(move(api.login(data.o1().admin()), f.relation(), open.getId(), f.relatedWorkOrder()),
                    CLOSED);
            assertEquals(before, relation(f.relation()));
        }

        @Test
        void betweenOpenWorkOrders_200_moved() {
            CommonData data = data();
            Fixture f = workOrders.create(data.o1().admin(), data.o1().technician(), Status.OPEN, false);
            WorkOrder open = records.workOrder(data.o1().company(), null, Status.OPEN, false);

            Response response = move(api.login(data.o1().admin()), f.relation(), open.getId(),
                    f.relatedWorkOrder());

            assertEquals(200, response.status(), response.toString());
            assertEquals(open.getId(), relation(f.relation()).get("parent_id"));
        }
    }

    // =====================================================================================================
    // CA-P12C-04: import (H1-01, PPB-20 b)
    // =====================================================================================================

    @Nested
    class Import {

        private WorkOrderImportDTO row(Long id, String title) {
            return WorkOrderImportDTO.builder().id(id).title(title).status("Open").build();
        }

        @Test
        void closedAndArchivedRowsRejected_openUpdated_newCreated() {
            CommonData data = data();
            Fixture closed = workOrders.create(data.o1().admin(), data.o1().technician(), Status.COMPLETE, false);
            WorkOrder archived = records.workOrder(data.o1().company(), null, Status.OPEN, false);
            workOrders.moveTo(archived.getId(), Status.OPEN, true);
            WorkOrder open = records.workOrder(data.o1().company(), null, Status.OPEN, false);
            String newTitle = TestRecords.unique("P-12c new");
            Map<String, Object> closedBefore = rows(closed.id());
            Map<String, Object> archivedBefore = rows(archived.getId());
            long records = auditRecords(data);

            ImportResponse response = importService.importWorkOrders(List.of(row(closed.id(), "P-12c closed"),
                    row(archived.getId(), "P-12c archived"), row(open.getId(), "P-12c open"), row(null, newTitle)),
                    data.o1().company(), data.o1().admin());

            assertEquals(closedBefore, rows(closed.id()));
            assertEquals(archivedBefore, rows(archived.getId()));
            assertEquals(records, auditRecords(data), "no reopening nor any other record");
            assertEquals(1, response.getCreated());
            assertEquals(1, response.getUpdated());
            assertEquals(2, response.getRejected().size(), response.toString());
            ImportRejection closedRow = response.getRejected().get(0);
            ImportRejection archivedRow = response.getRejected().get(1);
            assertEquals(List.of(1, closed.id(), CLOSED), List.of(closedRow.getRow(), closedRow.getId(),
                    closedRow.getCode()));
            assertEquals(List.of(2, archived.getId(), RETIRED), List.of(archivedRow.getRow(), archivedRow.getId(),
                    archivedRow.getCode()));
            assertTrue(closedRow.getMessage().startsWith("This work order is complete"), closedRow.getMessage());
            assertTrue(archivedRow.getMessage().startsWith("This work order is archived"),
                    archivedRow.getMessage());
            assertEquals("P-12c open", jdbc.queryForObject("SELECT title FROM work_order WHERE id = ?",
                    String.class, open.getId()));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM work_order WHERE company_id = ? AND title = ?",
                    Integer.class, data.o1().company().getId(), newTitle));
        }

        @Test
        void existingOpenRow_archivedColumn_doesNotArchive() {
            // D-P12c-4: the import neither archives nor restores an existing work order
            CommonData data = data();
            WorkOrder open = records.workOrder(data.o1().company(), null, Status.OPEN, false);

            ImportResponse response = importService.importWorkOrders(List.of(WorkOrderImportDTO.builder()
                    .id(open.getId()).title("P-12c open").status("Open").archived("Yes").build()),
                    data.o1().company(), data.o1().admin());

            assertEquals(1, response.getUpdated());
            assertEquals(List.of(), response.getRejected());
            assertEquals(false, jdbc.queryForObject("SELECT archived FROM work_order WHERE id = ?", Boolean.class,
                    open.getId()));
            assertEquals("P-12c open", jdbc.queryForObject("SELECT title FROM work_order WHERE id = ?",
                    String.class, open.getId()));
        }
    }
}
