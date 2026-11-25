package com.grash.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grash.integration.support.FaultInjector;
import com.grash.integration.support.TestApi;
import com.grash.integration.support.TestApi.Response;
import com.grash.integration.support.TestOrganizations;
import com.grash.integration.support.TestOrganizations.CommonData;
import com.grash.integration.support.TestRecords;
import com.grash.model.Company;
import com.grash.model.CustomField;
import com.grash.model.User;
import com.grash.model.enums.CustomFieldEntityType;
import com.grash.model.enums.CustomFieldType;
import com.grash.repository.CustomFieldRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import com.grash.job.WorkOrderCreationJob;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * P-15: deleting, pausing and resuming a preventive maintenance, its schedule and its tasks, the checklists and the
 * task definitions they leave in generated work orders (CA-PPB-039, 046, 047, 048, 097, 098; E-1 to E-9).
 */
@AutoConfigureMockMvc
class PreventiveMaintenanceIntegrationTest extends AbstractIntegrationTest {

    static final String FUTURE = "2099-01-01T00:00:00.000+00:00";

    private final TestOrganizations organizations;
    private final TestRecords records;
    private final TestApi api;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;
    private final FaultInjector faults;
    private final Scheduler scheduler;
    private final CustomFieldRepository customFieldRepository;
    private final WorkOrderCreationJob workOrderCreationJob;
    private final List<CommonData> created = new ArrayList<>();

    @Autowired
    PreventiveMaintenanceIntegrationTest(TestOrganizations organizations, TestRecords records, MockMvc mockMvc,
                                         ObjectMapper objectMapper, JdbcTemplate jdbc, Scheduler scheduler,
                                         CustomFieldRepository customFieldRepository,
                                         WorkOrderCreationJob workOrderCreationJob) {
        this.customFieldRepository = customFieldRepository;
        this.workOrderCreationJob = workOrderCreationJob;
        this.organizations = organizations;
        this.records = records;
        this.api = new TestApi(mockMvc, objectMapper);
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
        this.faults = new FaultInjector(jdbc);
        this.scheduler = scheduler;
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
        return jdbc.queryForList("SELECT action || ' ' || entity_type || ' ' || source || ' '"
                + " || coalesce(actor_id::text, '-') FROM audit_log WHERE company_id = ? ORDER BY id", String.class,
                data.o1().company().getId());
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

    private long scheduleOf(long preventiveMaintenance) {
        return jdbc.queryForObject("SELECT schedule_id FROM preventive_maintenance WHERE id = ?", Long.class,
                preventiveMaintenance);
    }

    private boolean jobExists(long schedule) throws SchedulerException {
        return scheduler.checkExists(new JobKey("wo-job-" + schedule, "wo-group"));
    }

    private Response replaceTasks(String path, long id, String tasks, User user) {
        return send(patch("/tasks/" + path + "/" + id), tasks, user);
    }

    private static String task(String label) {
        return "{\"label\":\"" + label + "\",\"taskType\":\"MULTIPLE\",\"options\":[\"yes\",\"no\"]}";
    }

    private long generatedWorkOrder(long preventiveMaintenance, User user) {
        Response triggered = call(post("/preventive-maintenances/" + preventiveMaintenance + "/trigger-work-order"),
                user);
        assertEquals(200, triggered.status(), triggered.toString());
        return json(triggered).get("id").asLong();
    }

    private List<Map<String, Object>> tasksOf(long workOrder) {
        return jdbc.queryForList("SELECT t.id, t.value, t.task_base_id, b.label, b.task_type, "
                + "(SELECT string_agg(o.label, ',' ORDER BY o.label) FROM task_option o WHERE o.task_base_id = b.id) "
                + "AS options FROM task t JOIN task_base b ON b.id = t.task_base_id WHERE t.work_order_id = ? "
                + "ORDER BY t.id", workOrder);
    }

    private long checklist(Company company, User user, String... labels) {
        Response created = send(post("/checklists"), checklistBody(company, labels), user);
        assertEquals(200, created.status(), created.toString());
        return json(created).get("id").asLong();
    }

    private static String checklistBody(Company company, String... labels) {
        List<String> tasks = new ArrayList<>();
        for (String label : labels) tasks.add(task(label));
        return "{\"name\":\"" + TestRecords.unique("P-15 checklist") + "\",\"companySettings\":"
                + ref(company.getCompanySettings().getId()) + ",\"taskBases\":[" + String.join(",", tasks) + "]}";
    }

    /**
     * A 409 with {@code code}, without SQL nor constraint names; returns its blocking types (empty without list).
     */
    private List<String> conflict(Response response, String code) {
        assertEquals(409, response.status(), response.toString());
        JsonNode body = json(response);
        assertAll(
                () -> assertFalse(body.get("success").asBoolean()),
                () -> assertEquals(code, body.get("code").asText(), response.toString()),
                () -> assertFalse(body.get("message").asText().isBlank()),
                () -> assertFalse(response.body().toLowerCase().contains("constraint"), response.toString()),
                () -> assertFalse(response.body().toLowerCase().contains("sql"), response.toString()));
        List<String> types = new ArrayList<>();
        if (body.hasNonNull("blockingTypes")) body.get("blockingTypes").forEach(type -> types.add(type.asText()));
        return types;
    }

    private String cascadeRows(CommonData data) {
        return jdbc.queryForObject("SELECT cascade_rows::text FROM audit_log WHERE company_id = ? ORDER BY id DESC "
                + "LIMIT 1", String.class, data.o1().company().getId());
    }

    private static String sqlState(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause())
            if (cause instanceof SQLException sql) return sql.getSQLState();
        return null;
    }

