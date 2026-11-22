package com.grash.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grash.integration.support.FaultInjector;
import com.grash.integration.support.FaultInjector.Operation;
import com.grash.integration.support.TestApi;
import com.grash.integration.support.TestApi.Response;
import com.grash.integration.support.TestAssetRecords;
import com.grash.integration.support.TestOrganizations;
import com.grash.integration.support.TestOrganizations.CommonData;
import com.grash.integration.support.TestRecords;
import com.grash.model.Asset;
import com.grash.model.AssetCategory;
import com.grash.model.AssetDowntime;
import com.grash.model.Company;
import com.grash.model.Location;
import com.grash.model.Meter;
import com.grash.model.MeterCategory;
import com.grash.model.User;
import com.grash.model.WorkOrder;
import com.grash.model.Workflow;
import com.grash.model.enums.Status;
import com.grash.model.enums.WorkOrderMeterTriggerCondition;
import com.grash.repository.AssetCategoryRepository;
import com.grash.repository.MeterCategoryRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * P-14: deletion of assets, locations, meters and their categories is rejected while a dependent exists (PPB-02,
 * PPB-26), readings are immutable (PPB-35), the removal of a child is recorded (PPB-14, PPB-23) and the schema backs
 * the rules (PPB-21, PPB-22).
 */
@AutoConfigureMockMvc
class AssetLocationMeterDeletionIntegrationTest extends AbstractIntegrationTest {

    private final TestOrganizations organizations;
    private final TestRecords records;
    private final TestAssetRecords module;
    private final TestApi api;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;
    private final FaultInjector faults;
    private final AssetCategoryRepository assetCategoryRepository;
    private final MeterCategoryRepository meterCategoryRepository;
    private final List<CommonData> created = new ArrayList<>();

    @Autowired
    AssetLocationMeterDeletionIntegrationTest(TestOrganizations organizations, TestRecords records,
                                              TestAssetRecords module, MockMvc mockMvc, ObjectMapper objectMapper,
                                              JdbcTemplate jdbc, AssetCategoryRepository assetCategoryRepository,
                                              MeterCategoryRepository meterCategoryRepository) {
        this.organizations = organizations;
        this.records = records;
        this.module = module;
        this.api = new TestApi(mockMvc, objectMapper);
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
        this.faults = new FaultInjector(jdbc);
        this.assetCategoryRepository = assetCategoryRepository;
        this.meterCategoryRepository = meterCategoryRepository;
    }

