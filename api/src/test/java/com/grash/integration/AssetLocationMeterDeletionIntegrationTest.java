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
import com.grash.model.AssetDowntime;
import com.grash.model.Company;
import com.grash.model.Meter;
import com.grash.model.User;
import com.grash.model.WorkOrder;
import com.grash.model.Workflow;
import com.grash.model.enums.Status;
import com.grash.model.enums.WorkOrderMeterTriggerCondition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
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
    private final List<CommonData> created = new ArrayList<>();

    @Autowired
    AssetLocationMeterDeletionIntegrationTest(TestOrganizations organizations, TestRecords records,
                                              TestAssetRecords module, MockMvc mockMvc, ObjectMapper objectMapper,
                                              JdbcTemplate jdbc) {
        this.organizations = organizations;
        this.records = records;
        this.module = module;
        this.api = new TestApi(mockMvc, objectMapper);
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
        this.faults = new FaultInjector(jdbc);
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
}
