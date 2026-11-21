package com.grash.integration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grash.integration.support.FaultInjector;
import com.grash.integration.support.FaultInjector.Fault;
import com.grash.integration.support.FaultInjector.Operation;
import com.grash.integration.support.TestApi;
import com.grash.integration.support.TestApi.Response;
import com.grash.integration.support.TestOrganizations;
import com.grash.integration.support.TestOrganizations.CommonData;
import com.grash.integration.support.TestOrganizations.O1;
import com.grash.integration.support.TestRecords;
import com.grash.model.ApiKey;
import com.grash.model.Asset;
import com.grash.model.AssetDowntime;
import com.grash.model.Part;
import com.grash.model.Role;
import com.grash.model.User;
import com.grash.model.WebhookEndpoint;
import com.grash.model.WorkOrderCategory;
import com.grash.model.enums.Status;
import com.grash.model.enums.webhook.WebhookEvent;
import com.grash.repository.WorkOrderCategoryRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * P-10 (PPB-23, 24, 25, 36): the audit log records each confirmed deletion with the operation, and only then; it is
 * read-only, survives what it records and who acted, and is queried with a preloaded, editable filter.
 */
@AutoConfigureMockMvc
class AuditLogIntegrationTest extends AbstractIntegrationTest {

    private static final String RECORD_COLUMNS = "id, occurred_at, pg_typeof(occurred_at)::text AS occurred_at_type, "
            + "company_id, actor_id, actor_name, actor_email, action, entity_type, entity_id, entity_folio, "
            + "entity_name, source, cascade_rows::text AS cascade_rows, storage_objects";

    private final TestOrganizations organizations;
    private final TestRecords records;
    private final TestApi api;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final WorkOrderCategoryRepository workOrderCategoryRepository;
    private final FaultInjector faults;
    private final List<CommonData> created = new ArrayList<>();
    private final List<Long> globalRecords = new ArrayList<>();

    @Autowired
    AuditLogIntegrationTest(TestOrganizations organizations, TestRecords records, MockMvc mockMvc,
                            ObjectMapper objectMapper, JdbcTemplate jdbcTemplate,
                            WorkOrderCategoryRepository workOrderCategoryRepository) {
        this.organizations = organizations;
        this.records = records;
        this.api = new TestApi(mockMvc, objectMapper);
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.workOrderCategoryRepository = workOrderCategoryRepository;
        this.faults = new FaultInjector(jdbcTemplate);
    }

    @AfterEach
    void cleanUp() {
        faults.removeAll();
        globalRecords.forEach(id -> jdbcTemplate.update("DELETE FROM audit_log WHERE id = ?", id));
        globalRecords.clear();
        created.forEach(records::deleteRequestPortals);
        created.forEach(organizations::delete);
        created.clear();
    }

    private CommonData newData() {
        CommonData data = organizations.create();
        created.add(data);
        return data;
    }

    private long count(String sql, Object... arguments) {
        Long result = jdbcTemplate.queryForObject(sql, Long.class, arguments);
        return result == null ? 0 : result;
    }

    private JsonNode json(Response response) {
        try {
            return objectMapper.readTree(response.body());
        } catch (Exception e) {
            throw new AssertionError("not JSON: " + response, e);
        }
    }

    private List<Map<String, Object>> recordsOf(Long companyId) {
        return jdbcTemplate.queryForList("SELECT " + RECORD_COLUMNS + " FROM audit_log WHERE company_id = ? "
                + "ORDER BY id", companyId);
    }

    private Map<String, Object> onlyRecordOf(Long companyId) {
        List<Map<String, Object>> rows = recordsOf(companyId);
        assertEquals(1, rows.size(), "exactly one record: " + rows);
        return rows.get(0);
    }

    private Map<String, Integer> cascadeRows(Map<String, Object> record) {
        try {
            return objectMapper.readValue((String) record.get("cascade_rows"), new TypeReference<>() {
            });
        } catch (Exception e) {
            throw new AssertionError("cascade_rows is not a JSON object: " + record, e);
        }
    }

    private String rowText(Object id) {
        return jdbcTemplate.queryForObject("SELECT row_to_json(a)::text FROM audit_log a WHERE id = ?", String.class,
                id);
    }

    private static String fullName(User user) {
        return user.getFirstName() + " " + user.getLastName();
    }

