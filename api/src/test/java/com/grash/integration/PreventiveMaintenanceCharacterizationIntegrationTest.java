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
import com.grash.model.Company;
import com.grash.model.User;
import com.grash.model.WorkOrder;
import com.grash.model.enums.Status;
import com.grash.repository.FileRepository;
import com.grash.repository.PartRepository;
import com.grash.repository.WorkOrderRepository;
import com.grash.service.DemoDataService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * P-15 characterization: fixes, before any change, how a preventive maintenance, its schedule, its tasks and the
 * checklists were deleted and changed: deletion with and without generated work orders and without schedule, what
 * happened to the jobs, editing a paused preventive maintenance, deleting a schedule, removing a task of a
 * preventive maintenance with a generated work order, a task definition deleted in the database, editing and
 * deleting a checklist used by a closed work order or cited by an automation, a disabled user assigned, the demo
 * purge in the normal case and the seven foreign keys of the package. The tests that show the behavior P-15 changes
 * are updated to its contract, as step 8 of the order allows.
 */
@AutoConfigureMockMvc
class PreventiveMaintenanceCharacterizationIntegrationTest extends AbstractIntegrationTest {

    private static final String FUTURE = "2099-01-01T00:00:00.000+00:00";

    private final TestOrganizations organizations;
    private final TestRecords records;
    private final TestWorkOrders workOrders;
    private final TestApi api;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;
    private final FaultInjector faults;
    private final Scheduler scheduler;
    private final DemoDataService demoDataService;
    private final List<CommonData> created = new ArrayList<>();

    @Autowired
    PreventiveMaintenanceCharacterizationIntegrationTest(TestOrganizations organizations, TestRecords records,
                                                         MockMvc mockMvc, ObjectMapper objectMapper,
                                                         JdbcTemplate jdbc, Scheduler scheduler,
                                                         DemoDataService demoDataService,
                                                         WorkOrderRepository workOrderRepository,
                                                         PartRepository partRepository,
                                                         FileRepository fileRepository) {
        this.organizations = organizations;
        this.records = records;
        this.api = new TestApi(mockMvc, objectMapper);
        this.workOrders = new TestWorkOrders(api, objectMapper, records, workOrderRepository, partRepository,
                fileRepository);
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
        this.faults = new FaultInjector(jdbc);
        this.scheduler = scheduler;
        this.demoDataService = demoDataService;
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

    // --- helpers ---

    private long count(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, Long.class, arguments);
    }

    private List<String> audit(CommonData data) {
        return jdbc.queryForList("SELECT action || ' ' || entity_type FROM audit_log WHERE company_id = ? "
                + "ORDER BY id", String.class, data.o1().company().getId());
    }

    private Response send(MockHttpServletRequestBuilder request, String body, User user) {
        return api.call(request.contentType(MediaType.APPLICATION_JSON).content(body), api.login(user));
    }

    private Response call(MockHttpServletRequestBuilder request, User user) {
        return api.call(request, api.login(user));
    }

    private JsonNode json(Response response) {
        try {
            return objectMapper.readTree(response.body());
        } catch (Exception e) {
            throw new AssertionError("not JSON: " + response, e);
        }
    }

    private static String ref(Long id) {
        return "{\"id\":" + id + "}";
    }

    private long preventiveMaintenance(User user, String extra) {
        String name = TestRecords.unique("P-15 PM");
        Response created = send(post("/preventive-maintenances"), "{\"name\":\"" + name + "\",\"title\":\"" + name
                + "\",\"frequency\":1,\"recurrenceType\":\"DAILY\",\"recurrenceBasedOn\":\"SCHEDULED_DATE\","
                + "\"startsOn\":\"" + FUTURE + "\"" + extra + "}", user);
        assertEquals(200, created.status(), created.toString());
        return json(created).get("id").asLong();
    }

    private Long scheduleOf(long preventiveMaintenance) {
        return jdbc.queryForObject("SELECT schedule_id FROM preventive_maintenance WHERE id = ?", Long.class,
                preventiveMaintenance);
    }

    private boolean jobExists(long schedule) throws SchedulerException {
        return scheduler.checkExists(new JobKey("wo-job-" + schedule, "wo-group"));
    }

