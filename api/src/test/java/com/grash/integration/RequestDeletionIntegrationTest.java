package com.grash.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grash.integration.support.TestApi;
import com.grash.integration.support.TestApi.Response;
import com.grash.integration.support.TestOrganizations;
import com.grash.integration.support.TestOrganizations.CommonData;
import com.grash.integration.support.TestRecords;
import com.grash.model.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * P-13: a request is deleted only while it is pending (PPB-05), and that deletion is recorded (PPB-23). The criteria
 * are those of the module «Solicitudes» of {@code PPB_Criterios_Aceptacion_20112026.md}.
 */
@AutoConfigureMockMvc
class RequestDeletionIntegrationTest extends AbstractIntegrationTest {

    static final String DELETED_BODY = "{\"success\":true,\"message\":\"Deleted successfully\"}";
    static final String REQUEST_CLOSED = "REQUEST_CLOSED";

    private final TestOrganizations organizations;
    private final TestRecords records;
    private final TestApi api;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;
    private final List<CommonData> created = new ArrayList<>();

    @Autowired
    RequestDeletionIntegrationTest(TestOrganizations organizations, TestRecords records, MockMvc mockMvc,
                                   ObjectMapper objectMapper, JdbcTemplate jdbc) {
        this.organizations = organizations;
        this.records = records;
        this.api = new TestApi(mockMvc, objectMapper);
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
    }

    @AfterEach
    void cleanUp() {
        created.forEach(records::deleteRequestPortals);
        created.forEach(organizations::delete);
        created.clear();
    }

    private CommonData data() {
        CommonData data = organizations.create();
        created.add(data);
        return data;
    }