    /**
     * A record written directly, for the query tests.
     */
    private long seed(Long companyId, User actor, String action, Instant occurredAt) {
        return jdbcTemplate.queryForObject("INSERT INTO audit_log (occurred_at, company_id, actor_id, actor_name, "
                        + "actor_email, action, entity_type, entity_id, entity_name, source) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 'PART', 1, 'P-10 seed', 'API') RETURNING id", Long.class,
                Timestamp.from(occurredAt), companyId, actor.getId(), fullName(actor), actor.getEmail(), action);
    }

    // =====================================================================================================
    // Recording (PPB-23, PPB-25)
    // =====================================================================================================

    @Nested
    class Recording {

        @Test
        void ca062_partDeleted_oneRecordWithEveryField() {
            O1 o1 = newData().o1();
            Part p5 = records.part(o1.company(), null);
            Instant before = Instant.now().minusSeconds(1);

            Response response = api.call(delete("/parts/" + p5.getId()), api.login(o1.admin()));

            Instant after = Instant.now().plusSeconds(1);
            assertEquals(200, response.status(), response.toString());
            Map<String, Object> record = onlyRecordOf(o1.company().getId());
            Instant occurredAt = ((Timestamp) record.get("occurred_at")).toInstant();
            assertAll(
                    () -> assertEquals("timestamp with time zone", record.get("occurred_at_type")),
                    () -> assertTrue(!occurredAt.isBefore(before) && !occurredAt.isAfter(after), "server time"),
                    () -> assertEquals(o1.admin().getId(), record.get("actor_id")),
                    () -> assertEquals(fullName(o1.admin()), record.get("actor_name")),
                    () -> assertEquals(o1.admin().getEmail(), record.get("actor_email")),
                    () -> assertEquals("DELETE", record.get("action")),
                    () -> assertEquals("PART", record.get("entity_type")),
                    () -> assertEquals(p5.getId(), record.get("entity_id")),
                    () -> assertNull(record.get("entity_folio"), "a part has no folio (D-P10-1)"),
                    () -> assertEquals(p5.getName(), record.get("entity_name")),
                    () -> assertEquals("API", record.get("source")),
                    () -> assertEquals(Map.of(), cascadeRows(record), "nothing else was deleted"),
                    () -> assertEquals(0, record.get("storage_objects")));
        }

        @Test
        void assetDeleted_folioAndNameRecorded_downtimeInSummary() {
            O1 o1 = newData().o1();
            Asset asset = records.asset(o1.company(), TestRecords.unique("A-P10"), false);
            records.downtime(asset);

            Response response = api.call(delete("/assets/" + asset.getId()), api.login(o1.admin()));

            assertEquals(200, response.status(), response.toString());
            Map<String, Object> record = onlyRecordOf(o1.company().getId());
            assertAll(
                    () -> assertEquals("ASSET", record.get("entity_type")),
                    () -> assertEquals(asset.getId(), record.get("entity_id")),
                    () -> assertEquals(asset.getCustomId(), record.get("entity_folio")),
                    () -> assertEquals(asset.getName(), record.get("entity_name")),
                    () -> assertEquals(Map.of("asset_downtime", 1), cascadeRows(record)));
        }

        @Test
        void ca063_rejectedDeletions_noRecord() {
            O1 o1 = newData().o1();
            Asset x2 = records.asset(o1.company());
            records.workOrder(o1.company(), x2, Status.COMPLETE, false);
            Part part = records.part(o1.company(), null);

            Response conflict = api.call(delete("/assets/" + x2.getId()), api.login(o1.admin()));
            Response forbidden = api.call(delete("/parts/" + part.getId()), api.login(o1.technician()));

            assertEquals(409, conflict.status(), conflict.toString());
            assertEquals(403, forbidden.status(), forbidden.toString());
            assertEquals(0, recordsOf(o1.company().getId()).size(), "a rejection leaves no record");
        }

        @Test
        void ca120_apiKeyDeleted_recordWithLabel_withoutKeyValue() {
            O1 o1 = newData().o1();
            ApiKey k1 = records.apiKey(o1.owner());

            Response response = api.call(delete("/api-keys/" + k1.getId()), api.login(o1.admin()));

            assertEquals(200, response.status(), response.toString());
            Map<String, Object> record = onlyRecordOf(o1.company().getId());
            assertAll(
                    () -> assertEquals("DELETE", record.get("action")),
                    () -> assertEquals("API_KEY", record.get("entity_type")),
                    () -> assertEquals(k1.getLabel(), record.get("entity_name")),
                    () -> assertFalse(rowText(record.get("id")).contains(k1.getCode()), "the key value is not kept"));
        }

