package com.grash.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grash.integration.support.FaultInjector;
import com.grash.integration.support.FaultInjector.Fault;
import com.grash.integration.support.FaultInjector.Operation;
import com.grash.integration.support.TestApi;
import com.grash.integration.support.TestApi.Response;
import com.grash.integration.support.TestAssetRecords;
import com.grash.integration.support.TestOrganizations;
import com.grash.integration.support.TestOrganizations.CommonData;
import com.grash.integration.support.TestOrganizations.O1;
import com.grash.integration.support.TestRecords;
import com.grash.model.Asset;
import com.grash.model.Location;
import com.grash.model.Role;
import com.grash.model.WorkOrder;
import com.grash.model.enums.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;

/**
 * P-10 (PPB-26): the common 409 answer, the safety net for foreign key violations on deletion, the role migrated
 * to the common mechanism, and the first real guard: an asset cited by a work order is not deleted.
 */
@AutoConfigureMockMvc
class DeletionConflictIntegrationTest extends AbstractIntegrationTest {

    private static final String DELETED_BODY = "{\"success\":true,\"message\":\"Deleted successfully\"}";
    private static final String ROLE_IN_USE_MESSAGE = "This role is assigned to at least one user. Assign another "
            + "role to those users before deleting it";
    private static final String RECORD_IN_USE_MESSAGE = "This record is used by other records and cannot be deleted";
    /**
     * Fragments that reveal SQL, constraints or stack traces; none may appear in a rejection.
     */
    private static final List<String> INTERNALS = List.of("sql", "constraint", "fk", "violates", "error:",
            "exception", "at com.", "select ", "delete from");
    /**
     * Table and column names (case-sensitive: the blocking types are upper case).
     */
    private static final List<String> TABLES = List.of("work_order", "own_user", "request_portal", "asset_id",
            "location_id", "role_id");

    private final TestOrganizations organizations;
    private final TestRecords records;
    private final TestAssetRecords assetRecords;
    private final TestApi api;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final FaultInjector faults;
    private final List<CommonData> created = new ArrayList<>();

    @Autowired
    DeletionConflictIntegrationTest(TestOrganizations organizations, TestRecords records, MockMvc mockMvc,
                                    ObjectMapper objectMapper, JdbcTemplate jdbcTemplate,
                                    TestAssetRecords assetRecords) {
        this.organizations = organizations;
        this.records = records;
        this.assetRecords = assetRecords;
        this.api = new TestApi(mockMvc, objectMapper);
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.faults = new FaultInjector(jdbcTemplate);
    }

    @AfterEach
    void cleanUp() {
        faults.removeAll();
        created.forEach(records::deleteRequestPortals);
        created.forEach(organizations::delete);
        created.clear();
    }

