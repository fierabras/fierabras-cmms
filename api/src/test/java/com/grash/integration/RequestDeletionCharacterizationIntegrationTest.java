package com.grash.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.grash.integration.support.TestApi;
import com.grash.integration.support.TestApi.Response;
import com.grash.integration.support.TestOrganizations;
import com.grash.integration.support.TestOrganizations.CommonData;
import com.grash.integration.support.TestRecords;
import com.grash.model.Company;
import com.grash.model.RequestPortal;
import com.grash.model.RequestPortalField;
import com.grash.model.User;
import com.grash.model.enums.PortalFieldType;
import com.grash.repository.RequestPortalRepository;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * P-13 characterization: fixes how the deletion and the edition of a request, its approval with a disabled user, the
 * deletion of a request portal and the demo purge behaved before any change, and the foreign keys that point to
 * {@code request} and {@code request_portal}. The 403 and 404 answers must keep passing after P-13; the tests that
 * show a closed request deleted or edited, a disabled user assigned or a portal with requests deleted are updated to
 * the contract of P-13, as step 8 of the order allows.
 */
@AutoConfigureMockMvc
class RequestDeletionCharacterizationIntegrationTest extends AbstractIntegrationTest {

    private final TestOrganizations organizations;
    private final TestRecords records;
    private final TestApi api;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;
    private final RequestPortalRepository requestPortalRepository;
    private final List<CommonData> created = new ArrayList<>();

    @Autowired
    RequestDeletionCharacterizationIntegrationTest(TestOrganizations organizations, TestRecords records,
                                                   MockMvc mockMvc, ObjectMapper objectMapper, JdbcTemplate jdbc,
                                                   RequestPortalRepository requestPortalRepository) {
        this.organizations = organizations;
        this.records = records;
        this.api = new TestApi(mockMvc, objectMapper);
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
        this.requestPortalRepository = requestPortalRepository;
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

    private long auditRecords(CommonData data) {
        return count("SELECT count(*) FROM audit_log WHERE company_id = ?", data.o1().company().getId());
    }

    private Long id(Response response) {
        assertTrue(response.is2xx(), response.toString());
        try {
            return objectMapper.readTree(response.body()).get("id").asLong();
        } catch (Exception e) {
            throw new AssertionError("not JSON: " + response, e);
        }
    }

    /**
     * Creates a pending request through the API as {@code creator}, optionally with {@code primaryUser}.
     */
    private Long request(User creator, User primaryUser) {
        String body = "{\"title\":\"" + TestRecords.unique("P-13 request") + "\""
                + (primaryUser == null ? "" : ",\"primaryUser\":{\"id\":" + primaryUser.getId() + "}") + "}";
        return id(api.call(post("/requests").contentType(MediaType.APPLICATION_JSON).content(body),
                api.login(creator)));
    }

    /**
     * A pending request whose creator is T. The default technician role cannot create requests (403), so A creates
     * it and T is set as its creator, as CA-PPB-017 states.
     */
    private Long requestOfTechnician(CommonData data) {
        Long request = request(data.o1().admin(), null);
        jdbc.update("UPDATE request SET created_by = ? WHERE id = ?", data.o1().technician().getId(), request);
        return request;
    }

    private Response approve(Long request, String token) {
        return api.call(patch("/requests/" + request + "/approve").contentType(MediaType.APPLICATION_JSON)
                .content("{}"), token);
    }

    private Response reject(Long request, String token) {
        return api.call(patch("/requests/" + request + "/cancel").param("reason", "P-13 rejection"), token);
    }

    private Response deleteRequest(Long request, String token) {
        return api.call(delete("/requests/" + request), token);
    }

    private Response editTitle(Long request, String title, String token) {
        return api.call(patch("/requests/" + request).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"" + title + "\"}"), token);
    }

    private Long workOrderOf(Long request) {
        return jdbc.queryForObject("SELECT work_order_id FROM request WHERE id = ?", Long.class, request);
    }

    private void disable(User user) {
        jdbc.update("UPDATE own_user SET enabled = false WHERE id = ?", user.getId());
    }

