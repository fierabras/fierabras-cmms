package com.grash.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.grash.integration.support.TestApi;
import com.grash.integration.support.TestApi.Response;
import com.grash.integration.support.TestOrganizations;
import com.grash.integration.support.TestOrganizations.CommonData;
import com.grash.integration.support.TestRecords;
import com.grash.integration.support.TestWorkOrders;
import com.grash.integration.support.TestWorkOrders.Fixture;
import com.grash.model.Asset;
import com.grash.model.CustomField;
import com.grash.model.User;
import com.grash.model.WorkOrder;
import com.grash.model.enums.CustomFieldEntityType;
import com.grash.model.enums.CustomFieldType;
import com.grash.model.enums.Status;
import com.grash.model.enums.webhook.WebhookEvent;
import com.grash.repository.CustomFieldRepository;
import com.grash.repository.FileRepository;
import com.grash.repository.PartRepository;
import com.grash.repository.WorkOrderRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * P-12: the life cycle of a work order (PPB-06, PPB-07, PPB-08, PPB-14, PPB-15, PPB-31 to PPB-34). A work order is
 * never deleted; while open its children change and each removal is recorded; closed or archived, its children and
 * associations do not change (409); reopening, archiving and restoring are recorded.
 */
@AutoConfigureMockMvc
class WorkOrderLifecycleIntegrationTest extends AbstractIntegrationTest {

    private static final String CLOSED = "WORK_ORDER_CLOSED";
    private static final String RETIRED = "WORK_ORDER_RETIRED";
    private static final String NOT_DELETABLE = "WORK_ORDER_NOT_DELETABLE";

    private final TestOrganizations organizations;
    private final TestRecords records;
    private final TestApi api;
    private final TestWorkOrders workOrders;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;
    private final WorkOrderRepository workOrderRepository;
    private final CustomFieldRepository customFieldRepository;
    private final List<CommonData> created = new ArrayList<>();

    @Autowired
    WorkOrderLifecycleIntegrationTest(TestOrganizations organizations, TestRecords records, MockMvc mockMvc,
                                      ObjectMapper objectMapper, JdbcTemplate jdbc,
                                      WorkOrderRepository workOrderRepository, PartRepository partRepository,
                                      FileRepository fileRepository, CustomFieldRepository customFieldRepository) {
        this.organizations = organizations;
        this.records = records;
        this.api = new TestApi(mockMvc, objectMapper);
        this.workOrders = new TestWorkOrders(api, objectMapper, records, workOrderRepository, partRepository,
                fileRepository);
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
        this.workOrderRepository = workOrderRepository;
        this.customFieldRepository = customFieldRepository;
    }

