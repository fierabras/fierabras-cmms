package com.grash.integration.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.grash.integration.support.TestApi.Response;
import com.grash.model.Company;
import com.grash.model.File;
import com.grash.model.Part;
import com.grash.model.User;
import com.grash.model.WorkOrder;
import com.grash.model.enums.Status;
import com.grash.repository.FileRepository;
import com.grash.repository.PartRepository;
import com.grash.repository.WorkOrderRepository;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * P-12: a work order with one child of each kind (labor, additional cost, task, part consumption, relation,
 * comment), one file and an assigned technician, created through the API while the work order is open, and then
 * moved to the state a test needs. Removing the organization ({@link TestOrganizations#delete}) removes them.
 */
public final class TestWorkOrders {

    /**
     * The identifiers of the work order {@code id}, of its children and of the records they use.
     */
    public record Fixture(Long id, Long labor, Long cost, Long task, Long partQuantity, Long part, Long relation,
                          Long relatedWorkOrder, Long comment, Long file) {
    }

    private final TestApi api;
    private final ObjectMapper objectMapper;
    private final TestRecords records;
    private final WorkOrderRepository workOrderRepository;
    private final PartRepository partRepository;
    private final FileRepository fileRepository;

    public TestWorkOrders(TestApi api, ObjectMapper objectMapper, TestRecords records,
                          WorkOrderRepository workOrderRepository, PartRepository partRepository,
                          FileRepository fileRepository) {
        this.api = api;
        this.objectMapper = objectMapper;
        this.records = records;
        this.workOrderRepository = workOrderRepository;
        this.partRepository = partRepository;
        this.fileRepository = fileRepository;
    }

    /**
     * An open work order assigned to {@code assigned}, without children and without creator.
     */
    public WorkOrder workOrder(Company company, User... assigned) {
        WorkOrder workOrder = records.workOrder(company, null, Status.OPEN, false);
        workOrder.setAssignedTo(new ArrayList<>(List.of(assigned)));
        return workOrderRepository.saveAndFlush(workOrder);
    }

    /**
     * An open work order of the organization of {@code admin}, assigned to {@code technician}, with one child of
     * each kind created by {@code admin} (the comment by {@code technician}) and one file, then moved to
     * {@code status} and archived if {@code archived}.
     */
    public Fixture create(User admin, User technician, Status status, boolean archived) {
        Company company = admin.getCompany();
        WorkOrder workOrder = workOrder(company, technician);
        Long id = workOrder.getId();
        String adminToken = api.login(admin);

        File file = new File();
        file.setName(TestRecords.unique("P-12 file"));
        file.setPath("p12/" + file.getName());
        file.setCompany(company);
        file = fileRepository.saveAndFlush(file);
        workOrder.setFiles(new ArrayList<>(List.of(file)));
        workOrderRepository.saveAndFlush(workOrder);

        Long labor = id(call(post("/labors"), adminToken, Map.of("workOrder", Map.of("id", id),
                "duration", 3600, "hourlyRate", 10, "startedAt", System.currentTimeMillis())));
        Long cost = id(call(post("/additional-costs"), adminToken, Map.of("workOrder", Map.of("id", id),
                "description", TestRecords.unique("P-12 cost"), "cost", 25)));
        Long task = json(call(patch("/tasks/work-order/" + id), adminToken,
                List.of(Map.of("label", TestRecords.unique("P-12 task"), "taskType", "SUBTASK",
                        "options", List.of())))).get(0).get("id").asLong();
        Part part = records.part(company, admin);
        part.setQuantity(10);
        part = partRepository.saveAndFlush(part);
        Long partQuantity = json(call(patch("/part-quantities/work-order/" + id), adminToken,
                List.of(part.getId()))).get(0).get("id").asLong();
        WorkOrder related = records.workOrder(company, null, Status.OPEN, false);
        Long relation = id(call(post("/relations"), adminToken, Map.of("parent", Map.of("id", id),
                "child", Map.of("id", related.getId()), "relationType", "RELATED_TO")));
        Long comment = id(call(post("/comments"), api.login(technician), Map.of("workOrder", Map.of("id", id),
                "content", TestRecords.unique("P-12 comment"))));

        moveTo(id, status, archived);
        return new Fixture(id, labor, cost, task, partQuantity, part.getId(), relation, related.getId(), comment,
                file.getId());
    }

    /**
     * Sets the status and the archived flag directly, without the effects of a status change.
     */
    public void moveTo(Long id, Status status, boolean archived) {
        WorkOrder workOrder = workOrderRepository.findById(id).orElseThrow();
        workOrder.setStatus(status);
        workOrder.setArchived(archived);
        workOrderRepository.saveAndFlush(workOrder);
    }

    /**
     * Every row that a change to the work order {@code id} or to its children could touch, for the "no changes"
     * checks: the work order, its children and associations, its relations, the stock of {@code part} (when not
     * null) and the number of its revisions.
     */
    public static Map<String, Object> rows(JdbcTemplate jdbc, Long id, Long part) {
        Map<String, Object> rows = new LinkedHashMap<>();
        rows.put("work_order", jdbc.queryForList("SELECT * FROM work_order WHERE id = ?", id));
        for (String table : List.of("labor", "additional_cost", "task", "part_quantity", "comment",
                "work_order_files", "work_order_assigned_to", "work_order_customers", "custom_field_value",
                "part_transaction")) {
            rows.put(table, jdbc.queryForList("SELECT * FROM " + table + " WHERE work_order_id = ? ORDER BY 1",
                    id));
        }
        rows.put("relation", jdbc.queryForList("SELECT * FROM relation WHERE parent_id = ? OR child_id = ? "
                + "ORDER BY id", id, id));
        if (part != null) rows.put("part", jdbc.queryForList("SELECT quantity FROM part WHERE id = ?", part));
        rows.put("revisions", jdbc.queryForObject("SELECT count(*) FROM work_order_aud WHERE id = ?", Long.class,
                id));
        return rows;
    }

    /**
     * The work order as {@code GET /work-orders/{id}} returns it: what the web app sends back on a {@code PATCH}.
     */
    public ObjectNode shown(Long id, String token) {
        return (ObjectNode) json(api.call(get("/work-orders/" + id), token));
    }

    /**
     * Calls the API with {@code body} as JSON.
     */
    public Response call(MockHttpServletRequestBuilder request, String token, Object body) {
        try {
            return api.call(request.contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body)), token);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode json(Response response) {
        if (!response.is2xx()) throw new AssertionError("fixture request failed: " + response);
        try {
            return objectMapper.readTree(response.body());
        } catch (Exception e) {
            throw new AssertionError("not JSON: " + response, e);
        }
    }

    private Long id(Response response) {
        return json(response).get("id").asLong();
    }
}