        @Test
        void ca121_webhookDeleted_summaryOfCollectionTables_eventAsName_noUrl() {
            O1 o1 = newData().o1();
            WebhookEndpoint wh1 = records.endpoint(o1.company(), WebhookEvent.WORK_ORDER_CHANGE);
            WorkOrderCategory category = new WorkOrderCategory();
            category.setName(TestRecords.unique("P-10 category"));
            category.setCompanySettings(o1.company().getCompanySettings());
            WorkOrderCategory savedCategory = workOrderCategoryRepository.saveAndFlush(category);
            for (Status status : List.of(Status.OPEN, Status.COMPLETE)) {
                jdbcTemplate.update("INSERT INTO webhook_endpoint_work_order_statuses (webhook_endpoint_id, "
                        + "work_order_statuses) VALUES (?, ?)", wh1.getId(), status.name());
            }
            jdbcTemplate.update("INSERT INTO webhook_endpoint_work_order_categories (webhook_endpoint_id, "
                    + "work_order_categories_id) VALUES (?, ?)", wh1.getId(), savedCategory.getId());

            Response response = api.call(delete("/webhook-endpoints/" + wh1.getId()), api.login(o1.admin()));

            assertEquals(200, response.status(), response.toString());
            Map<String, Object> record = onlyRecordOf(o1.company().getId());
            String text = rowText(record.get("id"));
            assertAll(
                    () -> assertEquals("WEBHOOK_ENDPOINT", record.get("entity_type")),
                    () -> assertEquals("WORK_ORDER_CHANGE", record.get("entity_name"), "D-P10-6"),
                    () -> assertEquals(Map.of("webhook_endpoint_work_order_statuses", 2,
                            "webhook_endpoint_work_order_categories", 1), cascadeRows(record)),
                    () -> assertFalse(text.contains("token="), "the URL is not kept"),
                    () -> assertFalse(text.contains("p10-secret"), "the secret is not kept"));
        }

        @Test
        void ca122_roleDeleted_oneRecord_invitationAndPermissionsInSummary() {
            O1 o1 = newData().o1();
            Role r1 = records.role(o1.company());
            records.invitation(r1);

            Response response = api.call(delete("/roles/" + r1.getId()), api.login(o1.admin()));

            assertEquals(200, response.status(), response.toString());
            Map<String, Object> record = onlyRecordOf(o1.company().getId());
            assertAll(
                    () -> assertEquals("ROLE", record.get("entity_type")),
                    () -> assertEquals(r1.getName(), record.get("entity_name")),
                    () -> assertEquals(Map.of("user_invitation", 1, "role_view_permissions", 1,
                            "role_create_permissions", 2), cascadeRows(record)));
        }

        @Test
        void ca067_auditWriteFails_assetKept_noMailWebhookOrStorage() {
            O1 o1 = newData().o1();
            Asset x1 = records.asset(o1.company());
            AssetDowntime downtime = records.downtime(x1);

            Response response;
            try (Fault fault = faults.failOn("audit_log", Operation.INSERT)) {
                response = api.call(delete("/assets/" + x1.getId()), api.login(o1.admin()));
                assertEquals(1, fault.hits(), "the audit write was reached and failed");
            }

            assertAll(
                    () -> assertFalse(response.is2xx(), "error: " + response),
                    () -> assertEquals(1, count("SELECT count(*) FROM asset WHERE id = ?", x1.getId()), "X1 exists"),
                    () -> assertEquals(1, count("SELECT count(*) FROM asset_downtime WHERE id = ?",
                            downtime.getId()), "its downtime exists"),
                    () -> assertTrue(mailDouble.calls().isEmpty(), "no mail"),
                    () -> assertTrue(webhookDouble.calls().isEmpty(), "no webhook"),
                    () -> assertTrue(storageDouble.calls().isEmpty(), "no storage call"),
                    () -> assertEquals(0, recordsOf(o1.company().getId()).size()));
        }