    @AfterEach
    void cleanUp() {
        faults.removeAll();
        created.forEach(records::deleteRequestPortals);
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

    private long rows(String table, Long id) {
        return count("SELECT count(*) FROM " + table + " WHERE id = ?", id);
    }

    private List<Map<String, Object>> row(String table, Long id) {
        return jdbc.queryForList("SELECT * FROM " + table + " WHERE id = ?", id);
    }

    private long auditRecords(CommonData data) {
        return count("SELECT count(*) FROM audit_log WHERE company_id = ?", data.o1().company().getId());
    }

    private List<String> audit(CommonData data) {
        return jdbc.queryForList("SELECT action || ' ' || entity_type || ' ' || coalesce(entity_folio, '-') || ' ' "
                + "|| coalesce(entity_name, '-') || ' ' || cascade_rows::text || ' ' || storage_objects FROM audit_log "
                + "WHERE company_id = ? ORDER BY id", String.class, data.o1().company().getId());
    }

    private void disable(User user) {
        jdbc.update("UPDATE own_user SET enabled = false WHERE id = ?", user.getId());
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

    /**
     * Asserts a 409 {@code code} with the common body (PPB-26) and returns its blocking types, sorted.
     */
    private List<String> assertInUse(Response response, String code) {
        assertEquals(409, response.status(), response.toString());
        JsonNode body = json(response);
        assertAll(
                () -> assertFalse(body.get("success").asBoolean()),
                () -> assertEquals(code, body.get("code").asText(), response.toString()),
                () -> assertFalse(body.get("message").asText().isBlank()),
                () -> assertFalse(response.body().toLowerCase().contains("constraint"), response.toString()),
                () -> assertFalse(response.body().toLowerCase().contains("sql"), response.toString()));
        JsonNode types = body.get("blockingTypes");
        return types == null ? List.of() : StreamSupport.stream(types.spliterator(), false)
                .map(JsonNode::asText).sorted().toList();
    }

    private void assertDeleted(Response response) {
        assertEquals(200, response.status(), response.toString());
        assertEquals("{\"success\":true,\"message\":\"Deleted successfully\"}", response.body());
    }

    /**
     * The foreign keys from {@code table}.{@code column}: ON DELETE rule (a NO ACTION, c CASCADE, n SET NULL) and
     * whether the column leads a valid, non-partial index.
     */
    private String schemaOf(String table, String column) {
        return jdbc.queryForObject("""
                SELECT string_agg(c.confdeltype::text || ' ' || (EXISTS (SELECT 1 FROM pg_index i
                           WHERE i.indrelid = c.conrelid AND i.indkey[0] = c.conkey[1] AND i.indisvalid
                           AND i.indpred IS NULL))::text, ',')
                FROM pg_constraint c JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = c.conkey[1]
                WHERE c.contype = 'f' AND c.conrelid = ?::regclass AND a.attname = ?""", String.class, table, column);
    }

    // =====================================================================================================
    // E-1: asset
    // =====================================================================================================

    @Nested
    class AssetDeletion {

        private static final String ASSET_IN_USE = "ASSET_IN_USE";

        private Response deleteAsset(Asset asset, User user) {
            return call(delete("/assets/" + asset.getId()), user);
        }

        /**
         * Creates the dependent with {@code dependent}, deletes the asset as A and asserts 409 with exactly
         * {@code type}, the asset unchanged and no audit record.
         */
        private void assertBlockedBy(String type, BiFunction<CommonData, Asset, Long> dependent, String table) {
            CommonData data = data();
            Asset asset = records.asset(data.o1().company());
            Long dependentId = dependent.apply(data, asset);
            List<Map<String, Object>> assetBefore = row("asset", asset.getId());
            List<Map<String, Object>> dependentBefore = row(table, dependentId);

            Response response = deleteAsset(asset, data.o1().admin());

            assertEquals(List.of(type), assertInUse(response, ASSET_IN_USE));
            assertAll(
                    () -> assertEquals(assetBefore, row("asset", asset.getId())),
                    () -> assertEquals(dependentBefore, row(table, dependentId)),
                    () -> assertEquals(0, auditRecords(data)));
        }

        @Test
        void ca005_childAsset_409WithAsset_bothExist() {
            assertBlockedBy("ASSET", (data, asset) -> {
                Asset child = records.asset(asset.getCompany());
                jdbc.update("UPDATE asset SET parent_asset_id = ? WHERE id = ?", asset.getId(), child.getId());
                return child.getId();
            }, "asset");
        }

        @Test
        void meter_409WithMeter() {
            assertBlockedBy("METER", (data, asset) -> module.meter(asset.getCompany(), asset).getId(), "meter");
        }

        @Test
        void request_409WithRequest() {
            assertBlockedBy("REQUEST", (data, asset) -> {
                Long request = module.request(asset.getCompany()).getId();
                jdbc.update("UPDATE request SET asset_id = ? WHERE id = ?", asset.getId(), request);
                return request;
            }, "request");
        }

        @Test
        void preventiveMaintenance_409WithPreventiveMaintenance() {
            assertBlockedBy("PREVENTIVE_MAINTENANCE", (data, asset) -> {
                Long preventiveMaintenance = module.preventiveMaintenance(asset.getCompany()).getId();
                jdbc.update("UPDATE preventive_maintenance SET asset_id = ? WHERE id = ?", asset.getId(),
                        preventiveMaintenance);
                return preventiveMaintenance;
            }, "preventive_maintenance");
        }

        @Test
        void taskBase_409WithTaskBase() {
            assertBlockedBy("TASK_BASE", (data, asset) -> module.taskBase(asset.getCompany(), asset, null).getId(),
                    "task_base");
        }

        @Test
        void meterTrigger_409WithWorkOrderMeterTrigger() {
            assertBlockedBy("WORK_ORDER_METER_TRIGGER", (data, asset) -> {
                Long trigger = module.trigger(module.meter(asset.getCompany(), records.asset(asset.getCompany())),
                        WorkOrderMeterTriggerCondition.MORE_THAN, 10).getId();
                jdbc.update("UPDATE work_order_meter_trigger SET asset_id = ? WHERE id = ?", asset.getId(), trigger);
                return trigger;
            }, "work_order_meter_trigger");
        }

        @Test
        void ca111_workflowAction_409WithWorkflowAction_workflowActionAndConditionsKept() {
            // D-P14-1: the type is WORKFLOW_ACTION (the table that cites the asset), not WORKFLOW
            CommonData data = data();
            Asset asset = records.asset(data.o1().company());
            Workflow workflow = module.workflow(data.o1().company(), asset, null, null, null);
            Long action = workflow.getAction().getId();
            Long condition = workflow.getSecondaryConditions().iterator().next().getId();
            Map<String, List<Map<String, Object>>> before = new LinkedHashMap<>();
            before.put("workflow", row("workflow", workflow.getId()));
            before.put("workflow_action", row("workflow_action", action));
            before.put("workflow_condition", row("workflow_condition", condition));

            Response response = deleteAsset(asset, data.o1().admin());

            assertEquals(List.of("WORKFLOW_ACTION"), assertInUse(response, ASSET_IN_USE));
            Map<String, List<Map<String, Object>>> after = new LinkedHashMap<>();
            after.put("workflow", row("workflow", workflow.getId()));
            after.put("workflow_action", row("workflow_action", action));
            after.put("workflow_condition", row("workflow_condition", condition));
            assertAll(
                    () -> assertEquals(before, after),
                    () -> assertEquals(1, rows("asset", asset.getId())),
                    () -> assertEquals(0, auditRecords(data)));
        }

        @Test
        void workflowCondition_409WithWorkflowCondition() {
            assertBlockedBy("WORKFLOW_CONDITION", (data, asset) -> module.workflow(asset.getCompany(),
                    records.asset(asset.getCompany()), null, asset, null).getSecondaryConditions().iterator().next()
                    .getId(), "workflow_condition");
        }

        @Test
        void ca116_portalField_409WithRequestPortalField_never500_fieldKept() {
            assertBlockedBy("REQUEST_PORTAL_FIELD", (data, asset) -> records.portalCiting(asset.getCompany(), null, asset)
                    .getFields().get(0).getId(), "request_portal_field");
        }

        @Test
        void workOrderRevision_409WithWorkOrderHistory() {
            // D-P14-5: the work order now cites another asset; one of its revisions still cites this one
            assertBlockedBy("WORK_ORDER_HISTORY", (data, asset) -> {
                WorkOrder workOrder = records.workOrder(asset.getCompany(), null, Status.COMPLETE, false);
                jdbc.update("UPDATE work_order_aud SET asset_id = ? WHERE id = ?", asset.getId(), workOrder.getId());
                return workOrder.getId();
            }, "work_order_aud");
        }

        @Test
        void disabledAssignedUser_409WithUser() {
            // D-P14-4, §3.3 (c)
            assertBlockedBy("USER", (data, asset) -> {
                User technician = data.o1().technician();
                jdbc.update("INSERT INTO t_asset_user_associations (id_asset, id_user) VALUES (?, ?)", asset.getId(),
                        technician.getId());
                disable(technician);
                return technician.getId();
            }, "own_user");
        }

        @Test
        void enabledAssignedUser_200() {
            CommonData data = data();
            Asset asset = records.asset(data.o1().company());
            jdbc.update("INSERT INTO t_asset_user_associations (id_asset, id_user) VALUES (?, ?)", asset.getId(),
                    data.o1().technician().getId());

            assertDeleted(deleteAsset(asset, data.o1().admin()));
            assertEquals(0, rows("asset", asset.getId()));
        }

        @Test
        void everyDependent_409WithEveryType() {
            CommonData data = data();
            Company company = data.o1().company();
            Asset asset = records.asset(company);
            Asset child = records.asset(company);
            jdbc.update("UPDATE asset SET parent_asset_id = ? WHERE id = ?", asset.getId(), child.getId());
            module.meter(company, asset);
            records.workOrder(company, asset, Status.OPEN, false);
            module.taskBase(company, asset, null);
            records.portalCiting(company, null, asset);

            Response response = deleteAsset(asset, data.o1().admin());

            assertEquals(List.of("ASSET", "METER", "REQUEST_PORTAL_FIELD", "TASK_BASE", "WORK_ORDER"),
                    assertInUse(response, ASSET_IN_USE),
                    "a revision of a work order that cites the asset is not reported again");
        }

        @Test
        void dependentAddedBetweenCheckAndDeletion_409WithItsType_never500() {
            CommonData data = data();
            Asset asset = records.asset(data.o1().company());
            Long request = module.request(data.o1().company()).getId();
            faults.runBefore("asset", Operation.DELETE,
                    "UPDATE request SET asset_id = " + asset.getId() + " WHERE id = " + request);

            Response response = deleteAsset(asset, data.o1().admin());

            assertEquals(List.of("REQUEST"), assertInUse(response, ASSET_IN_USE));
            assertAll(
                    () -> assertEquals(1, rows("asset", asset.getId())),
                    () -> assertEquals(0, auditRecords(data)));
        }

        @Test
        void ca003_withoutDependentsWithDowntime_200_assetAndDowntimeGone_recorded() {
            // guard (D-P14-7): passed before P-14
            CommonData data = data();
            Asset asset = records.asset(data.o1().company(), "A-P14", false);
            AssetDowntime downtime = records.downtime(asset);

            Response response = deleteAsset(asset, data.o1().admin());

            assertDeleted(response);
            assertAll(
                    () -> assertEquals(0, rows("asset", asset.getId())),
                    () -> assertEquals(0, rows("asset_downtime", downtime.getId())),
                    () -> assertEquals(List.of("DELETE ASSET A-P14 " + asset.getName()
                            + " {\"asset_downtime\": 1} 0"), audit(data)));
        }

        @Test
        void ca075_createdByTechnician_deletedByTechnician_200() {
            // guard (D-P14-7): passed before P-14
            CommonData data = data();
            Asset asset = records.asset(data.o1().company());
            jdbc.update("UPDATE asset SET created_by = ? WHERE id = ?", data.o1().technician().getId(),
                    asset.getId());

            assertDeleted(deleteAsset(asset, data.o1().technician()));
            assertEquals(0, rows("asset", asset.getId()));
        }

        @Test
        void schema_referencesToAssetAreNoActionWithIndex() {
            assertAll(List.of("asset.parent_asset_id", "meter.asset_id", "preventive_maintenance.asset_id",
                    "request.asset_id", "task_base.asset_id", "work_order_meter_trigger.asset_id",
                    "workflow_action.asset_id", "workflow_condition.asset_id").stream().map(key -> (Executable) () ->
                    assertEquals("a true", schemaOf(key.split("\\.")[0], key.split("\\.")[1]), key)));
        }
    }

    /**
     * Deletes {@code path} as A of O1 and asserts 409 {@code code} with exactly {@code type}, the entity and the
     * dependent unchanged and no audit record.
     */
    private void assertBlockedBy(CommonData data, String path, String table, Long id, String code, String type,
                                 String dependentTable, Long dependentId) {
        List<Map<String, Object>> before = row(table, id);
        List<Map<String, Object>> dependentBefore = row(dependentTable, dependentId);

        Response response = call(delete(path + id), data.o1().admin());

        assertEquals(List.of(type), assertInUse(response, code));
        assertAll(
                () -> assertEquals(before, row(table, id)),
                () -> assertEquals(dependentBefore, row(dependentTable, dependentId)),
                () -> assertEquals(0, auditRecords(data)));
    }

    // =====================================================================================================
    // E-2: location
    // =====================================================================================================

    @Nested
    class LocationDeletion {

        private static final String LOCATION_IN_USE = "LOCATION_IN_USE";
        private static final String PATH = "/locations/";

        /**
         * A location of O1 cited in {@code table}.location_id by the row {@code dependentId}; asserts the 409.
         */
        private void assertCitedBy(CommonData data, String type, String table, Long dependentId) {
            Location location = records.location(data.o1().company());
            jdbc.update("UPDATE " + table + " SET location_id = ? WHERE id = ?", location.getId(), dependentId);
            assertBlockedBy(data, PATH, "location", location.getId(), LOCATION_IN_USE, type, table, dependentId);
        }

        @Test
        void ca006_childLocationAndAsset_409WithLocationAndAsset_noChanges() {
            CommonData data = data();
            Location location = records.location(data.o1().company());
            Location child = records.location(data.o1().company());
            jdbc.update("UPDATE location SET parent_location_id = ? WHERE id = ?", location.getId(), child.getId());
            Asset asset = records.asset(data.o1().company());
            jdbc.update("UPDATE asset SET location_id = ? WHERE id = ?", location.getId(), asset.getId());
            Map<String, List<Map<String, Object>>> before = new LinkedHashMap<>();
            before.put("location", row("location", location.getId()));
            before.put("child", row("location", child.getId()));
            before.put("asset", row("asset", asset.getId()));

            Response response = call(delete(PATH + location.getId()), data.o1().admin());

            assertEquals(List.of("ASSET", "LOCATION"), assertInUse(response, LOCATION_IN_USE));
            Map<String, List<Map<String, Object>>> after = new LinkedHashMap<>();
            after.put("location", row("location", location.getId()));
            after.put("child", row("location", child.getId()));
            after.put("asset", row("asset", asset.getId()));
            assertAll(
                    () -> assertEquals(before, after),
                    () -> assertEquals(0, auditRecords(data)));
        }

        @Test
        void meter_409WithMeter() {
            CommonData data = data();
            Meter meter = module.meter(data.o1().company(), records.asset(data.o1().company()));
            assertCitedBy(data, "METER", "meter", meter.getId());
        }

        @Test
        void userOfTheLocation_409WithUser() {
            CommonData data = data();
            assertCitedBy(data, "USER", "own_user", data.o1().technician().getId());
        }

        @Test
        void disabledWorker_409WithUser() {
            // D-P14-4, §3.3 (c)
            CommonData data = data();
            Location location = records.location(data.o1().company());
            User technician = data.o1().technician();
            jdbc.update("INSERT INTO t_location_user_associations (id_location, id_user) VALUES (?, ?)",
                    location.getId(), technician.getId());
            disable(technician);
            assertBlockedBy(data, PATH, "location", location.getId(), LOCATION_IN_USE, "USER", "own_user",
                    technician.getId());
        }

        @Test
        void enabledWorker_200() {
            CommonData data = data();
            Location location = records.location(data.o1().company());
            jdbc.update("INSERT INTO t_location_user_associations (id_location, id_user) VALUES (?, ?)",
                    location.getId(), data.o1().technician().getId());

            assertDeleted(call(delete(PATH + location.getId()), data.o1().admin()));
        }

        @Test
        void preventiveMaintenance_409WithPreventiveMaintenance() {
            CommonData data = data();
            assertCitedBy(data, "PREVENTIVE_MAINTENANCE", "preventive_maintenance",
                    module.preventiveMaintenance(data.o1().company()).getId());
        }

        @Test
        void request_409WithRequest() {
            CommonData data = data();
            assertCitedBy(data, "REQUEST", "request", module.request(data.o1().company()).getId());
        }

        @Test
        void workOrder_409WithWorkOrder() {
            CommonData data = data();
            assertCitedBy(data, "WORK_ORDER", "work_order",
                    records.workOrder(data.o1().company(), null, Status.COMPLETE, false).getId());
        }

        @Test
        void workOrderRevision_409WithWorkOrderHistory() {
            // the work order no longer cites the location; one of its revisions does
            CommonData data = data();
            Location location = records.location(data.o1().company());
            WorkOrder workOrder = records.workOrder(data.o1().company(), null, Status.COMPLETE, false);
            jdbc.update("UPDATE work_order_aud SET location_id = ? WHERE id = ?", location.getId(), workOrder.getId());
            assertBlockedBy(data, PATH, "location", location.getId(), LOCATION_IN_USE, "WORK_ORDER_HISTORY",
                    "work_order", workOrder.getId());
        }

        @Test
        void meterTrigger_409WithWorkOrderMeterTrigger() {
            CommonData data = data();
            Meter meter = module.meter(data.o1().company(), records.asset(data.o1().company()));
            assertCitedBy(data, "WORK_ORDER_METER_TRIGGER", "work_order_meter_trigger",
                    module.trigger(meter, WorkOrderMeterTriggerCondition.MORE_THAN, 10).getId());
        }

        @Test
        void workflowAction_409WithWorkflowAction() {
            CommonData data = data();
            Workflow workflow = module.workflow(data.o1().company(), records.asset(data.o1().company()), null,
                    records.asset(data.o1().company()), null);
            assertCitedBy(data, "WORKFLOW_ACTION", "workflow_action", workflow.getAction().getId());
        }

        @Test
        void workflowCondition_409WithWorkflowCondition() {
            CommonData data = data();
            Workflow workflow = module.workflow(data.o1().company(), records.asset(data.o1().company()), null,
                    records.asset(data.o1().company()), null);
            assertCitedBy(data, "WORKFLOW_CONDITION", "workflow_condition",
                    workflow.getSecondaryConditions().iterator().next().getId());
        }

        @Test
        void portalField_409WithRequestPortalField() {
            CommonData data = data();
            Location location = records.location(data.o1().company());
            Long field = records.portalCiting(data.o1().company(), location, null).getFields().get(0).getId();
            assertBlockedBy(data, PATH, "location", location.getId(), LOCATION_IN_USE, "REQUEST_PORTAL_FIELD",
                    "request_portal_field", field);
        }

        @Test
        void dependentAddedBetweenCheckAndDeletion_409WithItsType_never500() {
            CommonData data = data();
            Location location = records.location(data.o1().company());
            Meter meter = module.meter(data.o1().company(), records.asset(data.o1().company()));
            faults.runBefore("location", Operation.DELETE,
                    "UPDATE meter SET location_id = " + location.getId() + " WHERE id = " + meter.getId());

            Response response = call(delete(PATH + location.getId()), data.o1().admin());

            assertEquals(List.of("METER"), assertInUse(response, LOCATION_IN_USE));
            assertAll(
                    () -> assertEquals(1, rows("location", location.getId())),
                    () -> assertEquals(0, auditRecords(data)));
        }

        @Test
        void withoutDependentsWithFloorPlan_200_deletedWithItsFloorPlan_recorded() {
            CommonData data = data();
            Location location = records.location(data.o1().company());
            jdbc.update("UPDATE location SET custom_id = 'L-P14' WHERE id = ?", location.getId());
            Long floorPlan = module.floorPlan(location).getId();

            assertDeleted(call(delete(PATH + location.getId()), data.o1().admin()));
            assertAll(
                    () -> assertEquals(0, rows("location", location.getId())),
                    () -> assertEquals(0, rows("floor_plan", floorPlan)),
                    () -> assertEquals(List.of("DELETE LOCATION L-P14 " + location.getName()
                            + " {\"floor_plan\": 1} 0"), audit(data)));
        }

        @Test
        void technicianNotCreator_403_otherOrganization_403_unknown_404_noAuditRecord() {
            CommonData data = data();
            Location location = records.location(data.o1().company());

            Response technician = call(delete(PATH + location.getId()), data.o1().technician());
            Response otherOrganization = call(delete(PATH + location.getId()), data.o2().admin());
            Response unknown = call(delete(PATH + Long.MAX_VALUE), data.o1().admin());

            assertAll(
                    () -> assertEquals(403, technician.status(), technician.toString()),
                    () -> assertEquals(403, otherOrganization.status(), otherOrganization.toString()),
                    () -> assertEquals(404, unknown.status(), unknown.toString()),
                    () -> assertEquals(1, rows("location", location.getId())),
                    () -> assertEquals(0, auditRecords(data)));
        }

        @Test
        void schema_referencesToLocationAreNoActionWithIndex() {
            assertAll(List.of("asset.location_id", "location.parent_location_id", "meter.location_id",
                    "own_user.location_id", "preventive_maintenance.location_id", "request.location_id",
                    "work_order.location_id", "work_order_aud.location_id", "work_order_meter_trigger.location_id",
                    "workflow_action.location_id", "workflow_condition.location_id").stream().map(key ->
                    (Executable) () -> assertEquals("a true", schemaOf(key.split("\\.")[0], key.split("\\.")[1]),
                            key)));
        }
    }

    // =====================================================================================================
    // E-3: meter
    // =====================================================================================================

    @Nested
    class MeterDeletion {

        private static final String METER_IN_USE = "METER_IN_USE";
        private static final String PATH = "/meters/";

        private Meter meter(CommonData data) {
            return module.meter(data.o1().company(), records.asset(data.o1().company()));
        }

        @Test
        void ca010_reading_409WithReading_readingKept() {
            CommonData data = data();
            Meter meter = meter(data);
            Long reading = module.reading(meter, 10).getId();
            assertBlockedBy(data, PATH, "meter", meter.getId(), METER_IN_USE, "READING", "reading", reading);
        }

        @Test
        void taskBase_409WithTaskBase() {
            CommonData data = data();
            Meter meter = meter(data);
            Long taskBase = module.taskBase(data.o1().company(), null, meter).getId();
            assertBlockedBy(data, PATH, "meter", meter.getId(), METER_IN_USE, "TASK_BASE", "task_base", taskBase);
        }

        @Test
        void meterTrigger_409WithWorkOrderMeterTrigger() {
            // D-P14-3: checked by the application; work_order_meter_trigger.meter_id stays CASCADE
            CommonData data = data();
            Meter meter = meter(data);
            Long trigger = module.trigger(meter, WorkOrderMeterTriggerCondition.MORE_THAN, 10).getId();
            assertBlockedBy(data, PATH, "meter", meter.getId(), METER_IN_USE, "WORK_ORDER_METER_TRIGGER",
                    "work_order_meter_trigger", trigger);
        }

        @Test
        void disabledUser_409WithUser() {
            // D-P14-4, §3.3 (c)
            CommonData data = data();
            Meter meter = meter(data);
            User technician = data.o1().technician();
            jdbc.update("INSERT INTO t_meter_user_associations (id_meter, id_user) VALUES (?, ?)", meter.getId(),
                    technician.getId());
            disable(technician);
            assertBlockedBy(data, PATH, "meter", meter.getId(), METER_IN_USE, "USER", "own_user",
                    technician.getId());
        }

        @Test
        void readingAddedBetweenCheckAndDeletion_409WithReading_never500() {
            CommonData data = data();
            Meter meter = meter(data);
            faults.runBefore("meter", Operation.DELETE, "INSERT INTO reading (id, created_at, updated_at, value, "
                    + "meter_id) SELECT coalesce(max(id), 0) + 1000000, now(), now(), 1, " + meter.getId()
                    + " FROM reading");

            Response response = call(delete(PATH + meter.getId()), data.o1().admin());

            assertEquals(List.of("READING"), assertInUse(response, METER_IN_USE));
            assertAll(
                    () -> assertEquals(1, rows("meter", meter.getId())),
                    () -> assertEquals(0, auditRecords(data)));
        }

        @Test
        void withoutDependentsWithAnEnabledUser_200_recorded() {
            CommonData data = data();
            Meter meter = meter(data);
            jdbc.update("INSERT INTO t_meter_user_associations (id_meter, id_user) VALUES (?, ?)", meter.getId(),
                    data.o1().technician().getId());

            assertDeleted(call(delete(PATH + meter.getId()), data.o1().admin()));
            assertAll(
                    () -> assertEquals(0, rows("meter", meter.getId())),
                    () -> assertEquals(List.of("DELETE METER - " + meter.getName()
                            + " {\"t_meter_user_associations\": 1} 0"), audit(data)));
        }

        @Test
        void technicianNotCreator_403_otherOrganization_403_unknown_404_noAuditRecord() {
            CommonData data = data();
            Meter meter = meter(data);

            Response technician = call(delete(PATH + meter.getId()), data.o1().technician());
            Response otherOrganization = call(delete(PATH + meter.getId()), data.o2().admin());
            Response unknown = call(delete(PATH + Long.MAX_VALUE), data.o1().admin());

            assertAll(
                    () -> assertEquals(403, technician.status(), technician.toString()),
                    () -> assertEquals(403, otherOrganization.status(), otherOrganization.toString()),
                    () -> assertEquals(404, unknown.status(), unknown.toString()),
                    () -> assertEquals(1, rows("meter", meter.getId())),
                    () -> assertEquals(0, auditRecords(data)));
        }

        @Test
        void schema_referencesToMeterAreNoActionWithIndex_triggerStaysCascade() {
            assertAll(
                    () -> assertEquals("a true", schemaOf("reading", "meter_id")),
                    () -> assertEquals("a true", schemaOf("task_base", "meter_id")),
                    () -> assertEquals("c false", schemaOf("work_order_meter_trigger", "meter_id")));
        }
    }

    // =====================================================================================================
    // E-5: readings are immutable (PPB-35)
    // =====================================================================================================

    @Nested
    class Readings {

        private static final String HISTORY_IMMUTABLE = "HISTORY_IMMUTABLE";

        private Response patchReading(Long id, User user) {
            return call(patch("/readings/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"value\":98}"),
                    user);
        }

        private long workOrders() {
            return count("SELECT count(*) FROM work_order");
        }

        /**
         * A meter of O1 with a reading of 100 and a trigger «less than 99»: changing the reading to 98 would fire it.
         */
        private Long readingWithTrigger(CommonData data) {
            Meter meter = module.meter(data.o1().company(), records.asset(data.o1().company()));
            module.trigger(meter, WorkOrderMeterTriggerCondition.LESS_THAN, 99);
            return module.reading(meter, 100).getId();
        }

        private void assertUnchanged(Long reading, List<Map<String, Object>> before, long workOrdersBefore) {
            assertAll(
                    () -> assertEquals(before, row("reading", reading)),
                    () -> assertEquals(workOrdersBefore, workOrders(), "no trigger was evaluated"),
                    () -> assertTrue(webhookDouble.calls().isEmpty(), "no webhook"));
        }

        @Test
        void ca014_patch_409HistoryImmutable_unchanged_noTriggerEvaluated() {
            CommonData data = data();
            Long reading = readingWithTrigger(data);
            List<Map<String, Object>> before = row("reading", reading);
            long workOrdersBefore = workOrders();

            Response response = patchReading(reading, data.o1().admin());

            assertEquals(List.of(), assertInUse(response, HISTORY_IMMUTABLE));
            assertUnchanged(reading, before, workOrdersBefore);
        }

        @Test
        void ca014_delete_409HistoryImmutable_unchanged_noTriggerEvaluated() {
            CommonData data = data();
            Long reading = readingWithTrigger(data);
            List<Map<String, Object>> before = row("reading", reading);
            long workOrdersBefore = workOrders();

            Response response = call(delete("/readings/" + reading), data.o1().admin());

            assertEquals(List.of(), assertInUse(response, HISTORY_IMMUTABLE));
            assertUnchanged(reading, before, workOrdersBefore);
        }

        @Test
        void c1_otherOrganization_403OnPatchAndDelete_unchanged_noTriggerEvaluated() {
            // D-P14-6 (C-1): isolation between organizations, checked before the 409
            CommonData data = data();
            Long reading = readingWithTrigger(data);
            List<Map<String, Object>> before = row("reading", reading);
            long workOrdersBefore = workOrders();

            Response patched = patchReading(reading, data.o2().admin());
            Response deleted = call(delete("/readings/" + reading), data.o2().admin());

            assertAll(
                    () -> assertEquals(403, patched.status(), patched.toString()),
                    () -> assertEquals(403, deleted.status(), deleted.toString()));
            assertUnchanged(reading, before, workOrdersBefore);
        }

        @Test
        void withoutAccessToTheMeter_403BeforeThe409() {
            CommonData data = data();
            Long reading = readingWithTrigger(data);

            Response patched = patchReading(reading, data.o1().technician());
            Response deleted = call(delete("/readings/" + reading), data.o1().technician());

            assertAll(
                    () -> assertEquals(403, patched.status(), patched.toString()),
                    () -> assertEquals(403, deleted.status(), deleted.toString()),
                    () -> assertEquals(1, rows("reading", reading)));
        }

        @Test
        void unknownReading_404() {
            CommonData data = data();

            Response patched = patchReading(Long.MAX_VALUE, data.o1().admin());
            Response deleted = call(delete("/readings/" + Long.MAX_VALUE), data.o1().admin());

            assertAll(
                    () -> assertEquals(404, patched.status(), patched.toString()),
                    () -> assertEquals(404, deleted.status(), deleted.toString()));
        }

        @Test
        void ca013_correctionAsANewReading_200_bothKept_newOneEvaluated() {
            // guard (D-P14-7): passed before P-14
            CommonData data = data();
            Meter meter = module.meter(data.o1().company(), records.asset(data.o1().company()));
            module.trigger(meter, WorkOrderMeterTriggerCondition.LESS_THAN, 99);
            Long r1 = module.reading(meter, 100).getId();
            jdbc.update("UPDATE reading SET created_at = now() - interval '2 days' WHERE id = ?", r1);
            long workOrdersBefore = count("SELECT count(*) FROM work_order WHERE company_id = ?",
                    data.o1().company().getId());

            Response response = call(post("/readings").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"value\":98,\"meter\":{\"id\":" + meter.getId() + "}}"), data.o1().admin());

            assertEquals(200, response.status(), response.toString());
            assertAll(
                    () -> assertEquals(100.0, jdbc.queryForObject("SELECT value FROM reading WHERE id = ?",
                            Double.class, r1)),
                    () -> assertEquals(2, count("SELECT count(*) FROM reading WHERE meter_id = ?", meter.getId())),
                    () -> assertEquals(workOrdersBefore + 1, count("SELECT count(*) FROM work_order WHERE "
                            + "company_id = ?", data.o1().company().getId()), "R2 fired the trigger"));
        }
    }

    // =====================================================================================================
    // E-4: asset and meter categories
    // =====================================================================================================

    @Nested
    class CategoryDeletion {

        private Long assetCategory(CommonData data) {
            return assetCategoryRepository.saveAndFlush(new AssetCategory(TestRecords.unique("P-14 asset category"),
                    data.o1().company().getCompanySettings())).getId();
        }

        private Long meterCategory(CommonData data) {
            return meterCategoryRepository.saveAndFlush(new MeterCategory(TestRecords.unique("P-14 meter category"),
                    data.o1().company().getCompanySettings())).getId();
        }

        @Test
        void ca100_assetCategoryUsedByAnAsset_409WithAsset_noChanges() {
            // D-P14-2: only assets cite an asset category
            CommonData data = data();
            Long category = assetCategory(data);
            Asset asset = records.asset(data.o1().company());
            jdbc.update("UPDATE asset SET category_id = ? WHERE id = ?", category, asset.getId());
            assertBlockedBy(data, "/asset-categories/", "asset_category", category, "ASSET_CATEGORY_IN_USE", "ASSET",
                    "asset", asset.getId());
        }

        @Test
        void ca102_meterCategoryUsedByAMeter_409WithMeter_noChanges() {
            CommonData data = data();
            Long category = meterCategory(data);
            Meter meter = module.meter(data.o1().company(), records.asset(data.o1().company()));
            jdbc.update("UPDATE meter SET meter_category_id = ? WHERE id = ?", category, meter.getId());
            assertBlockedBy(data, "/meter-categories/", "meter_category", category, "METER_CATEGORY_IN_USE", "METER",
                    "meter", meter.getId());
        }

        @Test
        void assetCategoryGivenBetweenCheckAndDeletion_409WithAsset_never500() {
            CommonData data = data();
            Long category = assetCategory(data);
            Asset asset = records.asset(data.o1().company());
            faults.runBefore("asset_category", Operation.DELETE,
                    "UPDATE asset SET category_id = " + category + " WHERE id = " + asset.getId());

            Response response = call(delete("/asset-categories/" + category), data.o1().admin());

            assertEquals(List.of("ASSET"), assertInUse(response, "ASSET_CATEGORY_IN_USE"));
            assertEquals(1, rows("asset_category", category));
        }

        @Test
        void meterCategoryGivenBetweenCheckAndDeletion_409WithMeter_never500() {
            CommonData data = data();
            Long category = meterCategory(data);
            Meter meter = module.meter(data.o1().company(), records.asset(data.o1().company()));
            faults.runBefore("meter_category", Operation.DELETE,
                    "UPDATE meter SET meter_category_id = " + category + " WHERE id = " + meter.getId());

            Response response = call(delete("/meter-categories/" + category), data.o1().admin());

            assertEquals(List.of("METER"), assertInUse(response, "METER_CATEGORY_IN_USE"));
            assertEquals(1, rows("meter_category", category));
        }

        @Test
        void unused_200_deletedAndRecorded() {
            CommonData data = data();
            Long assetCategory = assetCategory(data);
            Long meterCategory = meterCategory(data);
            String assetName = jdbc.queryForObject("SELECT name FROM asset_category WHERE id = ?", String.class,
                    assetCategory);
            String meterName = jdbc.queryForObject("SELECT name FROM meter_category WHERE id = ?", String.class,
                    meterCategory);

            assertDeleted(call(delete("/asset-categories/" + assetCategory), data.o1().admin()));
            assertDeleted(call(delete("/meter-categories/" + meterCategory), data.o1().admin()));
            assertEquals(List.of("DELETE ASSET_CATEGORY - " + assetName + " {} 0",
                    "DELETE METER_CATEGORY - " + meterName + " {} 0"), audit(data));
        }

        @Test
        void technicianNotCreator_403_otherOrganization_403_unknown_404_noAuditRecord() {
            CommonData data = data();
            Long assetCategory = assetCategory(data);
            Long meterCategory = meterCategory(data);

            List<Integer> statuses = List.of(
                    call(delete("/asset-categories/" + assetCategory), data.o1().technician()).status(),
                    call(delete("/meter-categories/" + meterCategory), data.o1().technician()).status(),
                    call(delete("/asset-categories/" + assetCategory), data.o2().admin()).status(),
                    call(delete("/meter-categories/" + meterCategory), data.o2().admin()).status(),
                    call(delete("/asset-categories/" + Long.MAX_VALUE), data.o1().admin()).status(),
                    call(delete("/meter-categories/" + Long.MAX_VALUE), data.o1().admin()).status());

            assertAll(
                    () -> assertEquals(List.of(403, 403, 403, 403, 404, 404), statuses),
                    () -> assertEquals(1, rows("asset_category", assetCategory)),
                    () -> assertEquals(1, rows("meter_category", meterCategory)),
                    () -> assertEquals(0, auditRecords(data)));
        }

        @Test
        void schema_referencesToTheCategoriesAreNoActionWithIndex() {
            assertAll(
                    () -> assertEquals("a true", schemaOf("asset", "category_id")),
                    () -> assertEquals("a true", schemaOf("meter", "meter_category_id")));
        }
    }
}