    private O1 newO1() {
        CommonData data = organizations.create();
        created.add(data);
        return data.o1();
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

    private Long assetOf(WorkOrder workOrder) {
        return jdbcTemplate.queryForObject("SELECT asset_id FROM work_order WHERE id = ?", Long.class,
                workOrder.getId());
    }

    /**
     * "409 X" of the acceptance criteria: 409, success = false, code X, a message, no SQL, constraint or trace.
     */
    private JsonNode assertConflict(Response response, String code) {
        JsonNode body = json(response);
        String lowerBody = response.body().toLowerCase(Locale.ROOT);
        assertAll(
                () -> assertEquals(409, response.status(), response.toString()),
                () -> assertFalse(body.get("success").asBoolean(), response.toString()),
                () -> assertEquals(code, body.path("code").asText(null), response.toString()),
                () -> assertFalse(body.path("message").asText("").isBlank(), "message: " + response),
                () -> INTERNALS.forEach(fragment -> assertFalse(lowerBody.contains(fragment),
                        "the body reveals '" + fragment + "': " + response)),
                () -> TABLES.forEach(table -> assertFalse(response.body().contains(table),
                        "the body reveals '" + table + "': " + response)));
        return body;
    }

    private static List<String> blockingTypes(JsonNode body) {
        List<String> types = new ArrayList<>();
        body.get("blockingTypes").forEach(type -> types.add(type.asText()));
        return types;
    }

    // =====================================================================================================
    // E-3b: asset cited by a work order (CA-PPB-004, 071, 072, 073, 136)
    // =====================================================================================================

    @Nested
    class AssetCitedByWorkOrder {

        @Test
        void ca004_ca071_assetCitedByClosedWorkOrder_409AssetInUse_workOrderType_noChanges() {
            O1 o1 = newO1();
            Asset x2 = records.asset(o1.company());
            WorkOrder closed = records.workOrder(o1.company(), x2, Status.COMPLETE, false);
            long assets = count("SELECT count(*) FROM asset WHERE company_id = ?", o1.company().getId());
            long workOrders = count("SELECT count(*) FROM work_order WHERE company_id = ?", o1.company().getId());

            Response response = api.call(delete("/assets/" + x2.getId()), api.login(o1.admin()));

            JsonNode body = assertConflict(response, "ASSET_IN_USE");
            assertAll(
                    () -> assertEquals(List.of("WORK_ORDER"), blockingTypes(body), "only types, no counts or ids"),
                    () -> assertTrue(body.get("blockingTypes").get(0).isTextual()),
                    // P-12c (H1-09): the message is the English one of the service
                    () -> assertEquals("This asset is used by other records. Archive it instead of deleting it",
                            body.get("message").asText(), "message in English"),
                    () -> assertFalse(response.body().contains(String.valueOf(closed.getId())), "no identifiers"),
                    () -> assertEquals(assets, count("SELECT count(*) FROM asset WHERE company_id = ?",
                            o1.company().getId()), "assets unchanged"),
                    () -> assertEquals(workOrders, count("SELECT count(*) FROM work_order WHERE company_id = ?",
                            o1.company().getId()), "work orders unchanged"),
                    () -> assertEquals(x2.getId(), assetOf(closed), "the work order keeps its asset"),
                    () -> assertTrue(mailDouble.calls().isEmpty(), "no mail"),
                    () -> assertTrue(webhookDouble.calls().isEmpty(), "no webhook"),
                    () -> assertTrue(storageDouble.calls().isEmpty(), "no storage call"));
        }

        @Test
        void ca004_assetCitedByOpenWorkOrder_409AssetInUse() {
            O1 o1 = newO1();
            Asset asset = records.asset(o1.company());
            WorkOrder open = records.workOrder(o1.company(), asset, Status.OPEN, false);

            Response response = api.call(delete("/assets/" + asset.getId()), api.login(o1.admin()));

            assertEquals(List.of("WORK_ORDER"), blockingTypes(assertConflict(response, "ASSET_IN_USE")));
            assertEquals(asset.getId(), assetOf(open));
        }

        @Test
        void ca072_workOrderInsertedBetweenCheckAndDelete_409AssetInUse_never500() {
            O1 o1 = newO1();
            Asset x11 = records.asset(o1.company());
            WorkOrder template = records.workOrder(o1.company(), null, Status.OPEN, false);
            String citingWorkOrder = "INSERT INTO work_order SELECT * FROM jsonb_populate_record(NULL::work_order, "
                    + "(SELECT to_jsonb(w) || jsonb_build_object('id', nextval('work_order_seq'), 'asset_id', "
                    + x11.getId() + ", 'custom_id', 'P10-RACE') FROM work_order w WHERE w.id = " + template.getId()
                    + "))";

            Response response;
            try (Fault fault = faults.runBefore("asset", Operation.DELETE, citingWorkOrder)) {
                response = api.call(delete("/assets/" + x11.getId()), api.login(o1.admin()));
                assertEquals(1, fault.hits(), "the work order was inserted between the check and the deletion");
            }

            assertEquals(List.of("WORK_ORDER"), blockingTypes(assertConflict(response, "ASSET_IN_USE")));
            assertEquals(1, count("SELECT count(*) FROM asset WHERE id = ?", x11.getId()), "X11 still exists");
        }

        @Test
        void ca073_technicianWithoutPermission_403_notConflict_noDependentsMentioned() {
            O1 o1 = newO1();
            Asset x2 = records.asset(o1.company());
            records.workOrder(o1.company(), x2, Status.COMPLETE, false);

            Response response = api.call(delete("/assets/" + x2.getId()), api.login(o1.technician()));

            JsonNode body = json(response);
            assertAll(
                    () -> assertEquals(403, response.status(), response.toString()),
                    () -> assertNull(body.get("blockingTypes"), "no dependents in a 403"),
                    () -> assertFalse(response.body().contains("IN_USE"), response.toString()),
                    () -> assertFalse(response.body().contains("WORK_ORDER"), response.toString()),
                    () -> assertEquals(1, count("SELECT count(*) FROM asset WHERE id = ?", x2.getId())));
        }

        @Test
        void ca136_assetWithoutDependents_200_successWithoutBlockingTypes() {
            O1 o1 = newO1();
            Asset x17 = records.asset(o1.company());

            Response response = api.call(delete("/assets/" + x17.getId()), api.login(o1.admin()));

            assertEquals(200, response.status(), response.toString());
            assertEquals(DELETED_BODY, response.body(), "success = true and no list of types");
            assertEquals(0, count("SELECT count(*) FROM asset WHERE id = ?", x17.getId()));
        }

        @Test
        void unknownReferencingTable_409AssetInUse_withoutBlockingTypes() {
            // P-14 (C-2): every table of the schema that cites an asset is now a dependent of its rule, so the test
            // owns a table that the rule does not know
            O1 o1 = newO1();
            Asset asset = records.asset(o1.company());
            jdbcTemplate.execute("CREATE TABLE p14_unknown_reference (asset_id BIGINT NOT NULL REFERENCES asset (id))");
            try {
                jdbcTemplate.update("INSERT INTO p14_unknown_reference (asset_id) VALUES (?)", asset.getId());

                Response response = api.call(delete("/assets/" + asset.getId()), api.login(o1.admin()));

                JsonNode body = assertConflict(response, "ASSET_IN_USE");
                assertNull(body.get("blockingTypes"), "the referencing table is not a known dependent: " + response);
                assertEquals(1, count("SELECT count(*) FROM asset WHERE id = ?", asset.getId()));
            } finally {
                jdbcTemplate.execute("DROP TABLE p14_unknown_reference");
            }
        }
    }

    // =====================================================================================================
    // E-3b: schema (PPB-21, PPB-22)
    // =====================================================================================================

    @Nested
    class Schema {

        @Test
        void workOrderAssetId_isNoAction_andFirstColumnOfAValidIndex() {
            String rule = jdbcTemplate.queryForObject("SELECT c.confdeltype::text FROM pg_constraint c "
                    + "JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = c.conkey[1] "
                    + "WHERE c.contype = 'f' AND c.conrelid = 'work_order'::regclass "
                    + "AND c.confrelid = 'asset'::regclass AND a.attname = 'asset_id'", String.class);
            long indexes = count("SELECT count(*) FROM pg_index i JOIN pg_attribute a ON a.attrelid = i.indrelid "
                    + "AND a.attnum = i.indkey[0] WHERE i.indrelid = 'work_order'::regclass "
                    + "AND a.attname = 'asset_id' AND i.indisvalid AND i.indpred IS NULL");

            assertEquals("a", rule, "ON DELETE NO ACTION");
            assertTrue(indexes >= 1, "work_order.asset_id leads a valid, non-partial index");
        }
    }

    // =====================================================================================================
    // E-2: safety net for deletions without a rule
    // =====================================================================================================

    @Nested
    class SafetyNet {

        @Test
        void deletionWithoutRule_foreignKeyViolation_409RecordInUse_noInternals() {
            // P-14 (C-2): the location now has a rule, so the test deletes a floor plan (a deletion without a rule)
            // cited by a table that the test owns
            O1 o1 = newO1();
            Location location = records.location(o1.company());
            Long floorPlan = assetRecords.floorPlan(location).getId();
            jdbcTemplate.execute("CREATE TABLE p14_unknown_reference (floor_plan_id BIGINT NOT NULL "
                    + "REFERENCES floor_plan (id))");
            try {
                jdbcTemplate.update("INSERT INTO p14_unknown_reference (floor_plan_id) VALUES (?)", floorPlan);

                Response response = api.call(delete("/floor-plans/" + floorPlan), api.login(o1.admin()));

                JsonNode body = assertConflict(response, "RECORD_IN_USE");
                assertAll(
                        () -> assertEquals(RECORD_IN_USE_MESSAGE, body.get("message").asText()),
                        () -> assertNull(body.get("blockingTypes")),
                        () -> assertEquals(1, count("SELECT count(*) FROM floor_plan WHERE id = ?", floorPlan)));
            } finally {
                jdbcTemplate.execute("DROP TABLE p14_unknown_reference");
            }
        }
    }

    // =====================================================================================================
    // E-3: role migrated to the common mechanism
    // =====================================================================================================

    @Nested
    class RoleInUse {

        @Test
        void roleWithUser_409_sameCodeAndMessage_plusUserType() {
            O1 o1 = newO1();
            Role role = records.role(o1.company());
            jdbcTemplate.update("UPDATE own_user SET role_id = ? WHERE id = ?", role.getId(),
                    o1.technician().getId());

            Response response = api.call(delete("/roles/" + role.getId()), api.login(o1.admin()));

            JsonNode body = assertConflict(response, "ROLE_IN_USE");
            assertEquals(ROLE_IN_USE_MESSAGE, body.get("message").asText());
            assertEquals(List.of("USER"), blockingTypes(body));
        }

        @Test
        void userAssignedBetweenCheckAndDelete_409RoleInUse_never500() {
            O1 o1 = newO1();
            Role role = records.role(o1.company());
            String assign = "UPDATE own_user SET role_id = " + role.getId() + " WHERE id = "
                    + o1.technician().getId();

            Response response;
            try (Fault fault = faults.runBefore("role", Operation.DELETE, assign)) {
                response = api.call(delete("/roles/" + role.getId()), api.login(o1.admin()));
                assertEquals(1, fault.hits());
            }

            JsonNode body = assertConflict(response, "ROLE_IN_USE");
            assertEquals(ROLE_IN_USE_MESSAGE, body.get("message").asText());
            assertEquals(List.of("USER"), blockingTypes(body));
            assertEquals(1, count("SELECT count(*) FROM role WHERE id = ?", role.getId()));
        }
    }

    // =====================================================================================================
    // D-P10-4: demo purge with a non-demo work order citing a demo asset
    // =====================================================================================================

    @Nested
    class DemoPurge {

        @Test
        void nonDemoWorkOrderCitesDemoAsset_409RecordInUse_nothingDeleted() {
            O1 o1 = newO1();
            Asset demoAsset = records.asset(o1.company(), null, true);
            WorkOrder demoWorkOrder = records.workOrder(o1.company(), null, Status.OPEN, true);
            WorkOrder realWorkOrder = records.workOrder(o1.company(), demoAsset, Status.OPEN, false);

            Response response = api.call(delete("/demo/demo-data"), api.login(o1.admin()));

            assertConflict(response, "RECORD_IN_USE");
            assertAll(
                    () -> assertEquals(1, count("SELECT count(*) FROM asset WHERE id = ?", demoAsset.getId()),
                            "the demo asset still exists"),
                    () -> assertEquals(1, count("SELECT count(*) FROM work_order WHERE id = ?",
                            demoWorkOrder.getId()), "the purge is rejected whole"),
                    () -> assertEquals(demoAsset.getId(), assetOf(realWorkOrder),
                            "the real work order keeps its asset"));
        }
    }
}