        /**
         * Guard (CA-PPB-129): editing a workflow deletes and recreates it internally; it is not a deletion.
         */
        @Test
        void ca129_workflowEdited_noDeletionRecord() {
            O1 o1 = newData().o1();
            String token = api.login(o1.admin());
            String body = "{\"title\":\"%s\",\"mainCondition\":\"WORK_ORDER_CREATED\",\"secondaryConditions\":[],"
                    + "\"action\":{\"workOrderAction\":\"ASSIGN_PRIORITY\",\"priority\":\"HIGH\"}}";
            Response createdWorkflow = api.call(post("/workflows").contentType(MediaType.APPLICATION_JSON)
                    .content(body.formatted("P-10 WF2")), token);
            assertEquals(200, createdWorkflow.status(), createdWorkflow.toString());
            long id = json(createdWorkflow).get("id").asLong();

            Response edited = api.call(patch("/workflows/" + id).contentType(MediaType.APPLICATION_JSON)
                    .content(body.formatted("P-10 WF2 edited")), token);

            assertEquals(200, edited.status(), edited.toString());
            assertEquals(0, recordsOf(o1.company().getId()).size(), "no DELETE record for an edition");
        }
    }

    // =====================================================================================================
    // Read-only and survival (PPB-24)
    // =====================================================================================================

    @Nested
    class Survival {

        /**
         * Guard (CA-PPB-064): there is no endpoint that modifies or deletes a record, and B1 does not change.
         */
        @Test
        void ca064_noEndpointModifiesOrDeletesARecord_b1Unchanged() {
            O1 o1 = newData().o1();
            long b1 = seed(o1.company().getId(), o1.admin(), "DELETE", Instant.now().minus(1, ChronoUnit.DAYS));
            String before = rowText(b1);
            String token = api.login(o1.admin());

            JsonNode paths = json(api.call(get("/v3/api-docs"), null)).get("paths");
            Set<String> writeMethods = Set.of("post", "put", "patch", "delete");
            for (Iterator<Map.Entry<String, JsonNode>> it = paths.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> path = it.next();
                if (!path.getKey().toLowerCase().contains("audit")) continue;
                path.getValue().fieldNames().forEachRemaining(method -> assertFalse(writeMethods.contains(method),
                        method.toUpperCase() + " " + path.getKey() + " must not exist"));
            }
            List<MockHttpServletRequestBuilder> attempts = List.of(
                    put("/audit-logs/" + b1).contentType(MediaType.APPLICATION_JSON).content("{\"entityName\":\"x\"}"),
                    patch("/audit-logs/" + b1).contentType(MediaType.APPLICATION_JSON).content("{\"entityName\":\"x\"}"),
                    delete("/audit-logs/" + b1),
                    post("/audit-logs").contentType(MediaType.APPLICATION_JSON).content("{}"));
            for (MockHttpServletRequestBuilder attempt : attempts) {
                Response response = api.call(attempt, token);
                assertFalse(response.is2xx(), "no write endpoint: " + response);
            }
            assertEquals(before, rowText(b1), "B1 unchanged");
        }

        @Test
        void ca065_actorDisabledAndRenamed_recordKeepsNameAndEmailAtTheTime() {
            O1 o1 = newData().o1();
            User t = o1.technician();
            Part part = records.part(o1.company(), t);
            Response deleted = api.call(delete("/parts/" + part.getId()), api.login(t));
            assertEquals(200, deleted.status(), deleted.toString());
            jdbcTemplate.update("UPDATE own_user SET enabled = false, first_name = 'Renamed', email = ? WHERE id = ?",
                    "renamed-" + t.getEmail(), t.getId());

            Response response = api.call(get("/audit-logs"), api.login(o1.admin()));

            assertEquals(200, response.status(), response.toString());
            JsonNode content = json(response).get("content");
            assertEquals(1, content.size(), response.toString());
            JsonNode b1 = content.get(0);
            assertAll(
                    () -> assertEquals(part.getId(), b1.get("entityId").asLong()),
                    () -> assertEquals(t.getId(), b1.get("actorId").asLong()),
                    () -> assertEquals(fullName(t), b1.get("actorName").asText()),
                    () -> assertEquals(t.getEmail(), b1.get("actorEmail").asText()));
        }
    }