    private boolean disabled(long schedule) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT disabled FROM schedule WHERE id = ?", Boolean.class,
                schedule));
    }

    private Response replaceTasks(String path, long id, String tasks, User user) {
        return send(patch("/tasks/" + path + "/" + id), tasks, user);
    }

    private static String task(String label) {
        return "{\"label\":\"" + label + "\",\"taskType\":\"SUBTASK\",\"options\":[]}";
    }

    private long generatedWorkOrder(long preventiveMaintenance, User user) {
        Response triggered = call(post("/preventive-maintenances/" + preventiveMaintenance + "/trigger-work-order"),
                user);
        assertEquals(200, triggered.status(), triggered.toString());
        return json(triggered).get("id").asLong();
    }

    private void close(long workOrder) {
        jdbc.update("UPDATE work_order SET status = ? WHERE id = ?", Status.COMPLETE.ordinal(), workOrder);
    }

    private List<Map<String, Object>> tasksOf(long workOrder) {
        return jdbc.queryForList("SELECT t.id, t.value, t.task_base_id, b.label, b.task_type, "
                + "(SELECT string_agg(o.label, ',' ORDER BY o.label) FROM task_option o WHERE o.task_base_id = b.id) "
                + "AS options FROM task t JOIN task_base b ON b.id = t.task_base_id WHERE t.work_order_id = ? "
                + "ORDER BY t.id", workOrder);
    }

    private void disable(User user) {
        jdbc.update("UPDATE own_user SET enabled = false WHERE id = ?", user.getId());
    }

    private long checklist(Company company, User user, String... labels) {
        Response created = send(post("/checklists"), checklistBody(company, labels), user);
        assertEquals(200, created.status(), created.toString());
        return json(created).get("id").asLong();
    }

    private static String checklistBody(Company company, String... labels) {
        List<String> tasks = new ArrayList<>();
        for (String label : labels)
            tasks.add("{\"label\":\"" + label + "\",\"taskType\":\"INSPECTION\",\"options\":[\"ok\",\"bad\"]}");
        return "{\"name\":\"" + TestRecords.unique("P-15 checklist") + "\",\"companySettings\":"
                + ref(company.getCompanySettings().getId()) + ",\"taskBases\":[" + String.join(",", tasks) + "]}";
    }

    // =====================================================================================================
    // Deleting a preventive maintenance (E-1, E-4, CA-PPB-098)
    // =====================================================================================================

    @Nested
    class Deletion {

        @Test
        void withoutWorkOrders_200_scheduleAndTasksGone_jobsStopped_recorded() throws SchedulerException {
            CommonData data = data();
            long pm = preventiveMaintenance(data.o1().admin(), "");
            long schedule = scheduleOf(pm);
            assertEquals(200, replaceTasks("preventive-maintenance", pm, "[" + task("P-15 step") + "]",
                    data.o1().admin()).status());
            boolean jobBefore = jobExists(schedule);

            Response response = call(delete("/preventive-maintenances/" + pm), data.o1().admin());

            assertAll(
                    () -> assertTrue(jobBefore),
                    () -> assertEquals(200, response.status(), response.toString()),
                    () -> assertEquals(0, count("SELECT count(*) FROM preventive_maintenance WHERE id = ?", pm)),
                    () -> assertEquals(0, count("SELECT count(*) FROM schedule WHERE id = ?", schedule)),
                    () -> assertEquals(0, count("SELECT count(*) FROM task WHERE preventive_maintenance_id = ?", pm)),
                    () -> assertFalse(jobExists(schedule)),
                    // P-15 (E-1): before, no audit record
                    () -> assertEquals(List.of("DELETE PREVENTIVE_MAINTENANCE"), audit(data)));
        }

        @Test
        void ca098_withAGeneratedWorkOrder_409_workOrderKeepsItsLink() {
            CommonData data = data();
            long pm = preventiveMaintenance(data.o1().admin(), "");
            long workOrder = generatedWorkOrder(pm, data.o1().admin());

            Response response = call(delete("/preventive-maintenances/" + pm), data.o1().admin());

            // P-15 (CA-PPB-098, E-9): before, 200; it was deleted and the work order lost its link (SET NULL)
            assertAll(
                    () -> assertEquals(409, response.status(), response.toString()),
                    () -> assertEquals(1, count("SELECT count(*) FROM preventive_maintenance WHERE id = ?", pm)),
                    () -> assertEquals(1, count("SELECT count(*) FROM work_order WHERE id = ? "
                            + "AND parent_preventive_maintenance_id = ?", workOrder, pm)));
        }

        @Test
        void a2_withoutSchedule_200_deleted() {
            CommonData data = data();
            long pm = preventiveMaintenance(data.o1().admin(), "");
            long schedule = scheduleOf(pm);
            jdbc.update("UPDATE preventive_maintenance SET schedule_id = NULL WHERE id = ?", pm);
            jdbc.update("DELETE FROM schedule WHERE id = ?", schedule);

            Response response = call(delete("/preventive-maintenances/" + pm), data.o1().admin());

            // P-15 (E-4, A-2): before, a NullPointerException on the schedule (500)
            assertAll(
                    () -> assertEquals(200, response.status(), response.toString()),
                    () -> assertEquals(0, count("SELECT count(*) FROM preventive_maintenance WHERE id = ?", pm)));
        }

        @Test
        void tdP1706_deletionFails_preventiveMaintenanceKept_withItsJobs() throws SchedulerException {
            CommonData data = data();
            long pm = preventiveMaintenance(data.o1().admin(), "");
            long schedule = scheduleOf(pm);

            Response response;
            try (FaultInjector.Fault ignored = faults.failOn("preventive_maintenance", Operation.DELETE)) {
                response = call(delete("/preventive-maintenances/" + pm), data.o1().admin());
            }

            // P-15 (TD-P17-06): before, the jobs were stopped before the deletion, which then failed
            Response failed = response;
            assertAll(
                    () -> assertNotEquals(200, failed.status(), failed.toString()),
                    () -> assertEquals(1, count("SELECT count(*) FROM preventive_maintenance WHERE id = ?", pm)),
                    () -> assertTrue(jobExists(schedule)));
        }
    }

    // =====================================================================================================
    // Pausing (E-2, CA-PPB-097)
    // =====================================================================================================

    @Nested
    class Pausing {

        @Test
        void ca097_aPausedOneEdited_staysPaused() {
            CommonData data = data();
            long pm = preventiveMaintenance(data.o1().admin(), "");
            long schedule = scheduleOf(pm);
            jdbc.update("UPDATE schedule SET disabled = true WHERE id = ?", schedule);

            Response edit = send(patch("/preventive-maintenances/" + pm), "{\"title\":\"P-15 edited\"}",
                    data.o1().admin());

            // P-15 (E-2, PPB-07): before, any edit reactivated it
            assertAll(
                    () -> assertEquals(200, edit.status(), edit.toString()),
                    () -> assertTrue(disabled(schedule)));
        }

        @Test
        void ca097_theUserPauses_onlyDisabledChanges_recorded() {
            CommonData data = data();
            long pm = preventiveMaintenance(data.o1().admin(), "");
            long schedule = scheduleOf(pm);

            Response edit = send(patch("/preventive-maintenances/" + pm), "{\"title\":\"P-15\",\"disabled\":true}",
                    data.o1().admin());

            // P-15 (E-2, D-1): before, neither the preventive maintenance nor the schedule accepted disabled
            assertAll(
                    () -> assertEquals(200, edit.status(), edit.toString()),
                    () -> assertTrue(disabled(schedule)),
                    () -> assertEquals(List.of("RETIRE PREVENTIVE_MAINTENANCE"), audit(data)));
        }

        @Test
        void theSystemPausesAfterFiveWorkOrdersWithoutReaction_recorded() {
            CommonData data = data();
            long pm = preventiveMaintenance(data.o1().admin(), "");
            long schedule = scheduleOf(pm);
            for (int i = 0; i < 5; i++) generatedWorkOrder(pm, data.o1().admin());

            Response edit = send(patch("/schedules/" + schedule), "{\"frequency\":2,\"recurrenceType\":\"DAILY\","
                    + "\"recurrenceBasedOn\":\"SCHEDULED_DATE\",\"startsOn\":\"" + FUTURE + "\"}", data.o1().admin());

            // P-15 (E-2): before, the system paused it without a record
            assertAll(
                    () -> assertEquals(200, edit.status(), edit.toString()),
                    () -> assertTrue(disabled(schedule)),
                    () -> assertEquals(List.of("RETIRE PREVENTIVE_MAINTENANCE"), audit(data)));
        }
    }

    // =====================================================================================================
    // Deleting a schedule (E-3, CA-PPB-039)
    // =====================================================================================================

    @Nested
    class Schedules {

        @Test
        void ca039_deleteSchedule_409_scheduleAndItsJobsKept() throws SchedulerException {
            CommonData data = data();
            long pm = preventiveMaintenance(data.o1().admin(), "");
            long schedule = scheduleOf(pm);

            Response response = call(delete("/schedules/" + schedule), data.o1().admin());

            // P-15 (CA-PPB-039): before, 200 and the jobs were stopped, but the schedule stayed (the preventive
            // maintenance cascades to it, so Hibernate cancels the deletion): it silently generated nothing more
            assertAll(
                    () -> assertEquals(409, response.status(), response.toString()),
                    () -> assertEquals(1, count("SELECT count(*) FROM schedule WHERE id = ?", schedule)),
                    () -> assertEquals(1, count("SELECT count(*) FROM preventive_maintenance WHERE id = ? "
                            + "AND schedule_id = ?", pm, schedule)),
                    () -> assertTrue(jobExists(schedule)));
        }
    }

    // =====================================================================================================
    // Tasks and task definitions (E-5, E-6, CA-PPB-047, 048)
    // =====================================================================================================

    @Nested
    class Tasks {

        @Test
        void ca048_taskOfThePreventiveMaintenanceRemoved_theGeneratedTaskKeepsItsDefinition_recorded() {
            CommonData data = data();
            long pm = preventiveMaintenance(data.o1().admin(), "");
            assertEquals(200, replaceTasks("preventive-maintenance", pm, "[" + task("P-15 shared") + "]",
                    data.o1().admin()).status());
            long workOrder = generatedWorkOrder(pm, data.o1().admin());
            List<Map<String, Object>> before = tasksOf(workOrder);

            Response removed = replaceTasks("preventive-maintenance", pm, "[]", data.o1().admin());

            assertAll(
                    () -> assertEquals(1, before.size()),
                    () -> assertEquals(200, removed.status(), removed.toString()),
                    () -> assertEquals(0, count("SELECT count(*) FROM task WHERE preventive_maintenance_id = ?", pm)),
                    () -> assertEquals(before, tasksOf(workOrder)),
                    // P-15 (E-5): before, removing a task of a preventive maintenance left no record
                    () -> assertEquals(List.of("DELETE TASK"), audit(data)));
        }

        @Test
        void ca048_sharedDefinitionDeletedInTheDatabase_rejected_theTasksKeepIt() {
            CommonData data = data();
            long pm = preventiveMaintenance(data.o1().admin(), "");
            assertEquals(200, replaceTasks("preventive-maintenance", pm, "[" + task("P-15 shared") + "]",
                    data.o1().admin()).status());
            long workOrder = generatedWorkOrder(pm, data.o1().admin());
            Long taskBase = (Long) tasksOf(workOrder).get(0).get("task_base_id");

            // P-15 (CA-PPB-048, E-9): before, task.task_base_id was CASCADE: both tasks went with it
            assertThrows(DataIntegrityViolationException.class,
                    () -> jdbc.update("DELETE FROM task_base WHERE id = ?", taskBase));
            assertEquals(2, count("SELECT count(*) FROM task WHERE task_base_id = ?", taskBase));
        }

        @Test
        void ca047_v3_assetCitedByTheDefinitionOfATaskOfAClosedWorkOrder_409AssetInUse() {
            CommonData data = data();
            Asset asset = records.asset(data.o1().company());
            WorkOrder workOrder = records.workOrder(data.o1().company(), null, Status.OPEN, false);
            assertEquals(200, replaceTasks("work-order", workOrder.getId(), "[{\"label\":\"P-15 on asset\","
                    + "\"taskType\":\"SUBTASK\",\"options\":[],\"asset\":" + ref(asset.getId()) + "}]",
                    data.o1().admin()).status());
            close(workOrder.getId());
            List<Map<String, Object>> before = tasksOf(workOrder.getId());

            Response response = call(delete("/assets/" + asset.getId()), data.o1().admin());

            // already so since P-14 (task_base.asset_id NO ACTION)
            assertAll(
                    () -> assertEquals(409, response.status(), response.toString()),
                    () -> assertTrue(response.body().contains("ASSET_IN_USE"), response.toString()),
                    () -> assertEquals(before, tasksOf(workOrder.getId())));
        }

        @Test
        void tdP1707_replacingTheTasksFailsHalfway_nothingStays() {
            CommonData data = data();
            WorkOrder workOrder = records.workOrder(data.o1().company(), null, Status.OPEN, false);
            assertEquals(200, replaceTasks("work-order", workOrder.getId(), "[" + task("P-15 old") + "]",
                    data.o1().admin()).status());
            long pm = preventiveMaintenance(data.o1().admin(), "");
            assertEquals(200, replaceTasks("preventive-maintenance", pm, "[" + task("P-15 old") + "]",
                    data.o1().admin()).status());

            Response onWorkOrder;
            try (FaultInjector.Fault ignored = faults.failOn("task", Operation.DELETE)) {
                onWorkOrder = replaceTasks("work-order", workOrder.getId(), "[" + task("P-15 new") + "]",
                        data.o1().admin());
            }
            Response onPreventiveMaintenance;
            try (FaultInjector.Fault ignored = faults.failOn("task", Operation.DELETE)) {
                onPreventiveMaintenance = replaceTasks("preventive-maintenance", pm, "[" + task("P-15 new") + "]",
                        data.o1().admin());
            }

            // TD-P17-07 does not hold: TaskController is @Transactional at class level (c365ba4f, upstream), so the
            // new task of a replacement that fails halfway is rolled back with it
            Response failedOnWorkOrder = onWorkOrder;
            Response failedOnPreventiveMaintenance = onPreventiveMaintenance;
            assertAll(
                    () -> assertNotEquals(200, failedOnWorkOrder.status(), failedOnWorkOrder.toString()),
                    () -> assertNotEquals(200, failedOnPreventiveMaintenance.status(),
                            failedOnPreventiveMaintenance.toString()),
                    () -> assertEquals(List.of("P-15 old"), jdbc.queryForList("SELECT b.label FROM task t "
                            + "JOIN task_base b ON b.id = t.task_base_id WHERE t.work_order_id = ?", String.class,
                            workOrder.getId())),
                    () -> assertEquals(List.of("P-15 old"), jdbc.queryForList("SELECT b.label FROM task t "
                            + "JOIN task_base b ON b.id = t.task_base_id WHERE t.preventive_maintenance_id = ?",
                            String.class, pm)));
        }

        @Test
        void tdP1703_removingATask_takesTheImageOutOfAClosedWorkOrderThatAlsoHasIt() {
            CommonData data = data();
            String admin = api.login(data.o1().admin());
            Fixture open = workOrders.create(data.o1().admin(), data.o1().technician(), Status.OPEN, false);
            Long image = upload(admin, open.task());
            WorkOrder closed = records.workOrder(data.o1().company(), null, Status.COMPLETE, false);
            jdbc.update("INSERT INTO work_order_files (work_order_id, files_id) VALUES (?, ?)", closed.getId(), image);

            Response response = api.call(delete("/tasks/" + open.task()), admin);

            // TD-P17-03: the association of the image with the closed work order goes in cascade (file.task_id)
            assertAll(
                    () -> assertEquals(200, response.status(), response.toString()),
                    () -> assertEquals(0, count("SELECT count(*) FROM work_order_files WHERE work_order_id = ?",
                            closed.getId())));
        }

        private Long upload(String token, Long taskId) {
            MockMultipartFile file = new MockMultipartFile("files", TestRecords.unique("p15") + ".txt",
                    MediaType.TEXT_PLAIN_VALUE, "P-15".getBytes(StandardCharsets.UTF_8));
            Response response = api.call(multipart("/files/upload").file(file).param("folder", "p15")
                    .param("hidden", "false").param("type", "IMAGE").param("taskId", taskId.toString()), token);
            assertTrue(response.is2xx(), "upload: " + response);
            return json(response).get(0).get("id").asLong();
        }
    }

    // =====================================================================================================
    // Checklists (E-7, CA-PPB-046)
    // =====================================================================================================

    @Nested
    class Checklists {

        @Test
        void ca046_usedForTheTasksOfAClosedWorkOrder_editedThenDeleted_200_tasksUnchanged_noAuditRecord() {
            CommonData data = data();
            Company company = data.o1().company();
            long checklist = checklist(company, data.o1().admin(), "P-15 first", "P-15 second");
            WorkOrder workOrder = records.workOrder(company, null, Status.OPEN, false);
            // the web copies the steps of the checklist into new tasks of the work order
            assertEquals(200, replaceTasks("work-order", workOrder.getId(), "[{\"label\":\"P-15 first\","
                    + "\"taskType\":\"INSPECTION\",\"options\":[\"ok\",\"bad\"]},{\"label\":\"P-15 second\","
                    + "\"taskType\":\"INSPECTION\",\"options\":[\"ok\",\"bad\"]}]", data.o1().admin()).status());
            close(workOrder.getId());
            List<Map<String, Object>> before = tasksOf(workOrder.getId());

            Response edit = send(patch("/checklists/" + checklist), checklistBody(company, "P-15 first"),
                    data.o1().admin());
            Response deletion = call(delete("/checklists/" + checklist), data.o1().admin());

            assertAll(
                    () -> assertEquals(200, edit.status(), edit.toString()),
                    () -> assertEquals(200, deletion.status(), deletion.toString()),
                    () -> assertEquals(0, count("SELECT count(*) FROM checklist WHERE id = ?", checklist)),
                    () -> assertEquals(before, tasksOf(workOrder.getId())),
                    // P-15 (E-7): before, deleting a checklist left no record
                    () -> assertEquals(List.of(), audit(data)));
        }

        @Test
        void citedByAnAutomation_409_theChecklistAndTheAutomationKept() {
            CommonData data = data();
            long checklist = checklist(data.o1().company(), data.o1().admin(), "P-15 step");
            Response workflow = send(post("/workflows"), "{\"title\":\"" + TestRecords.unique("P-15 workflow")
                    + "\",\"mainCondition\":\"WORK_ORDER_CREATED\",\"secondaryConditions\":[],\"action\":"
                    + "{\"workOrderAction\":\"ADD_CHECKLIST\",\"checklist\":" + ref(checklist) + "}}",
                    data.o1().admin());
            assertEquals(200, workflow.status(), workflow.toString());
            long workflowId = json(workflow).get("id").asLong();

            Response deletion = call(delete("/checklists/" + checklist), data.o1().admin());

            // P-15 (E-7, E-9): before, 200; the action went in cascade with the checklist, and the automation with it
            assertAll(
                    () -> assertEquals(409, deletion.status(), deletion.toString()),
                    () -> assertEquals(1, count("SELECT count(*) FROM checklist WHERE id = ?", checklist)),
                    () -> assertEquals(1, count("SELECT count(*) FROM workflow_action WHERE checklist_id = ?",
                            checklist)),
                    () -> assertEquals(1, count("SELECT count(*) FROM workflow WHERE id = ?", workflowId)));
        }
    }

    // =====================================================================================================
    // Disabled users (E-8)
    // =====================================================================================================

    @Nested
    class DisabledUsers {

        @Test
        void disabledUser_assignedToAPreventiveMaintenanceAndToTasks_200() {
            CommonData data = data();
            User retired = data.o1().technician();
            disable(retired);
            String user = ref(retired.getId());

            long pm = preventiveMaintenance(data.o1().admin(), ",\"primaryUser\":" + user + ",\"assignedTo\":["
                    + user + "]");
            long other = preventiveMaintenance(data.o1().admin(), "");
            Response edited = send(patch("/preventive-maintenances/" + other), "{\"title\":\"P-15\",\"primaryUser\":"
                    + user + "}", data.o1().admin());
            String withUser = "[{\"label\":\"P-15 by user\",\"taskType\":\"SUBTASK\",\"options\":[],\"user\":"
                    + user + "}]";
            Response pmTask = replaceTasks("preventive-maintenance", pm, withUser, data.o1().admin());
            WorkOrder workOrder = records.workOrder(data.o1().company(), null, Status.OPEN, false);
            Response woTask = replaceTasks("work-order", workOrder.getId(), withUser, data.o1().admin());
            Response checklist = send(post("/checklists"), "{\"name\":\"" + TestRecords.unique("P-15 checklist")
                    + "\",\"companySettings\":" + ref(data.o1().company().getCompanySettings().getId())
                    + ",\"taskBases\":" + withUser + "}", data.o1().admin());

            // P-15 (E-8, PPB-08): before, a disabled user was accepted everywhere
            assertAll(
                    () -> assertEquals(200, edited.status(), edited.toString()),
                    () -> assertEquals(200, pmTask.status(), pmTask.toString()),
                    () -> assertEquals(200, woTask.status(), woTask.toString()),
                    () -> assertEquals(200, checklist.status(), checklist.toString()),
                    () -> assertEquals(1, count("SELECT count(*) FROM preventive_maintenance_assigned_to "
                            + "WHERE preventive_maintenance_id = ?", pm)));
        }
    }

    // =====================================================================================================
    // Demo purge in the normal case
    // =====================================================================================================

    @Nested
    class DemoPurge {

        @Test
        void factoryDemoData_purged_200_preventiveMaintenancesAndSchedulesGone() throws InterruptedException {
            CommonData data = data();
            Long companyId = data.o1().company().getId();
            demoDataService.createDemoData(data.o1().admin(), data.o1().company());
            // createDemoData is asynchronous; the requests are created last
            for (int i = 0; i < 150 && count("SELECT count(*) FROM request WHERE company_id = ?", companyId) < 1; i++)
                Thread.sleep(200);
            long preventiveMaintenances = count("SELECT count(*) FROM preventive_maintenance WHERE company_id = ?",
                    companyId);

            Response response = call(delete("/demo/demo-data"), data.o1().admin());

            assertAll(
                    () -> assertTrue(preventiveMaintenances > 0),
                    () -> assertEquals(200, response.status(), response.toString()),
                    () -> assertEquals(0, count("SELECT count(*) FROM preventive_maintenance WHERE company_id = ?",
                            companyId)),
                    () -> assertEquals(0, count("SELECT count(*) FROM schedule s WHERE NOT EXISTS (SELECT 1 FROM "
                            + "preventive_maintenance p WHERE p.id = s.preventive_maintenance_id)")));
        }
    }

    // =====================================================================================================
    // The seven foreign keys of the package (E-9), as they are in the catalog
    // =====================================================================================================

    @Nested
    class Schema {

        @Test
        void e9_theSevenForeignKeys_currentRulesAndIndexes() {
            List<String> keys = jdbc.queryForList("""
                    SELECT c.conrelid::regclass::text || '.' || a.attname || ' -> ' || c.confrelid::regclass::text || ' '
                           || c.confdeltype::text || ' ' || c.conname::text || ' index=' || EXISTS (
                               SELECT 1 FROM pg_index i WHERE i.indrelid = c.conrelid AND i.indisvalid
                               AND i.indpred IS NULL AND i.indkey[0] = a.attnum)
                    FROM pg_constraint c
                    JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = c.conkey[1]
                    WHERE c.contype = 'f' AND (
                        (c.conrelid = 'work_order'::regclass AND a.attname = 'parent_preventive_maintenance_id')
                     OR (c.conrelid = 'work_order_aud'::regclass AND a.attname = 'parent_preventive_maintenance_id')
                     OR (c.conrelid = 'preventive_maintenance'::regclass AND a.attname = 'schedule_id')
                     OR (c.conrelid = 'task'::regclass AND a.attname = 'task_base_id')
                     OR (c.conrelid = 'workflow_action'::regclass AND a.attname = 'checklist_id')
                     OR (c.conrelid = 'custom_field_value'::regclass AND a.attname = 'preventive_maintenance_id')
                     OR (c.conrelid = 'task_option'::regclass AND a.attname = 'task_base_id'))
                    ORDER BY 1""", String.class);
            System.out.println("P-15 characterization, foreign keys: " + keys);

            // confdeltype: a NO ACTION, c CASCADE, n SET NULL. P-15 (E-9): before, custom_field_value a,
            // preventive_maintenance.schedule_id n, task c, task_option n, work_order n, work_order_aud n,
            // workflow_action c
            assertEquals(List.of(
                    "custom_field_value.preventive_maintenance_id -> preventive_maintenance c",
                    "preventive_maintenance.schedule_id -> schedule a",
                    "task.task_base_id -> task_base a",
                    "task_option.task_base_id -> task_base c",
                    "work_order.parent_preventive_maintenance_id -> preventive_maintenance a",
                    "work_order_aud.parent_preventive_maintenance_id -> preventive_maintenance a",
                    "workflow_action.checklist_id -> checklist a"),
                    keys.stream().map(key -> key.substring(0, key.lastIndexOf(' ', key.indexOf(" index=") - 1)))
                            .toList());
        }
    }
}
