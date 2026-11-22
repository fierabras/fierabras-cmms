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
import com.grash.model.User;
import com.grash.model.WorkOrder;
import com.grash.model.enums.Priority;
import com.grash.model.enums.Status;
import com.grash.repository.FileRepository;
import com.grash.repository.PartRepository;
import com.grash.repository.WorkOrderRepository;
import com.grash.service.DemoDataService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * P-12 characterization: fixes, before any change, what P-12 must keep: the operations on an open work order and
 * their answers, the 403 and 404 answers, the comment on a closed work order, archiving, reopening, the revisions of
 * a work order and the demo data purge. Every test here must keep passing after P-12.
 */
@AutoConfigureMockMvc
class WorkOrderLifecycleCharacterizationIntegrationTest extends AbstractIntegrationTest {

    private static final String DELETED_BODY = "{\"success\":true,\"message\":\"Deleted successfully\"}";

    private final TestOrganizations organizations;
    private final TestRecords records;
    private final TestApi api;
    private final TestWorkOrders workOrders;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;
    private final WorkOrderRepository workOrderRepository;
    private final DemoDataService demoDataService;
    private final List<CommonData> created = new ArrayList<>();

    @Autowired
    WorkOrderLifecycleCharacterizationIntegrationTest(TestOrganizations organizations, TestRecords records,
                                                      MockMvc mockMvc, ObjectMapper objectMapper, JdbcTemplate jdbc,
                                                      WorkOrderRepository workOrderRepository,
                                                      PartRepository partRepository, FileRepository fileRepository,
                                                      DemoDataService demoDataService) {
        this.organizations = organizations;
        this.records = records;
        this.api = new TestApi(mockMvc, objectMapper);
        this.workOrders = new TestWorkOrders(api, objectMapper, records, workOrderRepository, partRepository,
                fileRepository);
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
        this.workOrderRepository = workOrderRepository;
        this.demoDataService = demoDataService;
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

    private JsonNode json(Response response) {
        try {
            return objectMapper.readTree(response.body());
        } catch (Exception e) {
            throw new AssertionError("not JSON: " + response, e);
        }
    }

    private long count(String table, String column, Long id) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + column + " = ?", Long.class, id);
    }

    private double stock(Long part) {
        return jdbc.queryForObject("SELECT quantity FROM part WHERE id = ?", Double.class, part);
    }

    private Status status(Long workOrder) {
        return workOrderRepository.findById(workOrder).orElseThrow().getStatus();
    }

    private List<Map<String, Object>> children(Long id) {
        return jdbc.queryForList(("SELECT (SELECT count(*) FROM labor WHERE work_order_id = ?1) AS labor, "
                + "(SELECT count(*) FROM additional_cost WHERE work_order_id = ?1) AS cost, "
                + "(SELECT count(*) FROM task WHERE work_order_id = ?1) AS task, "
                + "(SELECT count(*) FROM part_quantity WHERE work_order_id = ?1) AS part_quantity, "
                + "(SELECT count(*) FROM relation WHERE parent_id = ?1) AS relation, "
                + "(SELECT count(*) FROM comment WHERE work_order_id = ?1) AS comment, "
                + "(SELECT count(*) FROM work_order_files WHERE work_order_id = ?1) AS files, "
                + "(SELECT count(*) FROM work_order_assigned_to WHERE work_order_id = ?1) AS assigned")
                .replace("?1", String.valueOf(id)));
    }

    // =====================================================================================================
    // Open work order: its children are created, changed and removed
    // =====================================================================================================

    @Test
    void open_childrenAreChanged_200() {
        CommonData data = data();
        User a = data.o1().admin();
        User t = data.o1().technician();
        Fixture f = workOrders.create(a, t, Status.IN_PROGRESS, false);
        String ta = api.login(a);

        assertAll(
                () -> assertEquals(200, workOrders.call(post("/labors"), ta, Map.of("workOrder",
                        Map.of("id", f.id()), "duration", 60, "startedAt", System.currentTimeMillis())).status()),
                () -> assertEquals(200, workOrders.call(patch("/labors/" + f.labor()), ta, Map.of("duration", 7200,
                        "hourlyRate", 10, "startedAt", System.currentTimeMillis())).status()),
                () -> assertEquals(200, workOrders.call(post("/additional-costs"), ta, Map.of("workOrder",
                        Map.of("id", f.id()), "description", "c", "cost", 1)).status()),
                () -> assertEquals(200, workOrders.call(patch("/additional-costs/" + f.cost()), ta,
                        Map.of("cost", 99, "description", "x")).status()),
                () -> assertEquals(200, workOrders.call(patch("/tasks/" + f.task()), ta,
                        Map.of("value", "COMPLETE")).status()),
                () -> assertEquals(200, workOrders.call(patch("/part-quantities/" + f.partQuantity()), ta,
                        Map.of("quantity", 2)).status()),
                () -> assertEquals(200, workOrders.call(patch("/comments/" + f.comment()), api.login(t),
                        Map.of("content", "edited")).status()),
                () -> assertEquals(Status.IN_PROGRESS, status(f.id())));
    }

    @Test
    void open_eachChildIsRemovedByItsEndpoint_200() {
        CommonData data = data();
        User a = data.o1().admin();
        User t = data.o1().technician();
        Fixture f = workOrders.create(a, t, Status.IN_PROGRESS, false);
        String ta = api.login(a);
        assertEquals(9.0, stock(f.part()));

        List<Response> responses = List.of(
                api.call(delete("/labors/" + f.labor()), ta),
                api.call(delete("/additional-costs/" + f.cost()), ta),
                api.call(delete("/tasks/" + f.task()), ta),
                api.call(delete("/part-quantities/" + f.partQuantity()), ta),
                api.call(delete("/relations/" + f.relation()), ta),
                api.call(delete("/comments/" + f.comment()), api.login(t)));

        responses.forEach(response -> {
            assertEquals(200, response.status(), response.toString());
            assertEquals(DELETED_BODY, response.body());
        });
        Map<String, Object> left = children(f.id()).get(0);
        assertAll(
                () -> assertEquals(0L, left.get("labor")),
                () -> assertEquals(0L, left.get("cost")),
                () -> assertEquals(0L, left.get("task")),
                () -> assertEquals(0L, left.get("part_quantity")),
                () -> assertEquals(0L, left.get("relation")),
                () -> assertEquals(0L, left.get("comment")),
                // P-16 changes how stock moves; P-12 keeps it: removing one consumption gives the stock back
                () -> assertEquals(10.0, stock(f.part())),
                () -> assertEquals(Status.IN_PROGRESS, status(f.id())));
    }

    @Test
    void open_collectionReplacements_removeWhatIsOmitted() {
        CommonData data = data();
        User a = data.o1().admin();
        Fixture f = workOrders.create(a, data.o1().technician(), Status.OPEN, false);
        String ta = api.login(a);

        assertEquals(200, workOrders.call(patch("/tasks/work-order/" + f.id()), ta, List.of()).status());
        assertEquals(200, workOrders.call(patch("/part-quantities/work-order/" + f.id()), ta, List.of()).status());

        assertAll(
                () -> assertEquals(0, count("task", "work_order_id", f.id())),
                () -> assertEquals(0, count("part_quantity", "work_order_id", f.id())),
                // P-16 adds the compensating movement here; P-12 keeps the current stock
                () -> assertEquals(9.0, stock(f.part())));
    }

    @Test
    void open_timerStartedByAssignedTechnician_movesToInProgress() {
        // CA-PPB-092 (guard): current behavior kept
        CommonData data = data();
        User t = data.o1().technician();
        WorkOrder workOrder = workOrders.workOrder(data.o1().company(), t);

        Response response = api.call(post("/labors/work-order/" + workOrder.getId()), api.login(t));

        assertEquals(200, response.status(), response.toString());
        assertEquals("RUNNING", json(response).get("status").asText());
        assertEquals(Status.IN_PROGRESS, status(workOrder.getId()));
    }

    // =====================================================================================================
    // Answers that stay: 403 and 404
    // =====================================================================================================

    @Test
    void withoutPermission_403_andMissing_404() {
        CommonData data = data();
        User a = data.o1().admin();
        User t = data.o1().technician();
        // T is not assigned to this work order and did not create it
        Fixture f = workOrders.create(a, data.o1().owner(), Status.OPEN, false);
        String tt = api.login(t);
        String ta = api.login(a);

        assertAll(
                () -> assertEquals(403, api.call(delete("/work-orders/" + f.id()), tt).status()),
                () -> assertEquals(404, api.call(delete("/work-orders/999999999"), ta).status()),
                () -> assertEquals(403, api.call(delete("/labors/" + f.labor()), tt).status()),
                () -> assertEquals(403, api.call(delete("/additional-costs/" + f.cost()), tt).status()),
                () -> assertEquals(403, api.call(delete("/tasks/" + f.task()), tt).status()),
                () -> assertEquals(403, api.call(delete("/relations/" + f.relation()), tt).status()),
                () -> assertEquals(404, api.call(delete("/labors/999999999"), ta).status()),
                () -> assertEquals(403, workOrders.call(patch("/work-orders/" + f.id() + "/change-status"), tt,
                        Map.of("status", "IN_PROGRESS")).status()),
                () -> assertEquals(Status.OPEN, status(f.id())),
                () -> assertEquals(1, count("labor", "work_order_id", f.id())));
    }

    // =====================================================================================================
    // Closed work order: a comment can be added; it can be reopened
    // =====================================================================================================

    @Test
    void closed_commentCreatedByTechnician_keepsStatus() {
        // CA-PPB-042 (guard)
        CommonData data = data();
        User t = data.o1().technician();
        Fixture f = workOrders.create(data.o1().admin(), t, Status.COMPLETE, false);
        // P-12c (H1-07): every row of the work order, its children and associations, except the new comment
        Map<String, Object> before = TestWorkOrders.rows(jdbc, f.id(), f.part());

        Response response = workOrders.call(post("/comments"), api.login(t), Map.of("workOrder",
                Map.of("id", f.id()), "content", "after closing"));

        assertEquals(200, response.status(), response.toString());
        assertEquals(Status.COMPLETE, status(f.id()));
        assertEquals(2, count("comment", "work_order_id", f.id()));
        Map<String, Object> after = TestWorkOrders.rows(jdbc, f.id(), f.part());
        before.remove("comment");
        after.remove("comment");
        assertEquals(before, after);
    }

    @Test
    void closed_reopenedByAdmin_200() {
        CommonData data = data();
        User a = data.o1().admin();
        Fixture f = workOrders.create(a, data.o1().technician(), Status.COMPLETE, false);

        Response response = workOrders.call(patch("/work-orders/" + f.id() + "/change-status"), api.login(a),
                Map.of("status", "IN_PROGRESS"));

        assertEquals(200, response.status(), response.toString());
        assertEquals(Status.IN_PROGRESS, status(f.id()));
    }

    // =====================================================================================================
    // Archiving
    // =====================================================================================================

    @Test
    void archive_keepsChildren_andRunningTimer() {
        // CA-PPB-022 and CA-PPB-090 (guards)
        CommonData data = data();
        User a = data.o1().admin();
        User t = data.o1().technician();
        Fixture f = workOrders.create(a, t, Status.IN_PROGRESS, false);
        String ta = api.login(a);
        assertEquals(200, api.call(post("/labors/work-order/" + f.id()), api.login(t)).status());
        List<Map<String, Object>> children = children(f.id());
        List<Map<String, Object>> labor = jdbc.queryForList("SELECT * FROM labor WHERE work_order_id = ? ORDER BY id",
                f.id());

        ObjectNode shown = workOrders.shown(f.id(), ta);
        shown.put("archived", true);
        Response response = workOrders.call(patch("/work-orders/" + f.id()), ta, shown);

        assertEquals(200, response.status(), response.toString());
        assertTrue(workOrderRepository.findById(f.id()).orElseThrow().isArchived());
        assertEquals(children, children(f.id()));
        assertEquals(labor, jdbc.queryForList("SELECT * FROM labor WHERE work_order_id = ? ORDER BY id", f.id()));
    }

    @Test
    void archived_isReadByIdWithItsReferences() {
        // CA-PPB-026, first half (guard)
        CommonData data = data();
        User a = data.o1().admin();
        Asset asset = records.asset(data.o1().company());
        WorkOrder workOrder = records.workOrder(data.o1().company(), asset, Status.OPEN, false);
        workOrders.moveTo(workOrder.getId(), Status.OPEN, true);

        Response response = api.call(get("/work-orders/" + workOrder.getId()), api.login(a));

        assertEquals(200, response.status(), response.toString());
        assertEquals(asset.getId(), json(response).get("asset").get("id").asLong());
        assertTrue(json(response).get("archived").asBoolean());
    }

    // =====================================================================================================
    // Revisions and demo data
    // =====================================================================================================

    @Test
    void change_addsRevision_andKeepsPreviousOnes() {
        // CA-PPB-093 (guard)
        CommonData data = data();
        User a = data.o1().admin();
        WorkOrder workOrder = records.workOrder(data.o1().company(), null, Status.OPEN, false);
        workOrder.setTitle(workOrder.getTitle() + " 2");
        workOrder = workOrderRepository.saveAndFlush(workOrder);
        workOrder.setTitle(workOrder.getTitle() + " 3");
        workOrderRepository.saveAndFlush(workOrder);
        String revisions = "SELECT * FROM work_order_aud WHERE id = ? ORDER BY rev";
        List<Map<String, Object>> before = jdbc.queryForList(revisions, workOrder.getId());
        assertEquals(3, before.size());
        String ta = api.login(a);

        ObjectNode shown = workOrders.shown(workOrder.getId(), ta);
        shown.put("priority", Priority.HIGH.name());
        assertEquals(200, workOrders.call(patch("/work-orders/" + workOrder.getId()), ta, shown).status());

        List<Map<String, Object>> after = jdbc.queryForList(revisions, workOrder.getId());
        assertEquals(4, after.size());
        assertEquals(before, after.subList(0, 3));
    }

    @Test
    void demoPurge_withOnlyDemoData_deletesIt() throws InterruptedException {
        // E-12 of the P-12 order
        CommonData data = data();
        User a = data.o1().admin();
        Long companyId = data.o1().company().getId();
        demoDataService.createDemoData(a, data.o1().company());
        // createDemoData is asynchronous
        for (int i = 0; i < 150 && count("request", "company_id", companyId) < 1; i++) Thread.sleep(200);
        assertEquals(3, count("work_order", "company_id", companyId));

        Response response = api.call(delete("/demo/demo-data"), api.login(a));

        assertEquals(200, response.status(), response.toString());
        assertAll(
                () -> assertEquals(0, count("work_order", "company_id", companyId)),
                () -> assertEquals(0, count("request", "company_id", companyId)),
                () -> assertEquals(0, count("labor", "company_id", companyId)),
                () -> assertEquals(0, count("asset", "company_id", companyId)));
    }
}