    private RequestPortal portal(Company company, int fields) {
        RequestPortal portal = new RequestPortal();
        portal.setTitle(TestRecords.unique("P-13 portal"));
        portal.setUuid(UUID.randomUUID().toString());
        portal.setCompany(company);
        List<PortalFieldType> types = List.of(PortalFieldType.DESCRIPTION, PortalFieldType.CONTACT,
                PortalFieldType.IMAGE);
        for (int i = 0; i < fields; i++) {
            RequestPortalField field = new RequestPortalField();
            field.setType(types.get(i));
            field.setRequestPortal(portal);
            field.setCompany(company);
            portal.getFields().add(field);
        }
        return requestPortalRepository.saveAndFlush(portal);
    }

    private Response deletePortal(Long portal, String token) {
        return api.call(delete("/request-portals/" + portal), token);
    }

    @Nested
    class DeleteRequest {

        @Test
        void pendingByItsCreatorWithoutDeleteOther_200_deleted_recorded() {
            CommonData data = data();
            Long request = requestOfTechnician(data);

            Response response = deleteRequest(request, api.login(data.o1().technician()));

            assertAll(
                    () -> assertEquals(200, response.status(), response.toString()),
                    () -> assertEquals(0, requests(request)),
                    () -> assertEquals(1, auditRecords(data), "P-13: the deletion is recorded"));
        }

        @Test
        void approvedByItsCreator_409_workOrderKeepsItsRequest() {
            // TD-009 closed by P-13: an approved request is not deleted
            CommonData data = data();
            Long request = requestOfTechnician(data);
            Response approved = approve(request, api.login(data.o1().admin()));
            assertEquals(200, approved.status(), approved.toString());
            Long workOrder = workOrderOf(request);

            Response response = deleteRequest(request, api.login(data.o1().technician()));

            assertAll(
                    () -> assertEquals(409, response.status(), response.toString()),
                    () -> assertTrue(response.body().contains("REQUEST_CLOSED"), response.toString()),
                    () -> assertEquals(1, requests(request)),
                    () -> assertEquals(1, count("SELECT count(*) FROM work_order WHERE id = ? "
                            + "AND parent_request_id = ?", workOrder, request), "P-13: the work order keeps it"));
        }

        @Test
        void rejectedByA_409_kept() {
            CommonData data = data();
            String admin = api.login(data.o1().admin());
            Long request = request(data.o1().admin(), null);
            assertEquals(200, reject(request, admin).status());

            Response response = deleteRequest(request, admin);

            assertAll(
                    () -> assertEquals(409, response.status(), response.toString()),
                    () -> assertTrue(response.body().contains("REQUEST_CLOSED"), response.toString()),
                    () -> assertEquals(1, requests(request)));
        }

        @Test
        void technicianOnARequestOfAnotherUser_403_kept() {
            CommonData data = data();
            Long request = request(data.o1().admin(), null);

            Response response = deleteRequest(request, api.login(data.o1().technician()));

            assertAll(
                    () -> assertEquals(403, response.status(), response.toString()),
                    () -> assertEquals(1, requests(request)),
                    () -> assertEquals(0, auditRecords(data)));
        }

        @Test
        void adminOfAnotherOrganization_rejected_kept() {
            CommonData data = data();
            Long request = request(data.o1().admin(), null);

            Response response = deleteRequest(request, api.login(data.o2().admin()));

            assertAll(
                    () -> assertEquals(403, response.status(), response.toString()),
                    () -> assertEquals(1, requests(request)));
        }

        @Test
        void unknownRequest_404() {
            CommonData data = data();

            Response response = deleteRequest(Long.MAX_VALUE, api.login(data.o1().admin()));

            assertEquals(404, response.status(), response.toString());
        }
    }

    @Nested
    class EditRequest {

        @Test
        void rejected_200_edited() {
            // TD-P12b-02: only the approved request is rejected
            CommonData data = data();
            String admin = api.login(data.o1().admin());
            Long request = request(data.o1().admin(), null);
            assertEquals(200, reject(request, admin).status());

            Response response = editTitle(request, "P-13 edited", admin);

            assertAll(
                    () -> assertEquals(200, response.status(), response.toString()),
                    () -> assertEquals("P-13 edited", jdbc.queryForObject("SELECT title FROM request WHERE id = ?",
                            String.class, request)));
        }

        @Test
        void approved_406_notEdited() {
            CommonData data = data();
            String admin = api.login(data.o1().admin());
            Long request = request(data.o1().admin(), null);
            assertEquals(200, approve(request, admin).status());

            Response response = editTitle(request, "P-13 edited", admin);

            assertAll(
                    () -> assertEquals(406, response.status(), response.toString()),
                    () -> assertNotEquals("P-13 edited", jdbc.queryForObject("SELECT title FROM request "
                            + "WHERE id = ?", String.class, request)));
        }

