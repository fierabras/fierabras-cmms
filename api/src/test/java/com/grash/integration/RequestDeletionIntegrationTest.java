package com.grash.integration;

import com.fasterxml.jackson.databind.JsonNode;
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
import com.grash.model.Workflow;
import com.grash.model.WorkflowAction;
import com.grash.model.enums.NotificationType;
import com.grash.model.enums.PortalFieldType;
import com.grash.model.enums.webhook.WebhookEvent;
import com.grash.model.enums.workflow.RequestAction;
import com.grash.model.enums.workflow.WFMainCondition;
import com.grash.repository.RequestPortalRepository;
import com.grash.repository.WorkflowRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;

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
    private final RequestPortalRepository requestPortalRepository;
    private final WorkflowRepository workflowRepository;
    private final List<CommonData> created = new ArrayList<>();

    @Autowired
    RequestDeletionIntegrationTest(TestOrganizations organizations, TestRecords records, MockMvc mockMvc,
                                   ObjectMapper objectMapper, JdbcTemplate jdbc,
                                   RequestPortalRepository requestPortalRepository,
                                   WorkflowRepository workflowRepository) {
        this.organizations = organizations;
        this.records = records;
        this.api = new TestApi(mockMvc, objectMapper);
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
        this.requestPortalRepository = requestPortalRepository;
        this.workflowRepository = workflowRepository;
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

    private Long id(Response response) {
        assertTrue(response.is2xx(), response.toString());
        return json(response).get("id").asLong();
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
     * E-6 (D-1, PPB-15, PPB-26): an approved or rejected request is not edited: 409 after the 404 and the 403.
     */
    @Nested
    class Edit {

        private Response editTitle(Long request, String token) {
            return api.call(patch("/requests/" + request).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"title\":\"P-13 edited\"}"), token);
        }

        private String title(Long request) {
            return jdbc.queryForObject("SELECT title FROM request WHERE id = ?", String.class, request);
        }

        @Test
        void rejected_409_unchanged() {
            CommonData data = data();
            Long request = rejected(data, data.o1().admin());
            List<Map<String, Object>> before = row(request);

            Response response = editTitle(request, api.login(data.o1().admin()));

            assertConflict(response, REQUEST_CLOSED);
            assertEquals(before, row(request));
        }

        @Test
        void approved_409_unchanged() {
            CommonData data = data();
            Long request = approved(data, data.o1().admin());
            List<Map<String, Object>> before = row(request);

            Response response = editTitle(request, api.login(data.o1().admin()));

            assertConflict(response, REQUEST_CLOSED);
            assertEquals(before, row(request));
        }

        @Test
        void approvedByATechnicianWhoIsNotItsCreator_403_beforeThe409() {
            CommonData data = data();
            Long request = approved(data, data.o1().admin());

            Response response = editTitle(request, api.login(data.o1().technician()));

            assertAll(
                    () -> assertEquals(403, response.status(), response.toString()),
                    () -> assertFalse(response.body().contains(REQUEST_CLOSED), response.toString()));
        }

        @Test
        void pending_200_edited() {
            CommonData data = data();
            Long request = request(data, data.o1().admin());

            Response response = editTitle(request, api.login(data.o1().admin()));

            assertAll(
                    () -> assertEquals(200, response.status(), response.toString()),
                    () -> assertEquals("P-13 edited", title(request)));
        }

        @Test
        void unknownRequest_404() {
            CommonData data = data();

            Response response = editTitle(Long.MAX_VALUE, api.login(data.o1().admin()));

            assertEquals(404, response.status(), response.toString());
        }
    }

    /**
     * E-7 (D-2, PPB-08): a disabled user is not assigned when a request is created, edited or approved. C-1: the
     * rejected approval reverts everything, the changes of the automation included, and has no external effect.
     */
    @Nested
    class DisabledUser {

        private static final String USER_RETIRED = "USER_RETIRED";

        private void disable(User user) {
            jdbc.update("UPDATE own_user SET enabled = false WHERE id = ?", user.getId());
        }

        private Response create(CommonData data, String users) {
            return api.call(post("/requests").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"title\":\"" + TestRecords.unique("P-13 request") + "\"," + users + "}"),
                    api.login(data.o1().admin()));
        }

        private Response edit(Long request, String users, CommonData data) {
            return api.call(patch("/requests/" + request).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"title\":\"P-13 edited\"," + users + "}"), api.login(data.o1().admin()));
        }

        private Response approve(Long request, CommonData data) {
            return api.call(patch("/requests/" + request + "/approve").contentType(MediaType.APPLICATION_JSON)
                    .content("{}"), api.login(data.o1().admin()));
        }

        private long requestsOf(CommonData data) {
            return count("SELECT count(*) FROM request WHERE company_id = ?", data.o1().company().getId());
        }

        private long workOrdersOf(CommonData data) {
            return count("SELECT count(*) FROM work_order WHERE company_id = ?", data.o1().company().getId());
        }

        /**
         * The notifications of an approval (type WORK_ORDER); those of the creation of a request (type REQUEST) are
         * written asynchronously and are not counted.
         */
        private long notificationsOf(CommonData data) {
            return count("SELECT count(*) FROM notification n JOIN own_user u ON u.id = n.user_id "
                    + "WHERE u.company_id = ? AND n.notification_type = ?", data.o1().company().getId(),
                    NotificationType.WORK_ORDER.ordinal());
        }

        private void assignUserWhenApproved(CommonData data, User user) {
            WorkflowAction action = new WorkflowAction();
            action.setCompany(data.o1().company());
            action.setRequestAction(RequestAction.ASSIGN_USER);
            action.setUser(user);
            Workflow workflow = new Workflow();
            workflow.setCompany(data.o1().company());
            workflow.setTitle(TestRecords.unique("P-13 workflow"));
            workflow.setMainCondition(WFMainCondition.REQUEST_APPROVED);
            workflow.setAction(action);
            workflowRepository.saveAndFlush(workflow);
        }

        /**
         * C-1: the 409 of the approval leaves the request as it was and creates nothing, with no mail, webhook nor
         * notification.
         */
        private void assertApprovalReverted(CommonData data, Long request, List<Map<String, Object>> before,
                                            long workOrders, long notifications, Response response) {
            assertConflict(response, USER_RETIRED);
            assertAll(
                    () -> assertEquals(before, row(request), "the request row is identical"),
                    () -> assertNull(workOrderOf(request), "the request stays pending"),
                    () -> assertEquals(workOrders, workOrdersOf(data), "no work order"),
                    () -> assertEquals(notifications, notificationsOf(data), "no notification"),
                    () -> assertNoSideEffects());
        }

        @Test
        void createdWithADisabledPrimaryUser_409_notCreated() {
            CommonData data = data();
            User technician = data.o1().technician();
            disable(technician);
            long requests = requestsOf(data);

            Response response = create(data, "\"primaryUser\":{\"id\":" + technician.getId() + "}");

            assertConflict(response, USER_RETIRED);
            assertEquals(requests, requestsOf(data));
        }

        @Test
        void createdWithADisabledAssignedUser_409_notCreated() {
            CommonData data = data();
            User technician = data.o1().technician();
            disable(technician);
            long requests = requestsOf(data);

            Response response = create(data, "\"assignedTo\":[{\"id\":" + technician.getId() + "}]");

            assertConflict(response, USER_RETIRED);
            assertEquals(requests, requestsOf(data));
        }

        @Test
        void editedToADisabledPrimaryUser_409_unchanged() {
            CommonData data = data();
            User technician = data.o1().technician();
            Long request = request(data, data.o1().admin());
            disable(technician);
            List<Map<String, Object>> before = row(request);

            Response response = edit(request, "\"primaryUser\":{\"id\":" + technician.getId() + "}", data);

            assertConflict(response, USER_RETIRED);
            assertEquals(before, row(request));
        }

        @Test
        void editedKeepingAUserDisabledAfterItsAssignment_200() {
            CommonData data = data();
            User technician = data.o1().technician();
            Long request = id(create(data, "\"primaryUser\":{\"id\":" + technician.getId() + "}"));
            disable(technician);

            Response response = edit(request, "\"primaryUser\":{\"id\":" + technician.getId() + "}", data);

            assertAll(
                    () -> assertEquals(200, response.status(), response.toString()),
                    () -> assertEquals("P-13 edited", jdbc.queryForObject("SELECT title FROM request WHERE id = ?",
                            String.class, request)));
        }

        @Test
        void approvedWithAnActiveUser_200_effectsSeen() {
            // control of C-1: the doubles and the notifications see the effects of an approval
            CommonData data = data();
            records.endpoint(data.o1().company(), WebhookEvent.WORK_REQUEST_STATUS_CHANGE);
            Long request = id(create(data, "\"primaryUser\":{\"id\":" + data.o1().technician().getId() + "}"));
            long notifications = notificationsOf(data);
            mailDouble.reset();
            webhookDouble.reset();

            Response response = approve(request, data);

            assertEquals(200, response.status(), response.toString());
            webhookDouble.awaitCalls(WebhookEvent.WORK_REQUEST_STATUS_CHANGE.name(), 1, Duration.ofSeconds(10));
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (notificationsOf(data) == notifications && System.nanoTime() < deadline)
                LockSupport.parkNanos(Duration.ofMillis(50).toNanos());
            assertAll(
                    () -> assertNotNull(workOrderOf(request)),
                    () -> assertFalse(mailDouble.calls().isEmpty(), "the approval mail"),
                    () -> assertTrue(notificationsOf(data) > notifications, "the approval notifications"));
        }

        @Test
        void c1_approvedWithADisabledUserOfTheRequest_409_everythingReverted() {
            CommonData data = data();
            User technician = data.o1().technician();
            records.endpoint(data.o1().company(), WebhookEvent.WORK_REQUEST_STATUS_CHANGE);
            Long request = id(create(data, "\"primaryUser\":{\"id\":" + technician.getId() + "}"));
            disable(technician);
            List<Map<String, Object>> before = row(request);
            long workOrders = workOrdersOf(data);
            long notifications = notificationsOf(data);
            mailDouble.reset();
            webhookDouble.reset();

            Response response = approve(request, data);

            assertApprovalReverted(data, request, before, workOrders, notifications, response);
        }

        @Test
        void c1_approvedWithADisabledUserAssignedByTheAutomation_409_everythingReverted() {
            CommonData data = data();
            User technician = data.o1().technician();
            records.endpoint(data.o1().company(), WebhookEvent.WORK_REQUEST_STATUS_CHANGE);
            assignUserWhenApproved(data, technician);
            Long request = request(data, data.o1().admin());
            disable(technician);
            List<Map<String, Object>> before = row(request);
            long workOrders = workOrdersOf(data);
            long notifications = notificationsOf(data);
            mailDouble.reset();
            webhookDouble.reset();

            Response response = approve(request, data);

            assertApprovalReverted(data, request, before, workOrders, notifications, response);
            assertNull(jdbc.queryForObject("SELECT primary_user_id FROM request WHERE id = ?", Long.class, request),
                    "the user that the automation set is reverted");
        }
    }

    /**
     * E-3 and E-4: a portal that received requests is not deleted (PPB-02); without them, it goes with its fields
     * (PPB-14) and is recorded (PPB-23).
     */
    @Nested
    class Portal {

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

        private long portals(Long portal) {
            return count("SELECT count(*) FROM request_portal WHERE id = ?", portal);
        }

        private long fields(Long portal) {
            return count("SELECT count(*) FROM request_portal_field WHERE request_portal_id = ?", portal);
        }

        private Response deletePortal(Long portal, String token) {
            return api.call(delete("/request-portals/" + portal), token);
        }

        @Test
        void ca113_withARequest_409_requestKeepsItsPortal() {
            CommonData data = data();
            RequestPortal portal = portal(data.o1().company(), 1);
            Long request = request(data, data.o1().admin());
            jdbc.update("UPDATE request SET request_portal_id = ? WHERE id = ?", portal.getId(), request);
            mailDouble.reset();
            webhookDouble.reset();

            Response response = deletePortal(portal.getId(), api.login(data.o1().admin()));

            assertEquals(409, response.status(), response.toString());
            JsonNode body = json(response);
            assertAll(
                    () -> assertFalse(body.get("success").asBoolean()),
                    () -> assertEquals("REQUEST_PORTAL_IN_USE", body.get("code").asText()),
                    () -> assertFalse(body.get("message").asText().isBlank()),
                    () -> assertEquals("[\"REQUEST\"]", body.get("blockingTypes").toString()),
                    () -> assertFalse(response.body().toLowerCase().contains("constraint"), response.toString()),
                    () -> assertEquals(portal.getId(), jdbc.queryForObject("SELECT request_portal_id FROM request "
                            + "WHERE id = ?", Long.class, request), "the request keeps its portal"),
                    () -> assertEquals(1, portals(portal.getId())),
                    () -> assertEquals(1, fields(portal.getId())),
                    () -> assertEquals(0, auditRecords(data)),
                    () -> assertNoSideEffects());
        }

        @Test
        void ca114_withoutRequests_200_portalAndItsThreeFieldsDeleted_recorded() {
            CommonData data = data();
            RequestPortal portal = portal(data.o1().company(), 3);

            Response response = deletePortal(portal.getId(), api.login(data.o1().admin()));

            assertAll(
                    () -> assertEquals(200, response.status(), response.toString()),
                    () -> assertEquals(DELETED_BODY, response.body()),
                    () -> assertEquals(0, portals(portal.getId())),
                    () -> assertEquals(0, fields(portal.getId())),
                    () -> assertEquals(List.of("DELETE REQUEST_PORTAL " + portal.getId() + " " + portal.getTitle()
                                    + " API " + data.o1().admin().getId() + " {\"request_portal_field\": 3} 0"),
                            jdbc.queryForList("SELECT action || ' ' || entity_type || ' ' || entity_id || ' ' "
                                    + "|| coalesce(entity_folio, '') || entity_name || ' ' || source || ' ' "
                                    + "|| actor_id || ' ' || cascade_rows::text || ' ' || storage_objects "
                                    + "FROM audit_log WHERE company_id = ?", String.class,
                                    data.o1().company().getId())));
        }

        @Test
        void withARequest_technicianWithoutSettings_403_beforeThe409() {
            CommonData data = data();
            RequestPortal portal = portal(data.o1().company(), 1);
            Long request = request(data, data.o1().admin());
            jdbc.update("UPDATE request SET request_portal_id = ? WHERE id = ?", portal.getId(), request);

            Response response = deletePortal(portal.getId(), api.login(data.o1().technician()));

            assertAll(
                    () -> assertEquals(403, response.status(), response.toString()),
                    () -> assertFalse(response.body().contains("REQUEST_PORTAL_IN_USE"), response.toString()),
                    () -> assertEquals(1, portals(portal.getId())));
        }

        @Test
        void adminOfAnotherOrganization_403_kept() {
            CommonData data = data();
            RequestPortal portal = portal(data.o1().company(), 1);

            Response response = deletePortal(portal.getId(), api.login(data.o2().admin()));

            assertAll(
                    () -> assertEquals(403, response.status(), response.toString()),
                    () -> assertEquals(1, portals(portal.getId())));
        }

        @Test
        void unknownPortal_404() {
            CommonData data = data();

            Response response = deletePortal(Long.MAX_VALUE, api.login(data.o1().admin()));

            assertEquals(404, response.status(), response.toString());
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