    @AfterEach
    void cleanUp() {
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

    private void assertConflict(Response response, String code) {
        assertEquals(409, response.status(), response.toString());
        JsonNode body = json(response);
        assertFalse(body.get("success").asBoolean(), response.toString());
        assertEquals(code, body.get("code").asText(), response.toString());
        assertFalse(body.get("message").asText().isBlank(), response.toString());
        assertFalse(body.has("blockingTypes"), response.toString());
        assertFalse(response.body().toLowerCase().contains("constraint"), response.toString());
    }

    private Response call(MockHttpServletRequestBuilder request, String token, Object body) {
        return workOrders.call(request, token, body);
    }

    private long count(String table, String column, Long id) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + column + " = ?", Long.class, id);
    }

    private WorkOrder reload(Long id) {
        return workOrderRepository.findById(id).orElseThrow();
    }

    private long auditRecords(CommonData data) {
        return count("audit_log", "company_id", data.o1().company().getId());
    }

    /**
     * The audit records of the organization: action, type, entity, folio, name, source.
     */
    private List<Map<String, Object>> audit(CommonData data) {
        return jdbc.queryForList("SELECT action, entity_type, entity_id, entity_folio, entity_name, source, "
                + "actor_id FROM audit_log WHERE company_id = ? ORDER BY id", data.o1().company().getId());
    }

    /**
     * Every row that a change to the work order or to its children could touch, for the "no changes" checks.
     */
    private Map<String, Object> snapshot(Long id, Long part) {
        return TestWorkOrders.rows(jdbc, id, part);
    }

    private CustomField customField(CommonData data) {
        CustomField field = new CustomField();
        field.setLabel("P-12 field " + UUID.randomUUID());
        field.setFieldType(CustomFieldType.SHORT_TEXT);
        field.setEntityType(CustomFieldEntityType.WORK_ORDER);
        field.setCompanySettings(data.o1().company().getCompanySettings());
        return customFieldRepository.saveAndFlush(field);
    }

    /**
     * The operations that change a child or an association of the work order of {@code f}, by A, each built
     * anew: create, modify and remove each child, the collection replacements that remove one element, files and
     * the timer.
     */
    private Map<String, Supplier<Response>> childChanges(Fixture f, String token) {
        Map<String, Supplier<Response>> changes = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        Long other = records.workOrder(reload(f.id()).getCompany(), null, Status.OPEN, false).getId();
        changes.put("create labor", () -> call(post("/labors"), token, Map.of("workOrder", Map.of("id", f.id()),
                "duration", 60, "startedAt", now)));
        changes.put("modify labor", () -> call(patch("/labors/" + f.labor()), token, Map.of("duration", 7200,
                "hourlyRate", 10, "startedAt", now)));
        changes.put("remove labor", () -> api.call(delete("/labors/" + f.labor()), token));
        changes.put("start timer", () -> api.call(post("/labors/work-order/" + f.id()), token));
        changes.put("create cost", () -> call(post("/additional-costs"), token, Map.of("workOrder",
                Map.of("id", f.id()), "description", "c", "cost", 1)));
        changes.put("modify cost", () -> call(patch("/additional-costs/" + f.cost()), token, Map.of("cost", 99,
                "description", "x")));
        changes.put("remove cost", () -> api.call(delete("/additional-costs/" + f.cost()), token));
        changes.put("add task", () -> call(patch("/tasks/work-order/" + f.id()), token, List.of(
                Map.of("label", "new", "taskType", "SUBTASK", "options", List.of()))));
        changes.put("modify task", () -> call(patch("/tasks/" + f.task()), token, Map.of("value", "COMPLETE")));
        changes.put("remove task", () -> api.call(delete("/tasks/" + f.task()), token));
        changes.put("replace tasks without one", () -> call(patch("/tasks/work-order/" + f.id()), token,
                List.of()));
        changes.put("create part quantity", () -> call(post("/part-quantities"), token, Map.of("workOrder",
                Map.of("id", f.id()), "part", Map.of("id", f.part()), "quantity", 1)));
        changes.put("modify part quantity", () -> call(patch("/part-quantities/" + f.partQuantity()), token,
                Map.of("quantity", 2)));
        changes.put("remove part quantity", () -> api.call(delete("/part-quantities/" + f.partQuantity()), token));
        changes.put("replace part quantities without one", () -> call(patch("/part-quantities/work-order/"
                + f.id()), token, List.of()));
        changes.put("create relation", () -> call(post("/relations"), token, Map.of("parent", Map.of("id", f.id()),
                "child", Map.of("id", other), "relationType", "BLOCKS")));
        changes.put("modify relation", () -> call(patch("/relations/" + f.relation()), token, Map.of(
                "parent", Map.of("id", f.id()), "child", Map.of("id", f.relatedWorkOrder()))));
        changes.put("remove relation", () -> api.call(delete("/relations/" + f.relation()), token));
        changes.put("add file", () -> call(patch("/work-orders/files/" + f.id() + "/add"), token,
                List.of(Map.of("id", f.file()))));
        changes.put("remove file", () -> api.call(delete("/work-orders/files/" + f.id() + "/" + f.file()
                + "/remove"), token));
        return changes;
    }

    // =====================================================================================================
    // E-1 · The work order is not deleted
    // =====================================================================================================

    @Nested
    class NotDeletable {

        @Test
        void ca021_deleteInEveryStatus_byCreatorAndAdmin_409_noMailNoWebhook() throws InterruptedException {
            CommonData data = data();
            User a = data.o1().admin();
            User t = data.o1().technician();
            records.endpoint(data.o1().company(), WebhookEvent.WORK_ORDER_DELETE);
            String ta = api.login(a);
            String tt = api.login(t);

            for (Status status : List.of(Status.OPEN, Status.IN_PROGRESS, Status.ON_HOLD, Status.COMPLETE)) {
                Fixture f = workOrders.create(a, t, status, false);
                // T is the creator: the creator could delete it until P-12
                jdbc.update("UPDATE work_order SET created_by = ? WHERE id = ?", t.getId(), f.id());
                Map<String, Object> before = snapshot(f.id(), f.part());
                mailDouble.reset();

                assertConflict(api.call(delete("/work-orders/" + f.id()), tt), NOT_DELETABLE);
                assertConflict(api.call(delete("/work-orders/" + f.id()), ta), NOT_DELETABLE);

                assertEquals(before, snapshot(f.id(), f.part()), status.name());
                assertTrue(mailDouble.calls().isEmpty(), status.name());
            }
            Thread.sleep(1500); // webhooks are asynchronous
            assertTrue(webhookDouble.calls("WORK_ORDER_DELETE").isEmpty());
            assertEquals(0, auditRecords(data));
        }
    }

    // =====================================================================================================
    // E-2 and E-3 · Archive, restore; an archived work order does not change
    // =====================================================================================================

    @Nested
    class Archive {

        @Test
        void ca022_archive_keepsChildrenAndOriginRequest_recordsRetire() {
            CommonData data = data();
            User a = data.o1().admin();
            Fixture f = workOrders.create(a, data.o1().technician(), Status.OPEN, false);
            String ta = api.login(a);
            Long request = call(post("/requests"), ta, Map.of("title", TestRecords.unique("P-12 request")))
                    .body().transform(body -> {
                        try {
                            return objectMapper.readTree(body).get("id").asLong();
                        } catch (Exception e) {
                            throw new AssertionError(body, e);
                        }
                    });
            jdbc.update("UPDATE request SET work_order_id = ? WHERE id = ?", f.id(), request);
            jdbc.update("UPDATE work_order SET parent_request_id = ? WHERE id = ?", request, f.id());
            Map<String, Object> before = snapshot(f.id(), f.part());
            before.remove("work_order");
            before.remove("revisions");

            ObjectNode shown = workOrders.shown(f.id(), ta);
            shown.put("archived", true);
            Response response = call(patch("/work-orders/" + f.id()), ta, shown);

            assertEquals(200, response.status(), response.toString());
            WorkOrder archived = reload(f.id());
            assertTrue(archived.isArchived());
            Map<String, Object> after = snapshot(f.id(), f.part());
            after.remove("work_order");
            after.remove("revisions");
            assertEquals(before, after);
            assertEquals(f.id(), jdbc.queryForObject("SELECT work_order_id FROM request WHERE id = ?", Long.class,
                    request));
            List<Map<String, Object>> audit = audit(data);
            assertEquals(1, audit.size(), audit.toString());
            assertEquals("RETIRE", audit.get(0).get("action"));
            assertEquals("WORK_ORDER", audit.get(0).get("entity_type"));
            assertEquals(f.id(), ((Number) audit.get(0).get("entity_id")).longValue());
            assertEquals(archived.getCustomId(), audit.get(0).get("entity_folio"));
            assertEquals(archived.getTitle(), audit.get(0).get("entity_name"));
            assertEquals(a.getId(), ((Number) audit.get(0).get("actor_id")).longValue());
        }

        @Test
        void e2_patchWithoutArchived_doesNotArchiveNorRestore() {
            CommonData data = data();
            User a = data.o1().admin();
            String ta = api.login(a);
            Fixture open = workOrders.create(a, data.o1().technician(), Status.OPEN, false);
            Fixture archived = workOrders.create(a, data.o1().technician(), Status.OPEN, true);

            ObjectNode edit = workOrders.shown(open.id(), ta);
            edit.remove("archived");
            edit.put("title", "edited");
            assertEquals(200, call(patch("/work-orders/" + open.id()), ta, edit).status());
            ObjectNode archivedEdit = workOrders.shown(archived.id(), ta);
            archivedEdit.remove("archived");
            Response response = call(patch("/work-orders/" + archived.id()), ta, archivedEdit);

            assertFalse(reload(open.id()).isArchived());
            assertConflict(response, RETIRED);
            assertTrue(reload(archived.id()).isArchived());
            assertEquals(0, auditRecords(data));
        }

        @Test
        void ca026_archived_isRead_butItsTitleDoesNotChange() {
            CommonData data = data();
            User a = data.o1().admin();
            Asset asset = records.asset(data.o1().company());
            WorkOrder workOrder = records.workOrder(data.o1().company(), asset, Status.OPEN, false);
            workOrders.moveTo(workOrder.getId(), Status.OPEN, true);
            String ta = api.login(a);
            Map<String, Object> before = jdbc.queryForMap("SELECT * FROM work_order WHERE id = ?", workOrder.getId());
            long records = auditRecords(data);

            Response read = api.call(get("/work-orders/" + workOrder.getId()), ta);
            ObjectNode edit = workOrders.shown(workOrder.getId(), ta);
            edit.put("title", "changed");
            Response change = call(patch("/work-orders/" + workOrder.getId()), ta, edit);

            assertEquals(200, read.status());
            assertEquals(asset.getId(), json(read).get("asset").get("id").asLong());
            assertConflict(change, RETIRED);
            assertEquals(before, jdbc.queryForMap("SELECT * FROM work_order WHERE id = ?", workOrder.getId()));
            assertEquals(records, auditRecords(data), "a rejection leaves no record (P-12c)");
        }

        @Test
        void e3_archived_everyChangeToItOrItsChildren_409Retired() {
            CommonData data = data();
            User a = data.o1().admin();
            User t = data.o1().technician();
            Fixture f = workOrders.create(a, t, Status.IN_PROGRESS, true);
            String ta = api.login(a);
            String tt = api.login(t);
            Map<String, Object> before = snapshot(f.id(), f.part());

            Map<String, Supplier<Response>> changes = childChanges(f, ta);
            changes.put("change status", () -> call(patch("/work-orders/" + f.id() + "/change-status"), ta,
                    Map.of("status", "OPEN")));
            changes.put("create comment", () -> call(post("/comments"), tt, Map.of("workOrder", Map.of("id", f.id()),
                    "content", "new")));
            changes.put("edit comment", () -> call(patch("/comments/" + f.comment()), tt, Map.of("content", "x")));
            changes.put("remove comment", () -> api.call(delete("/comments/" + f.comment()), tt));
            changes.forEach((name, change) -> {
                Response response = change.get();
                assertEquals(409, response.status(), name + ": " + response);
                assertEquals(RETIRED, json(response).get("code").asText(), name);
            });

            assertEquals(before, snapshot(f.id(), f.part()));
            assertEquals(0, auditRecords(data));
        }

        @Test
        void ca091_archivedWithRunningTimer_timerAndLaborFrozen() {
            CommonData data = data();
            User a = data.o1().admin();
            User t = data.o1().technician();
            Fixture f = workOrders.create(a, t, Status.IN_PROGRESS, false);
            String ta = api.login(a);
            String tt = api.login(t);
            Long running = json(api.call(post("/labors/work-order/" + f.id()), tt)).get("id").asLong();
            ObjectNode shown = workOrders.shown(f.id(), ta);
            shown.put("archived", true);
            assertEquals(200, call(patch("/work-orders/" + f.id()), ta, shown).status());
            List<Map<String, Object>> labor = jdbc.queryForList("SELECT * FROM labor WHERE work_order_id = ? "
                    + "ORDER BY id", f.id());

            assertConflict(api.call(post("/labors/work-order/" + f.id()).param("start", "false"), tt), RETIRED);
            assertConflict(api.call(post("/labors/work-order/" + f.id()), tt), RETIRED);
            assertConflict(call(patch("/labors/" + running), tt, Map.of("duration", 1, "startedAt",
                    System.currentTimeMillis())), RETIRED);

            assertEquals(labor, jdbc.queryForList("SELECT * FROM labor WHERE work_order_id = ? ORDER BY id",
                    f.id()));
        }

        @Test
        void ca134_restore_thenRemoveTask_recordsRestore() {
            CommonData data = data();
            User a = data.o1().admin();
            WorkOrder workOrder = workOrders.workOrder(data.o1().company());
            String ta = api.login(a);
            JsonNode tasks = json(call(patch("/tasks/work-order/" + workOrder.getId()), ta, List.of(
                    Map.of("label", "one", "taskType", "SUBTASK", "options", List.of()),
                    Map.of("label", "two", "taskType", "SUBTASK", "options", List.of()),
                    Map.of("label", "three", "taskType", "SUBTASK", "options", List.of()))));
            workOrders.moveTo(workOrder.getId(), Status.OPEN, true);

            ObjectNode restore = workOrders.shown(workOrder.getId(), ta);
            restore.put("archived", false);
            Response restored = call(patch("/work-orders/" + workOrder.getId()), ta, restore);
            Response removed = api.call(delete("/tasks/" + tasks.get(0).get("id").asLong()), ta);

            assertEquals(200, restored.status(), restored.toString());
            assertEquals(200, removed.status(), removed.toString());
            assertFalse(reload(workOrder.getId()).isArchived());
            assertEquals(2, count("task", "work_order_id", workOrder.getId()));
            List<Map<String, Object>> audit = audit(data);
            assertEquals(List.of("RESTORE", "DELETE"), audit.stream().map(row -> row.get("action")).toList());
            assertEquals("WORK_ORDER", audit.get(0).get("entity_type"));
            assertEquals("TASK", audit.get(1).get("entity_type"));
        }
    }

    // =====================================================================================================
    // E-6 · Open work order: each child is removed by its endpoint and recorded
    // =====================================================================================================

    @Nested
    class OpenRemovals {

        @Test
        void ca037_eachChildRemoved_200_andRecorded() {
            CommonData data = data();
            User a = data.o1().admin();
            User t = data.o1().technician();
            Fixture f = workOrders.create(a, t, Status.IN_PROGRESS, false);
            String ta = api.login(a);
            String folio = reload(f.id()).getCustomId();

            List<Response> responses = List.of(
                    api.call(delete("/labors/" + f.labor()), ta),
                    api.call(delete("/additional-costs/" + f.cost()), ta),
                    api.call(delete("/tasks/" + f.task()), ta),
                    api.call(delete("/part-quantities/" + f.partQuantity()), ta),
                    api.call(delete("/relations/" + f.relation()), ta),
                    api.call(delete("/comments/" + f.comment()), api.login(t)));

            responses.forEach(response -> assertEquals(200, response.status(), response.toString()));
            List<Map<String, Object>> audit = audit(data);
            assertEquals(List.of("LABOR", "ADDITIONAL_COST", "TASK", "PART_QUANTITY", "RELATION", "COMMENT"),
                    audit.stream().map(row -> row.get("entity_type")).toList());
            assertEquals(List.of(f.labor(), f.cost(), f.task(), f.partQuantity(), f.relation(), f.comment()),
                    audit.stream().map(row -> ((Number) row.get("entity_id")).longValue()).toList());
            audit.forEach(row -> {
                assertEquals("DELETE", row.get("action"));
                assertEquals("API", row.get("source"));
                // D-P12-5: a child is recorded with the folio of its work order
                assertEquals(folio, row.get("entity_folio"));
            });
            assertEquals(t.getId(), ((Number) audit.get(5).get("actor_id")).longValue());
        }

        @Test
        void ca037_replacementsRecordEachOmittedChild() {
            CommonData data = data();
            User a = data.o1().admin();
            Fixture f = workOrders.create(a, data.o1().technician(), Status.OPEN, false);
            String ta = api.login(a);

            assertEquals(200, call(patch("/tasks/work-order/" + f.id()), ta, List.of()).status());
            assertEquals(200, call(patch("/part-quantities/work-order/" + f.id()), ta, List.of()).status());

            List<Map<String, Object>> audit = audit(data);
            assertEquals(List.of("TASK", "PART_QUANTITY"), audit.stream().map(row -> row.get("entity_type"))
                    .toList());
            assertEquals(List.of(f.task(), f.partQuantity()), audit.stream()
                    .map(row -> ((Number) row.get("entity_id")).longValue()).toList());
        }
    }

    // =====================================================================================================
    // E-4, E-5 and E-8 · Closed work order: children and associations do not change; comments are added
    // =====================================================================================================

    @Nested
    class Closed {

        @Test
        void ca040_everyChildChange_409Closed_noChanges() {
            CommonData data = data();
            User a = data.o1().admin();
            Fixture f = workOrders.create(a, data.o1().technician(), Status.COMPLETE, false);
            String ta = api.login(a);
            Map<String, Object> before = snapshot(f.id(), f.part());

            childChanges(f, ta).forEach((name, change) -> {
                Response response = change.get();
                assertEquals(409, response.status(), name + ": " + response);
                assertEquals(CLOSED, json(response).get("code").asText(), name);
            });

            assertEquals(before, snapshot(f.id(), f.part()));
            assertEquals(0, auditRecords(data));
        }

        @Test
        void ca041_timerStartedByAssignedTechnician_409Closed_staysComplete() {
            CommonData data = data();
            User t = data.o1().technician();
            Fixture f = workOrders.create(data.o1().admin(), t, Status.COMPLETE, false);

            Response response = api.call(post("/labors/work-order/" + f.id()), api.login(t));

            assertConflict(response, CLOSED);
            assertEquals(Status.COMPLETE, reload(f.id()).getStatus());
            assertEquals(1, count("labor", "work_order_id", f.id()));
        }

        @Test
        void ca054_patchWithoutFilesNorAssigned_409Closed_associationsKept() {
            CommonData data = data();
            User a = data.o1().admin();
            Fixture f = workOrders.create(a, data.o1().technician(), Status.COMPLETE, false);
            String ta = api.login(a);
            Map<String, Object> before = snapshot(f.id(), f.part());

            ObjectNode withoutFiles = workOrders.shown(f.id(), ta);
            withoutFiles.remove("files");
            ObjectNode withoutAssigned = workOrders.shown(f.id(), ta);
            withoutAssigned.remove("assignedTo");
            ObjectNode withoutCustomers = workOrders.shown(f.id(), ta);
            withoutCustomers.putArray("customers").addObject().put("id", Long.MAX_VALUE);

            assertConflict(call(patch("/work-orders/" + f.id()), ta, withoutFiles), CLOSED);
            assertConflict(call(patch("/work-orders/" + f.id()), ta, withoutAssigned), CLOSED);
            assertConflict(call(patch("/work-orders/" + f.id()), ta, withoutCustomers), CLOSED);
            assertEquals(before, snapshot(f.id(), f.part()));
            assertEquals(0, auditRecords(data), "a rejection leaves no record (P-12c)");
        }

        @Test
        void dP12_1_closed_ownFieldsChange_200_associationsKept() {
            CommonData data = data();
            User a = data.o1().admin();
            Fixture f = workOrders.create(a, data.o1().technician(), Status.COMPLETE, false);
            String ta = api.login(a);
            Map<String, Object> before = snapshot(f.id(), f.part());

            ObjectNode edit = workOrders.shown(f.id(), ta);
            edit.put("title", "corrected after closing");
            Response response = call(patch("/work-orders/" + f.id()), ta, edit);
            // D-P12-2: a replacement without differences changes nothing
            Response tasks = call(patch("/tasks/work-order/" + f.id()), ta, List.of(Map.of("label",
                    jdbc.queryForObject("SELECT tb.label FROM task t JOIN task_base tb ON tb.id = t.task_base_id "
                            + "WHERE t.id = ?", String.class, f.task()), "taskType", "SUBTASK", "options", List.of())));
            Response parts = call(patch("/part-quantities/work-order/" + f.id()), ta, List.of(f.part()));

            assertEquals(200, response.status(), response.toString());
            assertEquals(200, tasks.status(), tasks.toString());
            assertEquals(200, parts.status(), parts.toString());
            assertEquals("corrected after closing", reload(f.id()).getTitle());
            assertEquals(Status.COMPLETE, reload(f.id()).getStatus());
            Map<String, Object> after = snapshot(f.id(), f.part());
            for (String table : List.of("labor", "additional_cost", "task", "part_quantity", "comment",
                    "work_order_files", "work_order_assigned_to", "relation", "part")) {
                assertEquals(before.get(table), after.get(table), table);
            }
        }

        @Test
        void g1_closed_customFieldValues_changed409_equal200() {
            CommonData data = data();
            User a = data.o1().admin();
            CustomField field = customField(data);
            Fixture f = workOrders.create(a, data.o1().technician(), Status.OPEN, false);
            String ta = api.login(a);
            ObjectNode set = workOrders.shown(f.id(), ta);
            set.putArray("customFields").addObject().put("id", field.getId()).put("value", "before");
            assertEquals(200, call(patch("/work-orders/" + f.id()), ta, set).status());
            workOrders.moveTo(f.id(), Status.COMPLETE, false);
            List<Map<String, Object>> values = jdbc.queryForList("SELECT * FROM custom_field_value WHERE "
                    + "work_order_id = ?", f.id());

            ObjectNode changed = workOrders.shown(f.id(), ta);
            changed.putArray("customFields").addObject().put("id", field.getId()).put("value", "after");
            Response rejected = call(patch("/work-orders/" + f.id()), ta, changed);
            ObjectNode equal = workOrders.shown(f.id(), ta);
            equal.putArray("customFields").addObject().put("id", field.getId()).put("value", "before");
            Response accepted = call(patch("/work-orders/" + f.id()), ta, equal);
            workOrders.moveTo(f.id(), Status.COMPLETE, true);
            // Without archived: an edit, not a restoration (D-P12-7)
            changed.remove("archived");
            Response archived = call(patch("/work-orders/" + f.id()), ta, changed);

            assertConflict(rejected, CLOSED);
            assertEquals(200, accepted.status(), accepted.toString());
            assertConflict(archived, RETIRED);
            assertEquals(values, jdbc.queryForList("SELECT * FROM custom_field_value WHERE work_order_id = ?",
                    f.id()));
        }

        @Test
        void ca088_commentOfTechnician_editAndRemove_409Closed() {
            CommonData data = data();
            User t = data.o1().technician();
            Fixture f = workOrders.create(data.o1().admin(), t, Status.COMPLETE, false);
            String tt = api.login(t);
            Map<String, Object> comment = jdbc.queryForMap("SELECT * FROM comment WHERE id = ?", f.comment());

            assertConflict(call(patch("/comments/" + f.comment()), tt, Map.of("content", "edited")), CLOSED);
            assertConflict(api.call(delete("/comments/" + f.comment()), tt), CLOSED);

            assertEquals(comment, jdbc.queryForMap("SELECT * FROM comment WHERE id = ?", f.comment()));
            assertEquals(0, auditRecords(data), "a rejection leaves no record (P-12c)");
        }

        @Test
        void ca118_relationOfClosedParent_delete409Closed() {
            CommonData data = data();
            User a = data.o1().admin();
            Fixture f = workOrders.create(a, data.o1().technician(), Status.COMPLETE, false);

            assertConflict(api.call(delete("/relations/" + f.relation()), api.login(a)), CLOSED);

            assertEquals(1, count("relation", "id", f.relation()));
            assertEquals(0, auditRecords(data), "a rejection leaves no record (P-12c)");
        }

        @Test
        void dP12_3_relationToClosedChild_isCreated() {
            CommonData data = data();
            User a = data.o1().admin();
            Fixture closed = workOrders.create(a, data.o1().technician(), Status.COMPLETE, false);
            WorkOrder open = workOrders.workOrder(data.o1().company());

            Response response = call(post("/relations"), api.login(a), Map.of("parent", Map.of("id", open.getId()),
                    "child", Map.of("id", closed.id()), "relationType", "DUPLICATE_OF"));

            assertEquals(200, response.status(), response.toString());
        }
    }

    // =====================================================================================================
    // E-7 · Reopening
    // =====================================================================================================

    @Nested
    class Reopen {

        @Test
        void ca043_reopenThenRemoveTask_recordsReopenAndDeletion() {
            CommonData data = data();
            User a = data.o1().admin();
            Fixture f = workOrders.create(a, data.o1().technician(), Status.COMPLETE, false);
            String ta = api.login(a);

            Response reopened = call(patch("/work-orders/" + f.id() + "/change-status"), ta, Map.of("status",
                    "IN_PROGRESS"));
            Response removed = api.call(delete("/tasks/" + f.task()), ta);

            assertEquals(200, reopened.status(), reopened.toString());
            assertEquals(200, removed.status(), removed.toString());
            List<Map<String, Object>> audit = audit(data);
            assertEquals(List.of("REOPEN", "DELETE"), audit.stream().map(row -> row.get("action")).toList());
            assertEquals(List.of("WORK_ORDER", "TASK"), audit.stream().map(row -> row.get("entity_type")).toList());
            assertEquals(f.id(), ((Number) audit.get(0).get("entity_id")).longValue());
            assertEquals(a.getId(), ((Number) audit.get(0).get("actor_id")).longValue());
        }

        @Test
        void ca089_technicianWithoutEditPermission_403_noReopen() {
            // Guard: the 403 is already the current answer
            CommonData data = data();
            Fixture f = workOrders.create(data.o1().admin(), data.o1().owner(), Status.COMPLETE, false);

            Response response = call(patch("/work-orders/" + f.id() + "/change-status"),
                    api.login(data.o1().technician()), Map.of("status", "OPEN"));

            assertEquals(403, response.status(), response.toString());
            assertEquals(Status.COMPLETE, reload(f.id()).getStatus());
            assertEquals(0, auditRecords(data));
        }

        @Test
        void completeAgain_isNotAReopening() {
            CommonData data = data();
            User a = data.o1().admin();
            Fixture f = workOrders.create(a, data.o1().technician(), Status.COMPLETE, false);

            Response response = call(patch("/work-orders/" + f.id() + "/change-status"), api.login(a),
                    Map.of("status", "COMPLETE"));

            assertEquals(200, response.status(), response.toString());
            assertEquals(0, auditRecords(data));
        }

        @Test
        void dP12_4_changeStatusOfMissingWorkOrder_404() {
            CommonData data = data();

            Response response = call(patch("/work-orders/999999999/change-status"), api.login(data.o1().admin()),
                    Map.of("status", "OPEN"));

            assertEquals(404, response.status(), response.toString());
        }
    }

    // =====================================================================================================
    // E-11 · Retired user on labor and additional costs
    // =====================================================================================================

    @Nested
    class RetiredUser {

        @Test
        void e11_laborAndCostWithDisabledUser_409UserRetired_previousKept() {
            CommonData data = data();
            User a = data.o1().admin();
            User t = data.o1().technician();
            Fixture f = workOrders.create(a, t, Status.OPEN, false);
            String ta = api.login(a);
            long now = System.currentTimeMillis();
            Long labor = json(call(post("/labors"), ta, Map.of("workOrder", Map.of("id", f.id()), "assignedTo",
                    Map.of("id", t.getId()), "duration", 60, "startedAt", now))).get("id").asLong();
            Long cost = json(call(post("/additional-costs"), ta, Map.of("workOrder", Map.of("id", f.id()),
                    "assignedTo", Map.of("id", t.getId()), "description", "c", "cost", 1))).get("id").asLong();
            jdbc.update("UPDATE own_user SET enabled = false WHERE id = ?", t.getId());

            assertConflict(call(post("/labors"), ta, Map.of("workOrder", Map.of("id", f.id()), "assignedTo",
                    Map.of("id", t.getId()), "duration", 60, "startedAt", now)), "USER_RETIRED");
            assertConflict(call(post("/additional-costs"), ta, Map.of("workOrder", Map.of("id", f.id()),
                    "assignedTo", Map.of("id", t.getId()), "description", "c", "cost", 1)), "USER_RETIRED");
            assertConflict(call(patch("/labors/" + f.labor()), ta, Map.of("assignedTo", Map.of("id", t.getId()),
                    "duration", 60, "startedAt", now)), "USER_RETIRED");
            assertConflict(call(patch("/additional-costs/" + f.cost()), ta, Map.of("assignedTo",
                    Map.of("id", t.getId()), "description", "c", "cost", 1)), "USER_RETIRED");
            // An assignment made before the user was disabled is kept
            assertEquals(200, call(patch("/labors/" + labor), ta, Map.of("assignedTo", Map.of("id", t.getId()),
                    "duration", 120, "startedAt", now)).status());
            assertEquals(200, call(patch("/additional-costs/" + cost), ta, Map.of("assignedTo",
                    Map.of("id", t.getId()), "description", "c2", "cost", 2)).status());
        }
    }

    // =====================================================================================================
    // Cells of the design matrix not covered by the criteria above
    // =====================================================================================================

    @Nested
    class MatrixCells {

        @Test
        void archiveClosed_200_recordsRetire_associationsKept() {
            CommonData data = data();
            User a = data.o1().admin();
            Fixture f = workOrders.create(a, data.o1().technician(), Status.COMPLETE, false);
            String ta = api.login(a);
            Map<String, Object> before = snapshot(f.id(), f.part());
            before.remove("work_order");
            before.remove("revisions");

            ObjectNode shown = workOrders.shown(f.id(), ta);
            shown.put("archived", true);
            Response response = call(patch("/work-orders/" + f.id()), ta, shown);

            assertEquals(200, response.status(), response.toString());
            assertTrue(reload(f.id()).isArchived());
            assertEquals(Status.COMPLETE, reload(f.id()).getStatus());
            Map<String, Object> after = snapshot(f.id(), f.part());
            after.remove("work_order");
            after.remove("revisions");
            assertEquals(before, after);
            assertEquals(List.of("RETIRE"), audit(data).stream().map(row -> row.get("action")).toList());
        }

        @Test
        void archivedFalseOnActive_isAnOrdinaryEdit_noRecord() {
            CommonData data = data();
            User a = data.o1().admin();
            Fixture f = workOrders.create(a, data.o1().technician(), Status.OPEN, false);
            String ta = api.login(a);

            ObjectNode edit = workOrders.shown(f.id(), ta);
            edit.put("archived", false);
            edit.put("title", "edited");
            Response response = call(patch("/work-orders/" + f.id()), ta, edit);

            assertEquals(200, response.status(), response.toString());
            assertEquals("edited", reload(f.id()).getTitle());
            assertFalse(reload(f.id()).isArchived());
            assertEquals(0, auditRecords(data));
        }

        @Test
        void archived_replacementsWithoutDifference_200_noChanges() {
            CommonData data = data();
            User a = data.o1().admin();
            Fixture f = workOrders.create(a, data.o1().technician(), Status.IN_PROGRESS, true);
            String ta = api.login(a);
            Map<String, Object> before = snapshot(f.id(), f.part());
            String label = jdbc.queryForObject("SELECT tb.label FROM task t JOIN task_base tb ON tb.id = "
                    + "t.task_base_id WHERE t.id = ?", String.class, f.task());

            Response tasks = call(patch("/tasks/work-order/" + f.id()), ta, List.of(Map.of("label", label,
                    "taskType", "SUBTASK", "options", List.of())));
            Response parts = call(patch("/part-quantities/work-order/" + f.id()), ta, List.of(f.part()));

            assertEquals(200, tasks.status(), tasks.toString());
            assertEquals(200, parts.status(), parts.toString());
            assertEquals(before, snapshot(f.id(), f.part()));
        }

        @Test
        void open_partQuantityCreated_andRelationChanged_200() {
            CommonData data = data();
            User a = data.o1().admin();
            Fixture f = workOrders.create(a, data.o1().technician(), Status.OPEN, false);
            String ta = api.login(a);
            WorkOrder other = workOrders.workOrder(data.o1().company());

            Response created = call(post("/part-quantities"), ta, Map.of("workOrder", Map.of("id", f.id()),
                    "part", Map.of("id", f.part()), "quantity", 1));
            Response changed = call(patch("/relations/" + f.relation()), ta, Map.of("parent",
                    Map.of("id", f.id()), "child", Map.of("id", other.getId())));

            assertEquals(200, created.status(), created.toString());
            assertEquals(200, changed.status(), changed.toString());
            assertEquals(2, count("part_quantity", "work_order_id", f.id()));
            assertEquals(other.getId(), jdbc.queryForObject("SELECT child_id FROM relation WHERE id = ?", Long.class,
                    f.relation()));
        }
    }

    // =====================================================================================================
    // INV-10 · Another organization never learns the state of a work order of O1
    // =====================================================================================================

    @Nested
    class Isolation {

        @Test
        void inv10_otherOrganization_closedAndArchived_403or404_never409_noChanges() {
            CommonData data = data();
            User a = data.o1().admin();
            Fixture closed = workOrders.create(a, data.o1().technician(), Status.COMPLETE, false);
            Fixture archived = workOrders.create(a, data.o1().technician(), Status.IN_PROGRESS, true);
            Map<String, Object> closedBefore = snapshot(closed.id(), closed.part());
            Map<String, Object> archivedBefore = snapshot(archived.id(), archived.part());
            String o2 = api.login(data.o2().admin());
            long now = System.currentTimeMillis();

            for (Fixture f : List.of(closed, archived)) {
                Map<String, Response> responses = new LinkedHashMap<>();
                responses.put("part quantity", call(post("/part-quantities"), o2, Map.of("workOrder",
                        Map.of("id", f.id()), "part", Map.of("id", f.part()), "quantity", 1)));
                responses.put("labor", call(post("/labors"), o2, Map.of("workOrder", Map.of("id", f.id()),
                        "duration", 60, "startedAt", now)));
                responses.put("additional cost", call(post("/additional-costs"), o2, Map.of("workOrder",
                        Map.of("id", f.id()), "description", "c", "cost", 1)));
                responses.put("comment", call(post("/comments"), o2, Map.of("workOrder", Map.of("id", f.id()),
                        "content", "from O2")));
                responses.forEach((name, response) -> {
                    assertTrue(response.status() == 403 || response.status() == 404, name + " on " + f.id()
                            + ": " + response);
                    assertFalse(response.body().contains(CLOSED) || response.body().contains(RETIRED),
                            name + " on " + f.id() + ": " + response);
                });
            }

            assertEquals(closedBefore, snapshot(closed.id(), closed.part()));
            assertEquals(archivedBefore, snapshot(archived.id(), archived.part()));
            assertEquals(0, auditRecords(data));
            assertEquals(0, count("audit_log", "company_id", data.o2().company().getId()));
        }
    }

    // =====================================================================================================
    // E-10 · Foreign keys
    // =====================================================================================================

    @Nested
    class Schema {

        @Test
        void e10_referencesToWorkOrder_noAction_withIndex() {
            for (String[] fk : List.of(new String[]{"relation", "child_id"}, new String[]{"request", "work_order_id"},
                    new String[]{"work_order_history", "work_order_id"})) {
                assertEquals("a", deleteRule(fk[0], fk[1]), fk[0] + "." + fk[1]);
                assertTrue(indexed(fk[0], fk[1]), fk[0] + "." + fk[1] + " has no index");
            }
            // P-16 changes it, with the stock movements
            assertEquals("c", deleteRule("part_transaction", "work_order_id"));
        }

        private String deleteRule(String table, String column) {
            return jdbc.queryForObject("SELECT c.confdeltype::text FROM pg_constraint c JOIN pg_attribute a ON "
                    + "a.attrelid = c.conrelid AND a.attnum = c.conkey[1] WHERE c.contype = 'f' AND c.conrelid = "
                    + "?::regclass AND a.attname = ? AND c.confrelid = 'work_order'::regclass", String.class, table,
                    column);
        }

        private boolean indexed(String table, String column) {
            return jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM pg_index i JOIN pg_attribute a ON a.attrelid = "
                    + "i.indrelid AND a.attnum = i.indkey[0] WHERE i.indrelid = ?::regclass AND a.attname = ? AND "
                    + "i.indisvalid AND i.indpred IS NULL)", Boolean.class, table, column);
        }
    }
}