        @Test
        void approvedByTechnicianWithoutEditOther_406_beforeThe403() {
            // the 406 of the approved request comes before the permission check
            CommonData data = data();
            String admin = api.login(data.o1().admin());
            Long request = request(data.o1().admin(), null);
            assertEquals(200, approve(request, admin).status());

            Response response = editTitle(request, "P-13 edited", api.login(data.o1().technician()));

            assertEquals(406, response.status(), response.toString());
        }
    }

    @Nested
    class DisabledUser {

        @Test
        void approvedWithADisabledPrimaryUser_200_workOrderAssignedToHim() {
            CommonData data = data();
            User technician = data.o1().technician();
            Long request = request(data.o1().admin(), technician);
            disable(technician);

            Response response = approve(request, api.login(data.o1().admin()));

            assertAll(
                    () -> assertEquals(200, response.status(), response.toString()),
                    () -> assertEquals(technician.getId(), jdbc.queryForObject("SELECT primary_user_id "
                            + "FROM work_order WHERE id = ?", Long.class, workOrderOf(request))));
        }

        @Test
        void createdWithADisabledPrimaryUser_200() {
            CommonData data = data();
            User technician = data.o1().technician();
            disable(technician);

            Long request = request(data.o1().admin(), technician);

            assertEquals(technician.getId(), jdbc.queryForObject("SELECT primary_user_id FROM request WHERE id = ?",
                    Long.class, request));
        }

        @Test
        void editedWithADisabledPrimaryUser_200() {
            CommonData data = data();
            User technician = data.o1().technician();
            String admin = api.login(data.o1().admin());
            Long request = request(data.o1().admin(), null);
            disable(technician);

            Response response = api.call(patch("/requests/" + request).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"title\":\"P-13\",\"primaryUser\":{\"id\":" + technician.getId() + "}}"), admin);