    private long count(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, Long.class, arguments);
    }

    private long requests(Long id) {
        return count("SELECT count(*) FROM request WHERE id = ?", id);
    }

    private List<Map<String, Object>> row(Long request) {
        return jdbc.queryForList("SELECT * FROM request WHERE id = ?", request);
    }

    private long auditRecords(CommonData data) {
        return count("SELECT count(*) FROM audit_log WHERE company_id = ?", data.o1().company().getId());
    }

    private JsonNode json(Response response) {
        try {
            return objectMapper.readTree(response.body());
        } catch (Exception e) {
            throw new AssertionError("not JSON: " + response, e);
        }
    }

    /**
     * A pending request created by A through the API; {@code creator} is then set as its creator (the default
     * technician role cannot create requests).
     */
    private Long request(CommonData data, User creator) {
        Response response = api.call(post("/requests").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"" + TestRecords.unique("P-13 request") + "\"}"), api.login(data.o1().admin()));
        assertTrue(response.is2xx(), response.toString());
        Long request = json(response).get("id").asLong();
        jdbc.update("UPDATE request SET created_by = ? WHERE id = ?", creator.getId(), request);
        return request;
    }

    private Long approved(CommonData data, User creator) {
        Long request = request(data, creator);
        Response response = api.call(patch("/requests/" + request + "/approve")
                .contentType(MediaType.APPLICATION_JSON).content("{}"), api.login(data.o1().admin()));
        assertEquals(200, response.status(), response.toString());
        return request;
    }

    private Long rejected(CommonData data, User creator) {
        Long request = request(data, creator);
        Response response = api.call(patch("/requests/" + request + "/cancel").param("reason", "P-13 rejection"),
                api.login(data.o1().admin()));
        assertEquals(200, response.status(), response.toString());
        return request;
    }

    private Long workOrderOf(Long request) {
        return jdbc.queryForObject("SELECT work_order_id FROM request WHERE id = ?", Long.class, request);
    }

    private Response deleteRequest(Long request, String token) {
        return api.call(delete("/requests/" + request), token);
    }

    /**
     * «409 X» of the common starting data: 409, success false, the code, a message without SQL nor constraint.
     */
    void assertConflict(Response response, String code) {
        assertEquals(409, response.status(), response.toString());
        JsonNode body = json(response);
        assertAll(
                () -> assertFalse(body.get("success").asBoolean(), response.toString()),
                () -> assertEquals(code, body.get("code").asText(), response.toString()),
                () -> assertFalse(body.get("message").asText().isBlank(), response.toString()),
                () -> assertFalse(response.body().toLowerCase().contains("constraint"), response.toString()),
                () -> assertFalse(response.body().toLowerCase().contains("sql"), response.toString()),
                () -> assertFalse(body.has("blockingTypes"), response.toString()));
    }

    private void assertNoSideEffects() {
        assertTrue(mailDouble.calls().isEmpty(), "no mail: " + mailDouble.calls());
        assertTrue(webhookDouble.calls().isEmpty(), "no webhook: " + webhookDouble.calls());
    }

    @Nested
    class Delete {

        @Test
        void ca017_pendingByItsCreator_200_deleted_recorded() {
            CommonData data = data();
            User technician = data.o1().technician();
            Long request = request(data, technician);
            String folio = jdbc.queryForObject("SELECT custom_id FROM request WHERE id = ?", String.class, request);
            String title = jdbc.queryForObject("SELECT title FROM request WHERE id = ?", String.class, request);

            Response response = deleteRequest(request, api.login(technician));

            assertAll(
                    () -> assertEquals(200, response.status(), response.toString()),
                    () -> assertEquals(DELETED_BODY, response.body()),
                    () -> assertEquals(0, requests(request)),
                    () -> assertEquals(List.of("DELETE REQUEST " + request + " " + folio + " " + title + " API "
                                    + technician.getId()),
                            jdbc.queryForList("SELECT action || ' ' || entity_type || ' ' || entity_id || ' ' "
                                            + "|| entity_folio || ' ' || entity_name || ' ' || source || ' ' "
                                            + "|| actor_id FROM audit_log WHERE company_id = ?", String.class,
                                    data.o1().company().getId())));
        }

        @Test
        void ca018_approvedByItsCreator_409_requestAndWorkOrderKeepTheirLink() {
            CommonData data = data();
            User technician = data.o1().technician();
            Long request = approved(data, technician);
            Long workOrder = workOrderOf(request);
            List<Map<String, Object>> before = row(request);
            mailDouble.reset();
            webhookDouble.reset();

            Response response = deleteRequest(request, api.login(technician));

            assertConflict(response, REQUEST_CLOSED);
            assertAll(
                    () -> assertEquals(before, row(request), "the request does not change"),
                    () -> assertEquals(workOrder, workOrderOf(request)),
                    () -> assertEquals(request, jdbc.queryForObject("SELECT parent_request_id FROM work_order "
                            + "WHERE id = ?", Long.class, workOrder), "the work order keeps its request"),
                    () -> assertEquals(0, auditRecords(data)),
                    () -> assertNoSideEffects());
        }

        @Test
        void ca019_rejectedByA_409_unchanged() {
            CommonData data = data();
            Long request = rejected(data, data.o1().admin());
            List<Map<String, Object>> before = row(request);
            mailDouble.reset();
            webhookDouble.reset();

            Response response = deleteRequest(request, api.login(data.o1().admin()));

            assertConflict(response, REQUEST_CLOSED);
            assertAll(
                    () -> assertEquals(before, row(request)),
                    () -> assertEquals(0, auditRecords(data)),
                    () -> assertNoSideEffects());
        }

        @Test
        void approvedByATechnicianWhoIsNotItsCreator_403_beforeThe409() {
            CommonData data = data();
            Long request = approved(data, data.o1().admin());

            Response response = deleteRequest(request, api.login(data.o1().technician()));

            assertAll(
                    () -> assertEquals(403, response.status(), response.toString()),
                    () -> assertFalse(response.body().contains(REQUEST_CLOSED), response.toString()),
                    () -> assertEquals(1, requests(request)));
        }

        @Test
        void closedByTheAdminOfAnotherOrganization_403_beforeThe409() {
            CommonData data = data();
            Long request = rejected(data, data.o1().admin());

            Response response = deleteRequest(request, api.login(data.o2().admin()));

            assertAll(
                    () -> assertEquals(403, response.status(), response.toString()),
                    () -> assertFalse(response.body().contains(REQUEST_CLOSED), response.toString()),
                    () -> assertEquals(1, requests(request)));
        }

        @Test
        void unknownRequest_404() {
            CommonData data = data();

            Response response = deleteRequest(Long.MAX_VALUE, api.login(data.o1().admin()));

            assertEquals(404, response.status(), response.toString());
        }
    }

    /**
     * E-5 (PPB-21, PPB-22): the five keys of P-13 in their target rule, each with an index, read from the catalog.
     */
    @Nested
    class Schema {

        private String key(String table, String column) {
            return jdbc.queryForObject("""
                    SELECT c.confdeltype::text || ' ' || (EXISTS (SELECT 1 FROM pg_index i
                           WHERE i.indrelid = c.conrelid AND i.indkey[0] = c.conkey[1] AND i.indisvalid
                             AND i.indpred IS NULL))::text
                    FROM pg_constraint c
                    JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = c.conkey[1]
                    WHERE c.contype = 'f' AND c.conrelid = ?::regclass AND a.attname = ?""", String.class,
                    table, column);
        }

        @Test
        void referencesToARequestAndToAPortalAreProtected_noAction_indexed() {
            assertAll(
                    () -> assertEquals("a true", key("work_order", "parent_request_id")),
                    () -> assertEquals("a true", key("work_order_aud", "parent_request_id")),
                    () -> assertEquals("a true", key("request", "request_portal_id")));
        }

        @Test
        void compositionChildrenOfARequestAndOfAPortal_cascade_indexed() {
            assertAll(
                    () -> assertEquals("c true", key("custom_field_value", "request_id")),
                    () -> assertEquals("c true", key("request_portal_field", "request_portal_id")));
        }
    }

    /**
     * V-4: an approved request does not go back to pending; its work order is not deleted.
     */
    @Nested
    class ApprovedStaysApproved {

        @Test
        void v4_deletingItsWorkOrder_409_requestStaysApproved() {
            CommonData data = data();
            Long request = approved(data, data.o1().admin());
            Long workOrder = workOrderOf(request);

            Response response = api.call(delete("/work-orders/" + workOrder), api.login(data.o1().admin()));

            assertAll(
                    () -> assertEquals(409, response.status(), response.toString()),
                    () -> assertEquals(workOrder, workOrderOf(request), "the request stays approved"),
                    () -> assertEquals(request, jdbc.queryForObject("SELECT parent_request_id FROM work_order "
                            + "WHERE id = ?", Long.class, workOrder)));
        }
    }
}