    // =====================================================================================================
    // B1 · The seven foreign keys (E-9, PPB-17, PPB-21, PPB-22) and CA-PPB-048 in the database
    // =====================================================================================================

    @Nested
    class Schema {

        private String rule(String table, String column) {
            return jdbc.queryForObject("SELECT c.confdeltype::text FROM pg_constraint c JOIN pg_attribute a "
                    + "ON a.attrelid = c.conrelid AND a.attnum = c.conkey[1] WHERE c.contype = 'f' "
                    + "AND c.conrelid = ?::regclass AND a.attname = ?", String.class, table, column);
        }

        private boolean indexed(String table, String column) {
            return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM pg_index i "
                    + "JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = i.indkey[0] "
                    + "WHERE i.indrelid = ?::regclass AND a.attname = ? AND i.indisvalid AND i.indpred IS NULL)",
                    Boolean.class, table, column));
        }

        @Test
        void e9_theSevenForeignKeys_withTheirRule_andAnIndexOnTheChildColumn() {
            // confdeltype: a NO ACTION, c CASCADE
            String[][] keys = {
                    {"work_order", "parent_preventive_maintenance_id", "a"},
                    {"work_order_aud", "parent_preventive_maintenance_id", "a"},
                    {"preventive_maintenance", "schedule_id", "a"},
                    {"task", "task_base_id", "a"},
                    {"workflow_action", "checklist_id", "a"},
                    {"custom_field_value", "preventive_maintenance_id", "c"},
                    {"task_option", "task_base_id", "c"}};
            List<String> expected = new ArrayList<>();
            List<String> actual = new ArrayList<>();
            for (String[] key : keys) {
                expected.add(key[0] + "." + key[1] + " " + key[2] + " indexed");
                actual.add(key[0] + "." + key[1] + " " + rule(key[0], key[1])
                        + (indexed(key[0], key[1]) ? " indexed" : " not indexed"));
            }
            assertEquals(expected, actual);
        }

        @Test
        void ca048_theSharedDefinitionDeletedInTheDatabase_23503_bothTasksKeepItAndItsOptions() {
            CommonData data = data();
            long pm = preventiveMaintenance(data.o1().admin(), "");
            assertEquals(200, replaceTasks("preventive-maintenance", pm, "[" + task("P-15 shared") + "]",
                    data.o1().admin()).status());
            long workOrder = generatedWorkOrder(pm, data.o1().admin());
            List<Map<String, Object>> before = tasksOf(workOrder);
            Long taskBase = (Long) before.get(0).get("task_base_id");

            DataIntegrityViolationException failure = assertThrows(DataIntegrityViolationException.class,
                    () -> jdbc.update("DELETE FROM task_base WHERE id = ?", taskBase));

            assertAll(
                    () -> assertEquals("23503", sqlState(failure)),
                    () -> assertEquals(before, tasksOf(workOrder)),
                    () -> assertEquals("no,yes", before.get(0).get("options")),
                    () -> assertEquals(2, count("SELECT count(*) FROM task WHERE task_base_id = ?", taskBase)));
        }

        @Test
        void theOptionsGoWithTheirDefinition_andTheValuesWithTheirPreventiveMaintenance() {
            CommonData data = data();
            Company company = data.o1().company();
            // the definitions of a checklist are used by no task
            long checklist = checklist(company, data.o1().admin(), "P-15 alone");
            Long taskBase = jdbc.queryForObject("SELECT task_bases_id FROM checklist_task_bases WHERE checklist_id = ?",
                    Long.class, checklist);
            long pm = preventiveMaintenance(data.o1().admin(), "");
            CustomField field = new CustomField();
            field.setLabel(TestRecords.unique("P-15 field"));
            field.setFieldType(CustomFieldType.SHORT_TEXT);
            field.setEntityType(CustomFieldEntityType.WORK_ORDER);
            field.setCompanySettings(company.getCompanySettings());
            Long fieldId = customFieldRepository.saveAndFlush(field).getId();
            Long value = jdbc.queryForObject("INSERT INTO custom_field_value (custom_field_id, preventive_maintenance_id, "
                    + "value) VALUES (?, ?, 'P-15') RETURNING id", Long.class, fieldId, pm);

            jdbc.update("DELETE FROM task_base WHERE id = ?", taskBase);
            jdbc.update("DELETE FROM preventive_maintenance WHERE id = ?", pm);

            assertAll(
                    () -> assertEquals(0, count("SELECT count(*) FROM task_option WHERE task_base_id = ? OR "
                            + "(company_id = ? AND task_base_id IS NULL)", taskBase, company.getId())),
                    () -> assertEquals(0, count("SELECT count(*) FROM custom_field_value WHERE id = ?", value)));
        }
    }

    // =====================================================================================================
    // B2 · Deleting a preventive maintenance (CA-PPB-098, E-1, E-4, TD-P17-06, TD-P17-10)
    // =====================================================================================================

    @Nested
    class Deletion {

        private Response deletePreventiveMaintenance(long pm, User user) {
            return call(delete("/preventive-maintenances/" + pm), user);
        }

        @Test
        void ca098_withAGeneratedWorkOrder_409InUse_workOrderKeepsItsLink_jobsGoOn_noRecord() throws Exception {
            CommonData data = data();
            long pm = preventiveMaintenance(data.o1().admin(), "");
            long schedule = scheduleOf(pm);
            long workOrder = generatedWorkOrder(pm, data.o1().admin());

            Response response = deletePreventiveMaintenance(pm, data.o1().admin());

            assertAll(
                    () -> assertEquals(List.of("WORK_ORDER"), conflict(response, "PREVENTIVE_MAINTENANCE_IN_USE")),
                    () -> assertEquals(1, count("SELECT count(*) FROM preventive_maintenance WHERE id = ?", pm)),
                    () -> assertEquals(1, count("SELECT count(*) FROM work_order WHERE id = ? "
                            + "AND parent_preventive_maintenance_id = ?", workOrder, pm)),
                    () -> assertTrue(jobExists(schedule), "a 409 does not stop the jobs"),
                    () -> assertEquals(List.of(), audit(data)));
        }

        @Test
        void citedNowAndByAnEarlierRevision_409_withBothTypes() {
            CommonData data = data();
            long pm = preventiveMaintenance(data.o1().admin(), "");
            generatedWorkOrder(pm, data.o1().admin());
            long earlier = generatedWorkOrder(pm, data.o1().admin());
            // the work order no longer cites it; its revision still does
            jdbc.update("UPDATE work_order SET parent_preventive_maintenance_id = NULL WHERE id = ?", earlier);

            Response response = deletePreventiveMaintenance(pm, data.o1().admin());

            assertEquals(List.of("WORK_ORDER", "WORK_ORDER_HISTORY"),
                    conflict(response, "PREVENTIVE_MAINTENANCE_IN_USE"));
        }

        @Test
        void citedOnlyByAnEarlierRevision_409History() {
            CommonData data = data();
            long pm = preventiveMaintenance(data.o1().admin(), "");
            long earlier = generatedWorkOrder(pm, data.o1().admin());
            jdbc.update("UPDATE work_order SET parent_preventive_maintenance_id = NULL WHERE id = ?", earlier);

            Response response = deletePreventiveMaintenance(pm, data.o1().admin());

            assertAll(
                    () -> assertEquals(List.of("WORK_ORDER_HISTORY"),
                            conflict(response, "PREVENTIVE_MAINTENANCE_IN_USE")),
                    () -> assertEquals(1, count("SELECT count(*) FROM work_order_aud WHERE id = ? "
                            + "AND parent_preventive_maintenance_id = ?", earlier, pm)));
        }

        @Test
        void d4_assignedToADisabledUser_409User_associationKept() {
            CommonData data = data();
            long pm = preventiveMaintenance(data.o1().admin(), ",\"assignedTo\":[" + ref(data.o1().technician()
                    .getId()) + "]");
            jdbc.update("UPDATE own_user SET enabled = false WHERE id = ?", data.o1().technician().getId());

            Response response = deletePreventiveMaintenance(pm, data.o1().admin());

            assertAll(
                    () -> assertEquals(List.of("USER"), conflict(response, "PREVENTIVE_MAINTENANCE_IN_USE")),
                    () -> assertEquals(1, count("SELECT count(*) FROM preventive_maintenance_assigned_to "
                            + "WHERE preventive_maintenance_id = ?", pm)));
        }

        @Test
        void assignedToAnActiveUser_200() {
            CommonData data = data();
            long pm = preventiveMaintenance(data.o1().admin(), ",\"assignedTo\":[" + ref(data.o1().technician()
                    .getId()) + "]");

            Response response = deletePreventiveMaintenance(pm, data.o1().admin());

            assertEquals(200, response.status(), response.toString());
        }

        @Test
        void e1_withoutWorkOrders_200_recordedWithWhatWentInCascade_jobsStopped() throws Exception {
            CommonData data = data();
            long pm = preventiveMaintenance(data.o1().admin(), "");
            long schedule = scheduleOf(pm);
            assertEquals(200, replaceTasks("preventive-maintenance", pm, "[" + task("P-15 step") + "]",
                    data.o1().admin()).status());
            String folio = jdbc.queryForObject("SELECT custom_id FROM preventive_maintenance WHERE id = ?",
                    String.class, pm);

            Response response = deletePreventiveMaintenance(pm, data.o1().admin());

            assertAll(
                    () -> assertEquals(200, response.status(), response.toString()),
                    () -> assertEquals("{\"success\":true,\"message\":\"Deleted successfully\"}", response.body()),
                    () -> assertEquals(0, count("SELECT count(*) FROM preventive_maintenance WHERE id = ?", pm)),
                    () -> assertEquals(0, count("SELECT count(*) FROM schedule WHERE id = ?", schedule)),
                    () -> assertFalse(jobExists(schedule)),
                    () -> assertEquals(List.of("DELETE PREVENTIVE_MAINTENANCE API " + data.o1().admin().getId()),
                            audit(data)),
                    () -> assertEquals(folio, jdbc.queryForObject("SELECT entity_folio FROM audit_log "
                            + "WHERE company_id = ?", String.class, data.o1().company().getId())),
                    () -> assertTrue(cascadeRows(data).contains("\"schedule\": 1"), cascadeRows(data)),
                    () -> assertTrue(cascadeRows(data).contains("\"task\": 1"), cascadeRows(data)));
        }

        @Test
        void e4_a2_withoutSchedule_200_recorded() {
            CommonData data = data();
            long pm = preventiveMaintenance(data.o1().admin(), "");
            long schedule = scheduleOf(pm);
            jdbc.update("UPDATE preventive_maintenance SET schedule_id = NULL WHERE id = ?", pm);
            jdbc.update("DELETE FROM schedule WHERE id = ?", schedule);

            Response response = deletePreventiveMaintenance(pm, data.o1().admin());

            assertAll(
                    () -> assertEquals(200, response.status(), response.toString()),
                    () -> assertEquals(0, count("SELECT count(*) FROM preventive_maintenance WHERE id = ?", pm)),
                    () -> assertEquals(List.of("DELETE PREVENTIVE_MAINTENANCE API " + data.o1().admin().getId()),
                            audit(data)));
        }

        @Test
        void tdP1706_theDeletionFails_itsJobsGoOn() throws Exception {
            CommonData data = data();
            long pm = preventiveMaintenance(data.o1().admin(), "");
            long schedule = scheduleOf(pm);

            Response response;
            try (FaultInjector.Fault fault = faults.failOn("preventive_maintenance",
                    FaultInjector.Operation.DELETE)) {
                response = deletePreventiveMaintenance(pm, data.o1().admin());
                assertEquals(1, fault.hits());
            }

            Response failed = response;
            assertAll(
                    () -> assertNotEquals(200, failed.status(), failed.toString()),
                    () -> assertEquals(1, count("SELECT count(*) FROM preventive_maintenance WHERE id = ?", pm)),
                    () -> assertTrue(jobExists(schedule)),
                    () -> assertEquals(List.of(), audit(data)));
        }

        @Test
        void theAuditWriteFails_keptWithItsJobs() throws Exception {
            CommonData data = data();
            long pm = preventiveMaintenance(data.o1().admin(), "");
            long schedule = scheduleOf(pm);

            Response response;
            try (FaultInjector.Fault ignored = faults.failOn("audit_log", FaultInjector.Operation.INSERT)) {
                response = deletePreventiveMaintenance(pm, data.o1().admin());
            }

            Response failed = response;
            assertAll(
                    () -> assertNotEquals(200, failed.status(), failed.toString()),
                    () -> assertEquals(1, count("SELECT count(*) FROM preventive_maintenance WHERE id = ?", pm)),
                    () -> assertTrue(jobExists(schedule)));
        }

        @Test
        void technicianWhoDidNotCreateIt_403_noMentionOfDependents() {
            CommonData data = data();
            long pm = preventiveMaintenance(data.o1().admin(), "");
            generatedWorkOrder(pm, data.o1().admin());

            Response response = deletePreventiveMaintenance(pm, data.o1().technician());

            assertAll(
                    () -> assertEquals(403, response.status(), response.toString()),
                    () -> assertFalse(response.body().contains("WORK_ORDER"), response.toString()),
                    () -> assertEquals(1, count("SELECT count(*) FROM preventive_maintenance WHERE id = ?", pm)));
        }

        @Test
        void inv10_anotherOrganization_403_never409() {
            CommonData data = data();
            long pm = preventiveMaintenance(data.o1().admin(), "");
            generatedWorkOrder(pm, data.o1().admin());

            Response response = deletePreventiveMaintenance(pm, data.o2().admin());

            assertAll(
                    () -> assertEquals(403, response.status(), response.toString()),
                    () -> assertFalse(response.body().contains("IN_USE"), response.toString()),
                    () -> assertEquals(1, count("SELECT count(*) FROM preventive_maintenance WHERE id = ?", pm)));
        }
    }

    // =====================================================================================================
    // B3 · The schedule is not deleted alone (CA-PPB-039, E-3, PPB-14)
    // =====================================================================================================

    @Nested
    class Schedules {

        @Test
        void ca039_deleteTheSchedule_409ScheduleRequired_scheduleAndJobsKept() throws Exception {
            CommonData data = data();
            long pm = preventiveMaintenance(data.o1().admin(), "");
            long schedule = scheduleOf(pm);
            Map<String, Object> before = jdbc.queryForMap("SELECT * FROM schedule WHERE id = ?", schedule);

            Response response = call(delete("/schedules/" + schedule), data.o1().admin());

            assertAll(
                    () -> assertEquals(List.of(), conflict(response, "SCHEDULE_REQUIRED")),
                    () -> assertEquals(before, jdbc.queryForMap("SELECT * FROM schedule WHERE id = ?", schedule)),
                    () -> assertEquals(schedule, scheduleOf(pm)),
                    () -> assertTrue(jobExists(schedule)),
                    () -> assertEquals(List.of(), audit(data)));
        }

        @Test
        void withoutPermission_403_beforeThe409() throws Exception {
            CommonData data = data();
            long schedule = scheduleOf(preventiveMaintenance(data.o1().admin(), ""));

            Response response = call(delete("/schedules/" + schedule), data.o1().technician());

            assertAll(
                    () -> assertEquals(403, response.status(), response.toString()),
                    () -> assertTrue(jobExists(schedule)));
        }

        @Test
        void inv10_anotherOrganization_403_never409() throws Exception {
            CommonData data = data();
            long schedule = scheduleOf(preventiveMaintenance(data.o1().admin(), ""));

            Response response = call(delete("/schedules/" + schedule), data.o2().admin());

            assertAll(
                    () -> assertEquals(403, response.status(), response.toString()),
                    () -> assertFalse(response.body().contains("SCHEDULE_REQUIRED"), response.toString()),
                    () -> assertTrue(jobExists(schedule)));
        }

        @Test
        void unknown_404() {
            CommonData data = data();

            Response response = call(delete("/schedules/" + Long.MAX_VALUE), data.o1().admin());

            assertEquals(404, response.status(), response.toString());
        }
    }

    // =====================================================================================================
    // B4 · Pausing and resuming (CA-PPB-097, E-2, D-P15-1, D-P15-2, D-P15-3)
    // =====================================================================================================

    @Nested
    class PauseAndResume {

        private Response setDisabled(long pm, boolean disabled, User user) {
            return send(patch("/preventive-maintenances/" + pm), "{\"disabled\":" + disabled + "}", user);
        }

        private boolean disabled(long schedule) {
            return Boolean.TRUE.equals(jdbc.queryForObject("SELECT disabled FROM schedule WHERE id = ?",
                    Boolean.class, schedule));
        }

        private long workOrdersOf(long pm) {
            return count("SELECT count(*) FROM work_order WHERE parent_preventive_maintenance_id = ?", pm);
        }

        /**
         * The next occurrence: what the work order job does when its trigger fires.
         */
        private void occurrence(long schedule) throws Exception {
            JobDataMap jobData = new JobDataMap();
            jobData.put("scheduleId", schedule);
            JobExecutionContext context = mock(JobExecutionContext.class);
            when(context.getMergedJobDataMap()).thenReturn(jobData);
            workOrderCreationJob.executeInternal(context);
        }

        private Response editSchedule(long schedule, User user) {
            return send(patch("/schedules/" + schedule), "{\"frequency\":2,\"recurrenceType\":\"DAILY\","
                    + "\"recurrenceBasedOn\":\"SCHEDULED_DATE\",\"startsOn\":\"" + FUTURE + "\"}", user);
        }

        @Test
        void ca097_pausedGeneratesNothing_editedStaysPaused_resumedTheNextOccurrenceGenerates() throws Exception {
            CommonData data = data();
            User admin = data.o1().admin();
            long pm = preventiveMaintenance(admin, "");
            long schedule = scheduleOf(pm);

            Response pause = setDisabled(pm, true, admin);
            boolean pausedAfterPause = disabled(schedule);
            occurrence(schedule);
            long whilePaused = workOrdersOf(pm);
            Response edit = send(patch("/preventive-maintenances/" + pm), "{\"title\":\"P-15 edited\"}", admin);
            boolean pausedAfterEdit = disabled(schedule);
            Response resume = setDisabled(pm, false, admin);
            occurrence(schedule);

            assertAll(
                    () -> assertEquals(200, pause.status(), pause.toString()),
                    () -> assertTrue(json(pause).get("schedule").get("disabled").asBoolean()),
                    () -> assertTrue(pausedAfterPause),
                    () -> assertEquals(0, whilePaused, "paused: the occurrence generates no work order"),
                    () -> assertEquals(200, edit.status(), edit.toString()),
                    () -> assertTrue(pausedAfterEdit, "editing it does not resume it"),
                    () -> assertEquals(200, resume.status(), resume.toString()),
                    () -> assertFalse(disabled(schedule)),
                    () -> assertTrue(jobExists(schedule)),
                    () -> assertEquals(1, workOrdersOf(pm), "resumed: the next occurrence generates it"),
                    () -> assertEquals(List.of("RETIRE PREVENTIVE_MAINTENANCE API " + admin.getId(),
                            "RESTORE PREVENTIVE_MAINTENANCE API " + admin.getId()), audit(data)));
        }

        @Test
        void pausingOrResuming_changesOnlyDisabled() {
            CommonData data = data();
            long pm = preventiveMaintenance(data.o1().admin(), "");
            Map<String, Object> before = jdbc.queryForMap("SELECT * FROM preventive_maintenance WHERE id = ?", pm);

            Response pause = send(patch("/preventive-maintenances/" + pm), "{\"disabled\":true,\"title\":\"P-15 "
                    + "other\",\"priority\":\"HIGH\"}", data.o1().admin());
            Map<String, Object> afterPause = jdbc.queryForMap("SELECT * FROM preventive_maintenance WHERE id = ?", pm);
            Response resume = send(patch("/preventive-maintenances/" + pm), "{\"disabled\":false,\"title\":\"P-15 "
                    + "other\"}", data.o1().admin());

            assertAll(
                    () -> assertEquals(200, pause.status(), pause.toString()),
                    () -> assertEquals(before, afterPause),
                    () -> assertEquals(200, resume.status(), resume.toString()),
                    () -> assertEquals(before, jdbc.queryForMap("SELECT * FROM preventive_maintenance WHERE id = ?",
                            pm)));
        }

        @Test
        void theSameState_200_noRecord() {
            CommonData data = data();
            long pm = preventiveMaintenance(data.o1().admin(), "");

            Response resumeActive = setDisabled(pm, false, data.o1().admin());
            setDisabled(pm, true, data.o1().admin());
            Response pauseAgain = setDisabled(pm, true, data.o1().admin());

            assertAll(
                    () -> assertEquals(200, resumeActive.status(), resumeActive.toString()),
                    () -> assertEquals(200, pauseAgain.status(), pauseAgain.toString()),
                    () -> assertEquals(List.of("RETIRE PREVENTIVE_MAINTENANCE API " + data.o1().admin().getId()),
                            audit(data)));
        }

        @Test
        void scheduleEditedWhilePaused_resumed_itsJobsAreBack() throws Exception {
            CommonData data = data();
            long pm = preventiveMaintenance(data.o1().admin(), "");
            long schedule = scheduleOf(pm);
            setDisabled(pm, true, data.o1().admin());

            Response edit = editSchedule(schedule, data.o1().admin());
            boolean jobWhilePaused = jobExists(schedule);
            Response resume = setDisabled(pm, false, data.o1().admin());

            assertAll(
                    () -> assertEquals(200, edit.status(), edit.toString()),
                    () -> assertFalse(jobWhilePaused, "editing the schedule of a paused one does not schedule it"),
                    () -> assertEquals(200, resume.status(), resume.toString()),
                    () -> assertFalse(disabled(schedule)),
                    () -> assertTrue(jobExists(schedule)));
        }

        @Test
        void d3_theSystemPausesIt_recordedWithoutActorWithTheOriginOfTheRoute() {
            CommonData data = data();
            long pm = preventiveMaintenance(data.o1().admin(), "");
            long schedule = scheduleOf(pm);
            for (int i = 0; i < 5; i++) generatedWorkOrder(pm, data.o1().admin());

            Response edit = editSchedule(schedule, data.o1().admin());

            assertAll(
                    () -> assertEquals(200, edit.status(), edit.toString()),
                    () -> assertTrue(disabled(schedule)),
                    () -> assertEquals(List.of("RETIRE PREVENTIVE_MAINTENANCE API -"), audit(data)));
        }

        @Test
        void alreadyPausedByTheUser_theSystemRecordsNothingMore() {
            CommonData data = data();
            long pm = preventiveMaintenance(data.o1().admin(), "");
            long schedule = scheduleOf(pm);
            for (int i = 0; i < 5; i++) generatedWorkOrder(pm, data.o1().admin());
            setDisabled(pm, true, data.o1().admin());

            Response edit = editSchedule(schedule, data.o1().admin());

            assertAll(
                    () -> assertEquals(200, edit.status(), edit.toString()),
                    () -> assertTrue(disabled(schedule)),
                    () -> assertEquals(List.of("RETIRE PREVENTIVE_MAINTENANCE API " + data.o1().admin().getId()),
                            audit(data)));
        }

        @Test
        void d2_pausedByTheSystem_resumed_staysActive_withItsJobs_theNextOccurrenceGenerates() throws Exception {
            CommonData data = data();
            long pm = preventiveMaintenance(data.o1().admin(), "");
            long schedule = scheduleOf(pm);
            for (int i = 0; i < 5; i++) generatedWorkOrder(pm, data.o1().admin());
            editSchedule(schedule, data.o1().admin());
            boolean pausedBySystem = disabled(schedule);

            Response resume = setDisabled(pm, false, data.o1().admin());
            occurrence(schedule);

            assertAll(
                    () -> assertTrue(pausedBySystem),
                    () -> assertEquals(200, resume.status(), resume.toString()),
                    () -> assertFalse(json(resume).get("schedule").get("disabled").asBoolean()),
                    () -> assertFalse(disabled(schedule), "resuming does not evaluate the rule of 5 (D-P15-2)"),
                    () -> assertTrue(jobExists(schedule)),
                    () -> assertEquals(6, workOrdersOf(pm)),
                    () -> assertEquals(List.of("RETIRE PREVENTIVE_MAINTENANCE API -",
                            "RESTORE PREVENTIVE_MAINTENANCE API " + data.o1().admin().getId()), audit(data)));
        }

        @Test
        void technicianWhoCannotEditIt_403_unchanged_noRecord() {
            CommonData data = data();
            long pm = preventiveMaintenance(data.o1().admin(), "");

            Response response = setDisabled(pm, true, data.o1().technician());

            assertAll(
                    () -> assertEquals(403, response.status(), response.toString()),
                    () -> assertFalse(disabled(scheduleOf(pm))),
                    () -> assertEquals(List.of(), audit(data)));
        }

        @Test
        void inv10_anotherOrganization_403_unchanged() {
            CommonData data = data();
            long pm = preventiveMaintenance(data.o1().admin(), "");

            Response response = setDisabled(pm, true, data.o2().admin());

            assertAll(
                    () -> assertEquals(403, response.status(), response.toString()),
                    () -> assertFalse(disabled(scheduleOf(pm))));
        }
    }

    // =====================================================================================================
    // B5 · Tasks of a preventive maintenance and their definitions (E-5, E-6, CA-PPB-047, 048, V-3)
    // =====================================================================================================

    @Nested
    class Tasks {

        private String folioOf(long pm) {
            return jdbc.queryForObject("SELECT custom_id FROM preventive_maintenance WHERE id = ?", String.class, pm);
        }

        private List<String> taskRecords(CommonData data) {
            return jdbc.queryForList("SELECT action || ' ' || entity_type || ' ' || entity_folio || ' ' || entity_name "
                    + "|| ' ' || coalesce(actor_id::text, '-') FROM audit_log WHERE company_id = ? ORDER BY id",
                    String.class, data.o1().company().getId());
        }

        @Test
        void ca048_e5_taskOfThePreventiveMaintenanceRemoved_recorded_theGeneratedTaskKeepsItsDefinition() {
            CommonData data = data();
            User admin = data.o1().admin();
            long pm = preventiveMaintenance(admin, "");
            assertEquals(200, replaceTasks("preventive-maintenance", pm, "[" + task("P-15 shared") + "]", admin)
                    .status());
            long workOrder = generatedWorkOrder(pm, admin);
            List<Map<String, Object>> before = tasksOf(workOrder);

            Response removed = replaceTasks("preventive-maintenance", pm, "[]", admin);

            assertAll(
                    () -> assertEquals(200, removed.status(), removed.toString()),
                    () -> assertEquals(0, count("SELECT count(*) FROM task WHERE preventive_maintenance_id = ?", pm)),
                    () -> assertEquals(before, tasksOf(workOrder)),
                    () -> assertEquals("no,yes", tasksOf(workOrder).get(0).get("options")),
                    () -> assertEquals(List.of("DELETE TASK " + folioOf(pm) + " P-15 shared " + admin.getId()),
                            taskRecords(data)));
        }

        @Test
        void e5_taskOfThePreventiveMaintenanceDeletedByItsEndpoint_recorded() {
            CommonData data = data();
            User admin = data.o1().admin();
            long pm = preventiveMaintenance(admin, "");
            Response tasks = replaceTasks("preventive-maintenance", pm, "[" + task("P-15 alone") + "]", admin);
            long task = json(tasks).get(0).get("id").asLong();

            Response response = call(delete("/tasks/" + task), admin);

            assertAll(
                    () -> assertEquals(200, response.status(), response.toString()),
                    () -> assertEquals(0, count("SELECT count(*) FROM task WHERE id = ?", task)),
                    () -> assertEquals(List.of("DELETE TASK " + folioOf(pm) + " P-15 alone " + admin.getId()),
                            taskRecords(data)));
        }

        @Test
        void e5_theAuditWriteFails_theTaskIsKept() {
            CommonData data = data();
            long pm = preventiveMaintenance(data.o1().admin(), "");
            assertEquals(200, replaceTasks("preventive-maintenance", pm, "[" + task("P-15 kept") + "]",
                    data.o1().admin()).status());

            Response response;
            try (FaultInjector.Fault ignored = faults.failOn("audit_log", FaultInjector.Operation.INSERT)) {
                response = replaceTasks("preventive-maintenance", pm, "[]", data.o1().admin());
            }

            Response failed = response;
            assertAll(
                    () -> assertNotEquals(200, failed.status(), failed.toString()),
                    () -> assertEquals(1, count("SELECT count(*) FROM task WHERE preventive_maintenance_id = ?",
                            pm)));
        }

        @Test
        void ca047_v3_assetCitedByTheDefinitionOfATaskOfAClosedWorkOrder_409AssetInUse_taskAndDefinitionKept() {
            CommonData data = data();
            Company company = data.o1().company();
            com.grash.model.Asset asset = records.asset(company);
            long workOrder = records.workOrder(company, null, com.grash.model.enums.Status.OPEN, false).getId();
            assertEquals(200, replaceTasks("work-order", workOrder, "[{\"label\":\"P-15 on asset\",\"taskType\":"
                    + "\"SUBTASK\",\"options\":[],\"asset\":" + ref(asset.getId()) + "}]", data.o1().admin()).status());
            jdbc.update("UPDATE work_order SET status = ? WHERE id = ?",
                    com.grash.model.enums.Status.COMPLETE.ordinal(), workOrder);
            List<Map<String, Object>> before = tasksOf(workOrder);

            Response response = call(delete("/assets/" + asset.getId()), data.o1().admin());

            assertAll(
                    () -> assertTrue(conflict(response, "ASSET_IN_USE").contains("TASK_BASE"), response.toString()),
                    () -> assertEquals(before, tasksOf(workOrder)),
                    () -> assertEquals(1, count("SELECT count(*) FROM task_base WHERE asset_id = ?", asset.getId())));
        }
    }
}