            assertAll(
                    () -> assertEquals(200, response.status(), response.toString()),
                    () -> assertEquals(technician.getId(), jdbc.queryForObject("SELECT primary_user_id "
                            + "FROM request WHERE id = ?", Long.class, request)));
        }
    }

    @Nested
    class DeletePortal {

        @Test
        void withARequest_200_deleted_requestLosesItsPortal() {
            CommonData data = data();
            RequestPortal portal = portal(data.o1().company(), 1);
            Long request = request(data.o1().admin(), null);
            jdbc.update("UPDATE request SET request_portal_id = ? WHERE id = ?", portal.getId(), request);

            Response response = deletePortal(portal.getId(), api.login(data.o1().admin()));

            assertAll(
                    () -> assertEquals(200, response.status(), response.toString()),
                    () -> assertEquals(0, count("SELECT count(*) FROM request_portal WHERE id = ?", portal.getId())),
                    () -> assertEquals(1, count("SELECT count(*) FROM request WHERE id = ? "
                            + "AND request_portal_id IS NULL", request), "request.request_portal_id is SET NULL"));
        }

        @Test
        void withoutRequests_200_portalAndFieldsDeleted_notRecorded() {
            CommonData data = data();
            RequestPortal portal = portal(data.o1().company(), 3);

            Response response = deletePortal(portal.getId(), api.login(data.o1().admin()));

            assertAll(
                    () -> assertEquals(200, response.status(), response.toString()),
                    () -> assertEquals(0, count("SELECT count(*) FROM request_portal WHERE id = ?", portal.getId())),
                    () -> assertEquals(0, count("SELECT count(*) FROM request_portal_field WHERE request_portal_id = ?",
                            portal.getId())),
                    () -> assertEquals(0, auditRecords(data), "today the deletion is not recorded"));
        }

        @Test
        void technicianWithoutSettings_403_kept() {
            CommonData data = data();
            RequestPortal portal = portal(data.o1().company(), 1);

            Response response = deletePortal(portal.getId(), api.login(data.o1().technician()));

            assertAll(
                    () -> assertEquals(403, response.status(), response.toString()),
                    () -> assertEquals(1, count("SELECT count(*) FROM request_portal WHERE id = ?", portal.getId())));
        }

        @Test
        void adminOfAnotherOrganization_rejected_kept() {
            CommonData data = data();
            RequestPortal portal = portal(data.o1().company(), 1);

            Response response = deletePortal(portal.getId(), api.login(data.o2().admin()));

            assertAll(
                    () -> assertEquals(403, response.status(), response.toString()),
                    () -> assertEquals(1, count("SELECT count(*) FROM request_portal WHERE id = ?", portal.getId())));
        }

        @Test
        void unknownPortal_404() {
            CommonData data = data();

            Response response = deletePortal(Long.MAX_VALUE, api.login(data.o1().admin()));

            assertEquals(404, response.status(), response.toString());
        }
    }

    @Nested
    class DemoPurge {

        private Response purge(CommonData data) {
            return api.call(delete("/demo/demo-data"), api.login(data.o1().admin()));
        }

        @Test
        void pendingDemoRequest_200_deleted() {
            CommonData data = data();
            Long request = request(data.o1().admin(), null);
            jdbc.update("UPDATE request SET is_demo = true WHERE id = ?", request);

            Response response = purge(data);

            assertAll(
                    () -> assertEquals(200, response.status(), response.toString()),
                    () -> assertEquals(0, requests(request)));
        }

        @Test
        void approvedDemoRequest_200_deleted_workOrderThatIsNotDemoLosesItsRequest() {
            CommonData data = data();
            Long request = request(data.o1().admin(), null);
            jdbc.update("UPDATE request SET is_demo = true WHERE id = ?", request);
            assertEquals(200, approve(request, api.login(data.o1().admin())).status());
            Long workOrder = workOrderOf(request);

            Response response = purge(data);

            assertAll(
                    () -> assertEquals(200, response.status(), response.toString()),
                    () -> assertEquals(0, requests(request)),
                    () -> assertEquals(1, count("SELECT count(*) FROM work_order WHERE id = ? AND is_demo = false "
                            + "AND parent_request_id IS NULL", workOrder), "the work order stays, without request"));
        }
    }

    @Nested
    class Schema {

        @Test
        void foreignKeysToRequestAndRequestPortal() {
            // child table.column, constraint, ON DELETE (a NO ACTION, c CASCADE, n SET NULL), indexed
            List<String> keys = jdbc.queryForList("""
                    SELECT c.conrelid::regclass::text || '.' || a.attname || ' ' || c.conname || ' '
                           || c.confdeltype::text || ' ' || (EXISTS (SELECT 1 FROM pg_index i WHERE i.indrelid = c.conrelid
                               AND i.indkey[0] = c.conkey[1] AND i.indisvalid AND i.indpred IS NULL))::text
                    FROM pg_constraint c
                    JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = c.conkey[1]
                    WHERE c.contype = 'f' AND c.confrelid IN ('request'::regclass, 'request_portal'::regclass)
                    ORDER BY 1""", String.class);

            assertEquals(List.of(
                    "custom_field_value.request_id fk_custom_field_value_request a true",
                    "request.request_portal_id fk_request_request_portal n false",
                    "request_assigned_to.request_id fkeg8mmectqp7vcxgxw7k64wt1h c false",
                    "request_customers.request_id fklbeh6vh70b2pqoj6lri2ua4fk c false",
                    "request_files.request_id fkd3vswlusjseoo7lpwb0697w2o c false",
                    "request_portal_field.request_portal_id fk_request_portal_field_request_portal a false",
                    "t_request_file_associations.id_request fki27xearjt3x6ralbb0of1jmmd c true",
                    "work_order.parent_request_id fko87wf45c2qbyn4jhgjv8s4s5d n false",
                    "work_order_aud.parent_request_id FK_work_order_AUD_parentRequest_id n false"), keys);
        }

        @Test
        void foreignKeysOfRequestAndRequestPortalThatDeleteThemInCascade() {
            // the parents whose deletion deletes a request or a request portal
            List<String> keys = jdbc.queryForList("""
                    SELECT c.conrelid::regclass::text || '.' || a.attname || ' -> ' || c.confrelid::regclass::text
                    FROM pg_constraint c
                    JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = c.conkey[1]
                    WHERE c.contype = 'f' AND c.confdeltype = 'c'
                      AND c.conrelid IN ('request'::regclass, 'request_portal'::regclass)
                    ORDER BY 1""", String.class);

            assertEquals(List.of("request.company_id -> company"), keys);
        }
    }
}