    // =====================================================================================================
    // Query (PPB-24, PPB-36)
    // =====================================================================================================

    @Nested
    class Query {

        private record Seeded(CommonData data, long r10, long r40, long r50, long r70, long r100, long o2Record) {
        }

        /**
         * O1: 10 days DELETE by T, 40 RETIRE by A, 50 RETIRE by T, 70 DELETE by A, 100 DELETE by T. O2: 5 days.
         */
        private Seeded seeded() {
            CommonData data = newData();
            O1 o1 = data.o1();
            Long company = o1.company().getId();
            Instant now = Instant.now();
            return new Seeded(data,
                    seed(company, o1.technician(), "DELETE", now.minus(10, ChronoUnit.DAYS)),
                    seed(company, o1.admin(), "RETIRE", now.minus(40, ChronoUnit.DAYS)),
                    seed(company, o1.technician(), "RETIRE", now.minus(50, ChronoUnit.DAYS)),
                    seed(company, o1.admin(), "DELETE", now.minus(70, ChronoUnit.DAYS)),
                    seed(company, o1.technician(), "DELETE", now.minus(100, ChronoUnit.DAYS)),
                    seed(data.o2().company().getId(), data.o2().admin(), "DELETE", now.minus(5, ChronoUnit.DAYS)));
        }

        private List<Long> ids(JsonNode body) {
            List<Long> ids = new ArrayList<>();
            body.get("content").forEach(record -> ids.add(record.get("id").asLong()));
            return ids;
        }

        private static String daysAgo(int days) {
            return Instant.now().minus(days, ChronoUnit.DAYS).toString();
        }

        private void assertInvalid(Response response) {
            JsonNode body = json(response);
            assertAll(
                    () -> assertEquals(400, response.status(), response.toString()),
                    () -> assertFalse(body.get("success").asBoolean()),
                    () -> assertEquals("AUDIT_FILTER_INVALID", body.path("code").asText(null), response.toString()),
                    () -> assertNull(body.get("content"), "no results: " + response));
        }

        @Test
        void ca094_noParameters_preloadedFilter_last30Days_onlyThe10DayRecord() {
            Seeded seeded = seeded();
            Instant now = Instant.now();

            Response response = api.call(get("/audit-logs"), api.login(seeded.data().o1().admin()));

            assertEquals(200, response.status(), response.toString());
            JsonNode body = json(response);
            JsonNode filter = body.get("filter");
            Instant from = Instant.parse(filter.get("from").asText());
            Instant to = Instant.parse(filter.get("to").asText());
            assertAll(
                    () -> assertTrue(Duration.between(to, now).abs().toSeconds() < 60, "to = now: " + filter),
                    () -> assertEquals(Duration.ofDays(30), Duration.between(from, to), "last 30 days: " + filter),
                    () -> assertTrue(filter.get("action").isNull(), "all actions: " + filter),
                    () -> assertTrue(filter.get("userId").isNull(), "all users: " + filter),
                    () -> assertEquals(List.of(seeded.r10()), ids(body)),
                    () -> assertEquals(0, body.get("page").asInt()),
                    () -> assertEquals(20, body.get("size").asInt()),
                    () -> assertEquals(1, body.get("totalElements").asLong()),
                    () -> assertEquals(1, body.get("totalPages").asInt()));
        }

        @Test
        void ca095_editedFilter_120Days_deleteAction_userT_onlyMatchingRecords_newestFirst() {
            Seeded seeded = seeded();
            O1 o1 = seeded.data().o1();

            Response response = api.call(get("/audit-logs").param("from", daysAgo(120))
                    .param("to", Instant.now().toString()).param("action", "DELETE")
                    .param("userId", String.valueOf(o1.technician().getId())), api.login(o1.admin()));

            assertEquals(200, response.status(), response.toString());
            JsonNode body = json(response);
            assertAll(
                    () -> assertEquals(List.of(seeded.r10(), seeded.r100()), ids(body)),
                    () -> assertEquals("DELETE", body.get("filter").get("action").asText()),
                    () -> assertEquals(o1.technician().getId(), body.get("filter").get("userId").asLong()));
        }

        @Test
        void pagination_newestFirst_fixedOrder() {
            Seeded seeded = seeded();
            String token = api.login(seeded.data().o1().admin());

            JsonNode first = json(api.call(get("/audit-logs").param("from", daysAgo(120)).param("size", "2")
                    .param("sort", "occurredAt,asc"), token));
            JsonNode last = json(api.call(get("/audit-logs").param("from", daysAgo(120)).param("size", "2")
                    .param("page", "2"), token));

            assertAll(
                    () -> assertEquals(List.of(seeded.r10(), seeded.r40()), ids(first)),
                    () -> assertEquals(5, first.get("totalElements").asLong()),
                    () -> assertEquals(3, first.get("totalPages").asInt()),
                    () -> assertEquals(List.of(seeded.r100()), ids(last)));
        }

        @Test
        void ca096_fromAfterTo_400AuditFilterInvalid_noResults() {
            Seeded seeded = seeded();

            Response response = api.call(get("/audit-logs").param("from", daysAgo(1)).param("to", daysAgo(5)),
                    api.login(seeded.data().o1().admin()));

            assertInvalid(response);
        }

        @Test
        void malformedFilter_400AuditFilterInvalid() {
            O1 o1 = newData().o1();
            String token = api.login(o1.admin());

            assertAll(
                    () -> assertInvalid(api.call(get("/audit-logs").param("from", "yesterday"), token)),
                    () -> assertInvalid(api.call(get("/audit-logs").param("action", "ERASE"), token)),
                    () -> assertInvalid(api.call(get("/audit-logs").param("userId", "abc"), token)),
                    () -> assertInvalid(api.call(get("/audit-logs").param("size", "0"), token)),
                    () -> assertInvalid(api.call(get("/audit-logs").param("size", "101"), token)),
                    () -> assertInvalid(api.call(get("/audit-logs").param("page", "-1"), token)));
        }

        @Test
        void ca066_technicianAndOtherOrganizationAdmin_403_noRouteReturnsO1Records() {
            Seeded seeded = seeded();
            O1 o1 = seeded.data().o1();
            String technician = api.login(o1.technician());
            String o2Admin = api.login(seeded.data().o2().admin());
            Set<Long> o1Records = Set.of(seeded.r10(), seeded.r40(), seeded.r50(), seeded.r70(), seeded.r100());

            Response technicianPlain = api.call(get("/audit-logs"), technician);
            Response technicianFiltered = api.call(get("/audit-logs").param("from", daysAgo(120)), technician);
            Response o2NamesO1 = api.call(get("/audit-logs").param("userId", String.valueOf(o1.admin().getId())),
                    o2Admin);

            assertAll(
                    () -> assertEquals(403, technicianPlain.status(), technicianPlain.toString()),
                    () -> assertEquals(403, technicianFiltered.status(), technicianFiltered.toString()),
                    () -> assertEquals(403, o2NamesO1.status(), o2NamesO1.toString()));
            List<Response> o2Routes = new ArrayList<>(List.of(api.call(get("/audit-logs"), o2Admin),
                    api.call(get("/audit-logs").param("from", daysAgo(120)).param("size", "100"), o2Admin)));
            for (String action : List.of("DELETE", "RETIRE", "RESTORE", "REOPEN", "PURGE")) {
                o2Routes.add(api.call(get("/audit-logs").param("from", daysAgo(120)).param("action", action), o2Admin));
            }
            Set<Long> seen = new HashSet<>();
            for (Response route : o2Routes) {
                assertEquals(200, route.status(), route.toString());
                seen.addAll(ids(json(route)));
            }
            assertTrue(seen.stream().noneMatch(o1Records::contains), "O2 sees no record of O1: " + seen);
            assertTrue(seen.contains(seeded.o2Record()), "O2 sees its own record");
        }

        @Test
        void recordWithoutOrganization_neverReturned() {
            O1 o1 = newData().o1();
            long global = jdbcTemplate.queryForObject("INSERT INTO audit_log (occurred_at, company_id, action, "
                    + "entity_type, entity_id, entity_name, source) VALUES (now(), NULL, 'DELETE', 'CURRENCY', 1, "
                    + "'P-10 global', 'API') RETURNING id", Long.class);
            globalRecords.add(global);

            Response response = api.call(get("/audit-logs"), api.login(o1.admin()));

            assertEquals(200, response.status(), response.toString());
            assertFalse(ids(json(response)).contains(global), "D-14: only the superadministrator would see it");
        }
    }
}
