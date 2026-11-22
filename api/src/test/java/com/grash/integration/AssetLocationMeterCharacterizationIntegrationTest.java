package com.grash.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grash.integration.support.TestApi;
import com.grash.integration.support.TestApi.Response;
import com.grash.integration.support.TestOrganizations;
import com.grash.integration.support.TestOrganizations.CommonData;
import com.grash.integration.support.TestRecords;
import com.grash.model.Asset;
import com.grash.model.AssetCategory;
import com.grash.model.AssetDowntime;
import com.grash.model.Company;
import com.grash.model.Deprecation;
import com.grash.model.File;
import com.grash.model.FloorPlan;
import com.grash.model.Location;
import com.grash.model.Meter;
import com.grash.model.MeterCategory;
import com.grash.model.PreventiveMaintenance;
import com.grash.model.Reading;
import com.grash.model.Request;
import com.grash.model.TaskBase;
import com.grash.model.User;
import com.grash.model.WorkOrder;
import com.grash.model.WorkOrderMeterTrigger;
import com.grash.model.Workflow;
import com.grash.model.WorkflowAction;
import com.grash.model.WorkflowCondition;
import com.grash.model.enums.FileType;
import com.grash.model.enums.Status;
import com.grash.model.enums.WorkOrderMeterTriggerCondition;
import com.grash.model.enums.workflow.WFMainCondition;
import com.grash.model.enums.workflow.WorkOrderAction;
import com.grash.model.enums.workflow.WorkOrderCondition;
import com.grash.repository.AssetCategoryRepository;
import com.grash.repository.DeprecationRepository;
import com.grash.repository.FileRepository;
import com.grash.repository.FloorPlanRepository;
import com.grash.repository.MeterCategoryRepository;
import com.grash.repository.MeterRepository;
import com.grash.repository.PreventiveMaintenanceRepository;
import com.grash.repository.ReadingRepository;
import com.grash.repository.RequestRepository;
import com.grash.repository.TaskBaseRepository;
import com.grash.repository.WorkOrderMeterTriggerRepository;
import com.grash.repository.WorkflowRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * P-14 characterization: fixes how assets, locations, meters, their categories, readings and their children (meter
 * trigger, floor plan, downtime, depreciation) were deleted or edited before any change, how a disabled user was
 * accepted on them, how the demo purge removed them, and the foreign keys that point to them. The 403 and 404
 * answers must keep passing after P-14; the tests that show a subtree deleted in cascade, references set to null, a
 * reading edited or deleted, a deletion without audit record or a disabled user accepted are updated to the
 * contract of P-14, as step 8 of the order allows.
 */
@AutoConfigureMockMvc
class AssetLocationMeterCharacterizationIntegrationTest extends AbstractIntegrationTest {

    private final TestOrganizations organizations;
    private final TestRecords records;
    private final TestApi api;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;
    private final MeterRepository meterRepository;
    private final ReadingRepository readingRepository;
    private final RequestRepository requestRepository;
    private final PreventiveMaintenanceRepository preventiveMaintenanceRepository;
    private final TaskBaseRepository taskBaseRepository;
    private final WorkOrderMeterTriggerRepository triggerRepository;
    private final WorkflowRepository workflowRepository;
    private final FloorPlanRepository floorPlanRepository;
    private final DeprecationRepository deprecationRepository;
    private final AssetCategoryRepository assetCategoryRepository;
    private final MeterCategoryRepository meterCategoryRepository;
    private final FileRepository fileRepository;
    private final List<CommonData> created = new ArrayList<>();

    @Autowired
    AssetLocationMeterCharacterizationIntegrationTest(TestOrganizations organizations, TestRecords records,
                                                      MockMvc mockMvc, ObjectMapper objectMapper, JdbcTemplate jdbc,
                                                      MeterRepository meterRepository,
                                                      ReadingRepository readingRepository,
                                                      RequestRepository requestRepository,
                                                      PreventiveMaintenanceRepository preventiveMaintenanceRepository,
                                                      TaskBaseRepository taskBaseRepository,
                                                      WorkOrderMeterTriggerRepository triggerRepository,
                                                      WorkflowRepository workflowRepository,
                                                      FloorPlanRepository floorPlanRepository,
                                                      DeprecationRepository deprecationRepository,
                                                      AssetCategoryRepository assetCategoryRepository,
                                                      MeterCategoryRepository meterCategoryRepository,
                                                      FileRepository fileRepository) {
        this.organizations = organizations;
        this.records = records;
        this.api = new TestApi(mockMvc, objectMapper);
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
        this.meterRepository = meterRepository;
        this.readingRepository = readingRepository;
        this.requestRepository = requestRepository;
        this.preventiveMaintenanceRepository = preventiveMaintenanceRepository;
        this.taskBaseRepository = taskBaseRepository;
        this.triggerRepository = triggerRepository;
        this.workflowRepository = workflowRepository;
        this.floorPlanRepository = floorPlanRepository;
        this.deprecationRepository = deprecationRepository;
        this.assetCategoryRepository = assetCategoryRepository;
        this.meterCategoryRepository = meterCategoryRepository;
        this.fileRepository = fileRepository;
    }

    @AfterEach
    void cleanUp() {
        created.forEach(records::deleteRequestPortals);
        // readings have no company: they are not removed in cascade with it
        created.forEach(data -> jdbc.update("DELETE FROM reading WHERE meter_id IN (SELECT id FROM meter "
                + "WHERE company_id IN (?, ?))", data.o1().company().getId(), data.o2().company().getId()));
        created.forEach(organizations::delete);
        created.clear();
    }

    private CommonData data() {
        CommonData data = organizations.create();
        created.add(data);
        return data;
    }

    // --- records ---

    private Meter meter(Company company, Asset asset) {
        Meter meter = new Meter();
        meter.setName(TestRecords.unique("P-14 meter"));
        meter.setUpdateFrequency(1);
        meter.setAsset(asset);
        meter.setCompany(company);
        return meterRepository.saveAndFlush(meter);
    }

    private Reading reading(Meter meter, double value) {
        Reading reading = new Reading();
        reading.setMeter(meter);
        reading.setValue(value);
        return readingRepository.saveAndFlush(reading);
    }

    private Request request(Company company) {
        Request request = new Request();
        request.setTitle(TestRecords.unique("P-14 request"));
        request.setCompany(company);
        return requestRepository.saveAndFlush(request);
    }

    private PreventiveMaintenance preventiveMaintenance(Company company) {
        PreventiveMaintenance preventiveMaintenance = new PreventiveMaintenance();
        preventiveMaintenance.setName(TestRecords.unique("P-14 PM"));
        preventiveMaintenance.setTitle(preventiveMaintenance.getName());
        preventiveMaintenance.setCompany(company);
        return preventiveMaintenanceRepository.saveAndFlush(preventiveMaintenance);
    }

    private TaskBase taskBase(Company company, Asset asset, Meter meter) {
        TaskBase taskBase = new TaskBase();
        taskBase.setLabel(TestRecords.unique("P-14 task"));
        taskBase.setCompany(company);
        taskBase.setAsset(asset);
        taskBase.setMeter(meter);
        return taskBaseRepository.saveAndFlush(taskBase);
    }

    private WorkOrderMeterTrigger trigger(Meter meter, WorkOrderMeterTriggerCondition condition, int value) {
        WorkOrderMeterTrigger trigger = new WorkOrderMeterTrigger();
        trigger.setName(TestRecords.unique("P-14 trigger"));
        trigger.setTitle(trigger.getName());
        trigger.setTriggerCondition(condition);
        trigger.setValue(value);
        trigger.setMeter(meter);
        trigger.setCompany(meter.getCompany());
        return triggerRepository.saveAndFlush(trigger);
    }

    /**
     * A workflow whose action assigns {@code actionAsset} or {@code actionLocation}, and whose condition is on
     * {@code conditionAsset} or {@code conditionLocation} (any may be {@code null}).
     */
    private Workflow workflow(Company company, Asset actionAsset, Location actionLocation, Asset conditionAsset,
                              Location conditionLocation) {
        WorkflowAction action = new WorkflowAction();
        action.setCompany(company);
        action.setWorkOrderAction(actionLocation != null ? WorkOrderAction.ASSIGN_LOCATION
                : WorkOrderAction.ASSIGN_ASSET);
        action.setAsset(actionAsset);
        action.setLocation(actionLocation);
        WorkflowCondition condition = new WorkflowCondition();
        condition.setCompany(company);
        condition.setWorkOrderCondition(conditionLocation != null ? WorkOrderCondition.LOCATION_IS
                : WorkOrderCondition.ASSET_IS);
        condition.setAsset(conditionAsset);
        condition.setLocation(conditionLocation);
        Workflow workflow = new Workflow();
        workflow.setCompany(company);
        workflow.setTitle(TestRecords.unique("P-14 workflow"));
        workflow.setMainCondition(WFMainCondition.WORK_ORDER_CREATED);
        workflow.setAction(action);
        workflow.setSecondaryConditions(new ArrayList<>(List.of(condition)));
        return workflowRepository.saveAndFlush(workflow);
    }

    private FloorPlan floorPlan(Location location) {
        FloorPlan floorPlan = new FloorPlan();
        floorPlan.setName(TestRecords.unique("P-14 floor plan"));
        floorPlan.setLocation(location);
        return floorPlanRepository.saveAndFlush(floorPlan);
    }

    private Long file(Company company) {
        File file = new File();
        file.setName(TestRecords.unique("P-14 image"));
        file.setPath("p14/" + TestRecords.unique("image"));
        file.setType(FileType.IMAGE);
        file.setCompany(company);
        return fileRepository.saveAndFlush(file).getId();
    }

    // --- queries ---

    private long count(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, Long.class, arguments);
    }

    private long rows(String table, Long id) {
        return count("SELECT count(*) FROM " + table + " WHERE id = ?", id);
    }

    private Long reference(String table, String column, Long id) {
        return jdbc.queryForObject("SELECT " + column + " FROM " + table + " WHERE id = ?", Long.class, id);
    }

    private long auditRecords(CommonData data) {
        return count("SELECT count(*) FROM audit_log WHERE company_id = ?", data.o1().company().getId());
    }

    private void disable(User user) {
        jdbc.update("UPDATE own_user SET enabled = false WHERE id = ?", user.getId());
    }

    private Response call(MockHttpServletRequestBuilder request, User user) {
        return api.call(request, api.login(user));
    }

    private Response send(MockHttpServletRequestBuilder request, String body, User user) {
        return call(request.contentType(MediaType.APPLICATION_JSON).content(body), user);
    }

    private JsonNode json(Response response) {
        try {
            return objectMapper.readTree(response.body());
        } catch (Exception e) {
            throw new AssertionError("not JSON: " + response, e);
        }
    }

    private Long id(Response response) {
        assertTrue(response.is2xx(), response.toString());
        return json(response).get("id").asLong();
    }

    @Nested
    class AssetDeletion {

        private Response deleteAsset(Asset asset, User user) {
            return call(delete("/assets/" + asset.getId()), user);
        }

        /**
         * P-14: asserts 409 ASSET_IN_USE with {@code type}, the asset kept and no audit record.
         */
        private void assertInUse(Response response, Asset asset, CommonData data, String type) {
            assertAll(
                    () -> assertEquals(409, response.status(), response.toString()),
                    () -> assertTrue(response.body().contains("\"code\":\"ASSET_IN_USE\""), response.toString()),
                    () -> assertTrue(response.body().contains("\"" + type + "\""), response.toString()),
                    () -> assertEquals(1, rows("asset", asset.getId())),
                    () -> assertEquals(0, auditRecords(data)));
        }

        @Test
        void childAsset_409_subtreeKept() {
            // before P-14: 200, the child deleted in cascade
            CommonData data = data();
            Asset parent = records.asset(data.o1().company());
            Asset child = records.asset(data.o1().company());
            jdbc.update("UPDATE asset SET parent_asset_id = ? WHERE id = ?", parent.getId(), child.getId());

            Response response = deleteAsset(parent, data.o1().admin());

            assertInUse(response, parent, data, "ASSET");
            assertEquals(1, rows("asset", child.getId()));
        }

        @Test
        void meterWithReading_409_meterAndReadingKept() {
            // before P-14: 200, the meter and its reading deleted in cascade
            CommonData data = data();
            Asset asset = records.asset(data.o1().company());
            Meter meter = meter(data.o1().company(), asset);
            Reading reading = reading(meter, 10);

            Response response = deleteAsset(asset, data.o1().admin());

            assertInUse(response, asset, data, "METER");
            assertAll(
                    () -> assertEquals(1, rows("meter", meter.getId())),
                    () -> assertEquals(1, rows("reading", reading.getId())));
        }

        @Test
        void taskBase_409_kept() {
            // before P-14: 200, the task deleted in cascade
            CommonData data = data();
            Asset asset = records.asset(data.o1().company());
            TaskBase taskBase = taskBase(data.o1().company(), asset, null);

            Response response = deleteAsset(asset, data.o1().admin());

            assertInUse(response, asset, data, "TASK_BASE");
            assertEquals(1, rows("task_base", taskBase.getId()));
        }

        @Test
        void requestPreventiveMaintenanceAndMeterTrigger_409_theyKeepTheAsset() {
            // before P-14: 200, their asset set to null
            CommonData data = data();
            Company company = data.o1().company();
            Asset asset = records.asset(company);
            Request request = request(company);
            PreventiveMaintenance preventiveMaintenance = preventiveMaintenance(company);
            WorkOrderMeterTrigger trigger = trigger(meter(company, records.asset(company)),
                    WorkOrderMeterTriggerCondition.MORE_THAN, 10);
            jdbc.update("UPDATE request SET asset_id = ? WHERE id = ?", asset.getId(), request.getId());
            jdbc.update("UPDATE preventive_maintenance SET asset_id = ? WHERE id = ?", asset.getId(),
                    preventiveMaintenance.getId());
            jdbc.update("UPDATE work_order_meter_trigger SET asset_id = ? WHERE id = ?", asset.getId(),
                    trigger.getId());

            Response response = deleteAsset(asset, data.o1().admin());

            assertInUse(response, asset, data, "REQUEST");
            assertAll(
                    () -> assertTrue(response.body().contains("\"PREVENTIVE_MAINTENANCE\""), response.toString()),
                    () -> assertTrue(response.body().contains("\"WORK_ORDER_METER_TRIGGER\""), response.toString()),
                    () -> assertEquals(asset.getId(), reference("request", "asset_id", request.getId())),
                    () -> assertEquals(asset.getId(), reference("preventive_maintenance", "asset_id",
                            preventiveMaintenance.getId())),
                    () -> assertEquals(asset.getId(), reference("work_order_meter_trigger", "asset_id",
                            trigger.getId())));
        }

        @Test
        void workflowAction_409_workflowKept() {
            // before P-14: 200, the action and the whole workflow deleted in cascade
            CommonData data = data();
            Asset asset = records.asset(data.o1().company());
            Workflow workflow = workflow(data.o1().company(), asset, null, null, null);

            Response response = deleteAsset(asset, data.o1().admin());

            assertInUse(response, asset, data, "WORKFLOW_ACTION");
            assertEquals(1, rows("workflow", workflow.getId()));
        }

        @Test
        void workflowCondition_409_conditionKept() {
            // before P-14: 200, the condition deleted in cascade
            CommonData data = data();
            Asset asset = records.asset(data.o1().company());
            Workflow workflow = workflow(data.o1().company(), records.asset(data.o1().company()), null, asset, null);
            Long condition = workflow.getSecondaryConditions().iterator().next().getId();

            Response response = deleteAsset(asset, data.o1().admin());

            assertInUse(response, asset, data, "WORKFLOW_CONDITION");
            assertEquals(1, rows("workflow_condition", condition));
        }

        @Test
        void portalField_409WithRequestPortalField_fieldKept() {
            // before P-14: 409 ASSET_IN_USE without types
            CommonData data = data();
            Asset asset = records.asset(data.o1().company());
            records.portalCiting(data.o1().company(), null, asset);

            Response response = deleteAsset(asset, data.o1().admin());

            assertInUse(response, asset, data, "REQUEST_PORTAL_FIELD");
        }

        @Test
        void disabledAssignedUser_409WithUser_associationKept() {
            // before P-14: 200, the association deleted
            CommonData data = data();
            Asset asset = records.asset(data.o1().company());
            User technician = data.o1().technician();
            jdbc.update("INSERT INTO t_asset_user_associations (id_asset, id_user) VALUES (?, ?)", asset.getId(),
                    technician.getId());
            disable(technician);

            Response response = deleteAsset(asset, data.o1().admin());

            assertInUse(response, asset, data, "USER");
            assertEquals(1, count("SELECT count(*) FROM t_asset_user_associations WHERE id_asset = ?",
                    asset.getId()));
        }

        @Test
        void withoutDependentsWithDowntime_200_downtimeInTheSummary() {
            // CA-PPB-003 as of today (P-10 already records the deletion)
            CommonData data = data();
            Asset asset = records.asset(data.o1().company());
            AssetDowntime downtime = records.downtime(asset);

            Response response = deleteAsset(asset, data.o1().admin());

            assertAll(
                    () -> assertEquals(200, response.status(), response.toString()),
                    () -> assertEquals(0, rows("asset_downtime", downtime.getId())),
                    () -> assertEquals(List.of("ASSET {\"asset_downtime\": 1}"), jdbc.queryForList(
                            "SELECT entity_type || ' ' || cascade_rows::text FROM audit_log WHERE company_id = ?",
                            String.class, data.o1().company().getId())));
        }

        @Test
        void createdByTechnician_deletedByTechnician_200() {
            // CA-PPB-075 as of today
            CommonData data = data();
            Asset asset = records.asset(data.o1().company());
            jdbc.update("UPDATE asset SET created_by = ? WHERE id = ?", data.o1().technician().getId(),
                    asset.getId());

            Response response = deleteAsset(asset, data.o1().technician());

            assertAll(
                    () -> assertEquals(200, response.status(), response.toString()),
                    () -> assertEquals(0, rows("asset", asset.getId())));
        }

        @Test
        void technicianNotCreator_403_otherOrganization_403_unknown_404() {
            CommonData data = data();
            Asset asset = records.asset(data.o1().company());

            Response technician = deleteAsset(asset, data.o1().technician());
            Response otherOrganization = deleteAsset(asset, data.o2().admin());
            Response unknown = call(delete("/assets/" + Long.MAX_VALUE), data.o1().admin());

            assertAll(
                    () -> assertEquals(403, technician.status(), technician.toString()),
                    () -> assertEquals(403, otherOrganization.status(), otherOrganization.toString()),
                    () -> assertEquals(404, unknown.status(), unknown.toString()),
                    () -> assertEquals(1, rows("asset", asset.getId())));
        }
    }

    @Nested
    class LocationDeletion {

        private Response deleteLocation(Location location, User user) {
            return call(delete("/locations/" + location.getId()), user);
        }

        private void assertInUse(Response response, Location location, CommonData data, String... types) {
            assertAll(
                    () -> assertEquals(409, response.status(), response.toString()),
                    () -> assertTrue(response.body().contains("\"code\":\"LOCATION_IN_USE\""), response.toString()),
                    () -> assertTrue(List.of(types).stream().allMatch(type -> response.body().contains("\"" + type
                            + "\"")), response.toString()),
                    () -> assertEquals(1, rows("location", location.getId())),
                    () -> assertEquals(0, auditRecords(data)));
        }

        @Test
        void childLocationAndFloorPlan_409_subtreeAndFloorPlanKept() {
            // before P-14: 200, the child and the floor plan deleted in cascade, no audit record
            CommonData data = data();
            Location parent = records.location(data.o1().company());
            Location child = records.location(data.o1().company());
            jdbc.update("UPDATE location SET parent_location_id = ? WHERE id = ?", parent.getId(), child.getId());
            FloorPlan floorPlan = floorPlan(parent);

            Response response = deleteLocation(parent, data.o1().admin());

            assertInUse(response, parent, data, "LOCATION");
            assertAll(
                    () -> assertEquals(1, rows("location", child.getId())),
                    () -> assertEquals(1, rows("floor_plan", floorPlan.getId())));
        }

        @Test
        void referencingRecords_409_theyKeepTheLocation() {
            // before P-14: 200, their location set to null, the revision included
            CommonData data = data();
            Company company = data.o1().company();
            Location location = records.location(company);
            Asset asset = records.asset(company);
            Meter meter = meter(company, records.asset(company));
            Request request = request(company);
            PreventiveMaintenance preventiveMaintenance = preventiveMaintenance(company);
            WorkOrder workOrder = records.workOrder(company, null, Status.COMPLETE, false);
            WorkOrderMeterTrigger trigger = trigger(meter, WorkOrderMeterTriggerCondition.MORE_THAN, 10);
            User technician = data.o1().technician();
            for (String table : List.of("asset", "meter", "request", "preventive_maintenance", "work_order",
                    "work_order_meter_trigger", "own_user")) {
                Long id = switch (table) {
                    case "asset" -> asset.getId();
                    case "meter" -> meter.getId();
                    case "request" -> request.getId();
                    case "preventive_maintenance" -> preventiveMaintenance.getId();
                    case "work_order" -> workOrder.getId();
                    case "work_order_meter_trigger" -> trigger.getId();
                    default -> technician.getId();
                };
                jdbc.update("UPDATE " + table + " SET location_id = ? WHERE id = ?", location.getId(), id);
            }
            // the revision that Envers wrote when the work order was created
            jdbc.update("UPDATE work_order_aud SET location_id = ? WHERE id = ?", location.getId(),
                    workOrder.getId());

            Response response = deleteLocation(location, data.o1().admin());

            assertInUse(response, location, data, "ASSET", "METER", "REQUEST", "PREVENTIVE_MAINTENANCE",
                    "WORK_ORDER", "WORK_ORDER_METER_TRIGGER", "USER");
            assertAll(
                    () -> assertEquals(location.getId(), reference("asset", "location_id", asset.getId())),
                    () -> assertEquals(location.getId(), reference("meter", "location_id", meter.getId())),
                    () -> assertEquals(location.getId(), reference("request", "location_id", request.getId())),
                    () -> assertEquals(location.getId(), reference("preventive_maintenance", "location_id",
                            preventiveMaintenance.getId())),
                    () -> assertEquals(location.getId(), reference("work_order", "location_id", workOrder.getId())),
                    () -> assertEquals(location.getId(), reference("work_order_meter_trigger", "location_id",
                            trigger.getId())),
                    () -> assertEquals(location.getId(), reference("own_user", "location_id", technician.getId())),
                    () -> assertEquals(1, count("SELECT count(*) FROM work_order_aud WHERE id = ? "
                            + "AND location_id = ?", workOrder.getId(), location.getId())));
        }

        @Test
        void workflowAction_409_workflowKept() {
            // before P-14: 200, the whole workflow deleted in cascade
            CommonData data = data();
            Location location = records.location(data.o1().company());
            Workflow workflow = workflow(data.o1().company(), null, location, null, null);

            Response response = deleteLocation(location, data.o1().admin());

            assertInUse(response, location, data, "WORKFLOW_ACTION");
            assertEquals(1, rows("workflow", workflow.getId()));
        }

        @Test
        void portalField_409LocationInUseWithRequestPortalField_fieldKept() {
            // before P-14: 409 RECORD_IN_USE
            CommonData data = data();
            Location location = records.location(data.o1().company());
            records.portalCiting(data.o1().company(), location, null);

            Response response = deleteLocation(location, data.o1().admin());

            assertInUse(response, location, data, "REQUEST_PORTAL_FIELD");
        }

        @Test
        void technicianNotCreator_403_otherOrganization_403_unknown_404() {
            CommonData data = data();
            Location location = records.location(data.o1().company());

            Response technician = deleteLocation(location, data.o1().technician());
            Response otherOrganization = deleteLocation(location, data.o2().admin());
            Response unknown = call(delete("/locations/" + Long.MAX_VALUE), data.o1().admin());

            assertAll(
                    () -> assertEquals(403, technician.status(), technician.toString()),
                    () -> assertEquals(403, otherOrganization.status(), otherOrganization.toString()),
                    () -> assertEquals(404, unknown.status(), unknown.toString()),
                    () -> assertEquals(1, rows("location", location.getId())));
        }
    }

    @Nested
    class MeterDeletion {

        @Test
        void readingTriggerAndTaskBase_409_allKept_noAuditRecord() {
            // before P-14: 200, the reading, the trigger and the task deleted in cascade, no audit record
            CommonData data = data();
            Meter meter = meter(data.o1().company(), records.asset(data.o1().company()));
            Reading reading = reading(meter, 10);
            WorkOrderMeterTrigger trigger = trigger(meter, WorkOrderMeterTriggerCondition.MORE_THAN, 10);
            TaskBase taskBase = taskBase(data.o1().company(), null, meter);

            Response response = call(delete("/meters/" + meter.getId()), data.o1().admin());

            assertAll(
                    () -> assertEquals(409, response.status(), response.toString()),
                    () -> assertTrue(response.body().contains("\"code\":\"METER_IN_USE\""), response.toString()),
                    () -> assertTrue(response.body().contains("\"READING\""), response.toString()),
                    () -> assertTrue(response.body().contains("\"WORK_ORDER_METER_TRIGGER\""), response.toString()),
                    () -> assertTrue(response.body().contains("\"TASK_BASE\""), response.toString()),
                    () -> assertEquals(1, rows("reading", reading.getId())),
                    () -> assertEquals(1, rows("work_order_meter_trigger", trigger.getId())),
                    () -> assertEquals(1, rows("task_base", taskBase.getId())),
                    () -> assertEquals(0, auditRecords(data)));
        }

        @Test
        void technicianNotCreator_403_otherOrganization_403_unknown_404() {
            CommonData data = data();
            Meter meter = meter(data.o1().company(), records.asset(data.o1().company()));

            Response technician = call(delete("/meters/" + meter.getId()), data.o1().technician());
            Response otherOrganization = call(delete("/meters/" + meter.getId()), data.o2().admin());
            Response unknown = call(delete("/meters/" + Long.MAX_VALUE), data.o1().admin());

            assertAll(
                    () -> assertEquals(403, technician.status(), technician.toString()),
                    () -> assertEquals(403, otherOrganization.status(), otherOrganization.toString()),
                    () -> assertEquals(404, unknown.status(), unknown.toString()),
                    () -> assertEquals(1, rows("meter", meter.getId())));
        }
    }

    @Nested
    class CategoryDeletion {

        @Test
        void assetCategoryInUse_200_assetCategorySetToNull_noAuditRecord() {
            CommonData data = data();
            AssetCategory category = assetCategoryRepository.saveAndFlush(new AssetCategory(
                    TestRecords.unique("P-14 asset category"), data.o1().company().getCompanySettings()));
            Asset asset = records.asset(data.o1().company());
            jdbc.update("UPDATE asset SET category_id = ? WHERE id = ?", category.getId(), asset.getId());

            Response response = call(delete("/asset-categories/" + category.getId()), data.o1().admin());

            assertAll(
                    () -> assertEquals(200, response.status(), response.toString()),
                    () -> assertNull(reference("asset", "category_id", asset.getId())),
                    () -> assertEquals(0, auditRecords(data)));
        }

        @Test
        void meterCategoryInUse_200_meterCategorySetToNull_noAuditRecord() {
            CommonData data = data();
            MeterCategory category = meterCategoryRepository.saveAndFlush(new MeterCategory(
                    TestRecords.unique("P-14 meter category"), data.o1().company().getCompanySettings()));
            Meter meter = meter(data.o1().company(), records.asset(data.o1().company()));
            jdbc.update("UPDATE meter SET meter_category_id = ? WHERE id = ?", category.getId(), meter.getId());

            Response response = call(delete("/meter-categories/" + category.getId()), data.o1().admin());

            assertAll(
                    () -> assertEquals(200, response.status(), response.toString()),
                    () -> assertNull(reference("meter", "meter_category_id", meter.getId())),
                    () -> assertEquals(0, auditRecords(data)));
        }
    }

    @Nested
    class Readings {

        @Test
        void patch_409HistoryImmutable_valueKept_noTriggerEvaluated() {
            // before P-14: 200, the value changed and the trigger created a work order
            CommonData data = data();
            Meter meter = meter(data.o1().company(), records.asset(data.o1().company()));
            Reading reading = reading(meter, 100);
            trigger(meter, WorkOrderMeterTriggerCondition.LESS_THAN, 99);
            long workOrders = count("SELECT count(*) FROM work_order WHERE company_id = ?",
                    data.o1().company().getId());

            Response response = send(patch("/readings/" + reading.getId()), "{\"value\":98}", data.o1().admin());

            assertAll(
                    () -> assertEquals(409, response.status(), response.toString()),
                    () -> assertTrue(response.body().contains("HISTORY_IMMUTABLE"), response.toString()),
                    () -> assertEquals(100.0, jdbc.queryForObject("SELECT value FROM reading WHERE id = ?",
                            Double.class, reading.getId())),
                    () -> assertEquals(workOrders, count("SELECT count(*) FROM work_order WHERE company_id = ?",
                            data.o1().company().getId())));
        }

        @Test
        void delete_409HistoryImmutable_kept() {
            // before P-14: 200, the reading deleted
            CommonData data = data();
            Meter meter = meter(data.o1().company(), records.asset(data.o1().company()));
            Reading reading = reading(meter, 100);

            Response response = call(delete("/readings/" + reading.getId()), data.o1().admin());

            assertAll(
                    () -> assertEquals(409, response.status(), response.toString()),
                    () -> assertTrue(response.body().contains("HISTORY_IMMUTABLE"), response.toString()),
                    () -> assertEquals(1, rows("reading", reading.getId())));
        }

        @Test
        void newReading_200_evaluatedAgainstTheTriggers() {
            // CA-PPB-013 as of today
            CommonData data = data();
            Meter meter = meter(data.o1().company(), records.asset(data.o1().company()));
            trigger(meter, WorkOrderMeterTriggerCondition.LESS_THAN, 99);
            long workOrders = count("SELECT count(*) FROM work_order WHERE company_id = ?",
                    data.o1().company().getId());

            Response response = send(post("/readings"), "{\"value\":98,\"meter\":{\"id\":" + meter.getId() + "}}",
                    data.o1().admin());

            assertAll(
                    () -> assertEquals(200, response.status(), response.toString()),
                    () -> assertEquals(workOrders + 1, count("SELECT count(*) FROM work_order WHERE company_id = ?",
                            data.o1().company().getId())));
        }

        @Test
        void otherOrganization_patchAndDelete_403_kept() {
            // before P-14: 200 on both, the reading of O1 changed and deleted from O2 (D-P14-6)
            CommonData data = data();
            Meter meter = meter(data.o1().company(), records.asset(data.o1().company()));
            Reading reading = reading(meter, 100);

            Response patchOther = send(patch("/readings/" + reading.getId()), "{\"value\":1}", data.o2().admin());
            Response deleteOther = call(delete("/readings/" + reading.getId()), data.o2().admin());

            assertAll(
                    () -> assertEquals(403, patchOther.status(), patchOther.toString()),
                    () -> assertEquals(403, deleteOther.status(), deleteOther.toString()),
                    () -> assertEquals(100.0, jdbc.queryForObject("SELECT value FROM reading WHERE id = ?",
                            Double.class, reading.getId())));
        }

        @Test
        void otherOrganization_create_403_unknown_404() {
            CommonData data = data();
            Meter meter = meter(data.o1().company(), records.asset(data.o1().company()));

            Response create = send(post("/readings"), "{\"value\":5,\"meter\":{\"id\":" + meter.getId() + "}}",
                    data.o2().admin());
            Response patchUnknown = send(patch("/readings/" + Long.MAX_VALUE), "{\"value\":1}", data.o1().admin());
            Response deleteUnknown = call(delete("/readings/" + Long.MAX_VALUE), data.o1().admin());

            assertAll(
                    () -> assertEquals(403, create.status(), create.toString()),
                    () -> assertEquals(404, patchUnknown.status(), patchUnknown.toString()),
                    () -> assertEquals(404, deleteUnknown.status(), deleteUnknown.toString()),
                    () -> assertEquals(0, count("SELECT count(*) FROM reading WHERE meter_id = ?", meter.getId())));
        }
    }

    @Nested
    class Children {

        @Test
        void meterTrigger_200_deletedWithItsAssignees_noAuditRecord() {
            CommonData data = data();
            WorkOrderMeterTrigger trigger = trigger(meter(data.o1().company(),
                    records.asset(data.o1().company())), WorkOrderMeterTriggerCondition.MORE_THAN, 10);
            jdbc.update("INSERT INTO work_order_meter_trigger_assigned_to (work_order_meter_trigger_id, "
                    + "assigned_to_id) VALUES (?, ?)", trigger.getId(), data.o1().technician().getId());

            Response response = call(delete("/work-order-meter-triggers/" + trigger.getId()), data.o1().admin());

            assertAll(
                    () -> assertEquals(200, response.status(), response.toString()),
                    () -> assertEquals(0, rows("work_order_meter_trigger", trigger.getId())),
                    () -> assertEquals(0, count("SELECT count(*) FROM work_order_meter_trigger_assigned_to "
                            + "WHERE work_order_meter_trigger_id = ?", trigger.getId())),
                    () -> assertEquals(1, rows("own_user", data.o1().technician().getId())),
                    () -> assertEquals(0, auditRecords(data)));
        }

        @Test
        void floorPlanWithImage_200_imageKept_noAuditRecord() {
            CommonData data = data();
            FloorPlan floorPlan = floorPlan(records.location(data.o1().company()));
            Long image = file(data.o1().company());
            jdbc.update("UPDATE floor_plan SET image_id = ? WHERE id = ?", image, floorPlan.getId());

            Response response = call(delete("/floor-plans/" + floorPlan.getId()), data.o1().admin());

            assertAll(
                    () -> assertEquals(200, response.status(), response.toString()),
                    () -> assertEquals(0, rows("floor_plan", floorPlan.getId())),
                    () -> assertEquals(1, rows("file", image)),
                    () -> assertEquals(0, auditRecords(data)));
        }

        @Test
        void downtime_200_deleted_noAuditRecord() {
            CommonData data = data();
            AssetDowntime downtime = records.downtime(records.asset(data.o1().company()));

            Response response = call(delete("/asset-downtimes/" + downtime.getId()), data.o1().admin());

            assertAll(
                    () -> assertEquals(200, response.status(), response.toString()),
                    () -> assertEquals(0, rows("asset_downtime", downtime.getId())),
                    () -> assertEquals(0, auditRecords(data)));
        }

        @Test
        void deprecation_200_deleted_assetPointerSetToNull_noAuditRecord() {
            CommonData data = data();
            Asset asset = records.asset(data.o1().company());
            Deprecation deprecation = new Deprecation();
            deprecation.setCompany(data.o1().company());
            deprecation = deprecationRepository.saveAndFlush(deprecation);
            jdbc.update("UPDATE asset SET deprecation_id = ? WHERE id = ?", deprecation.getId(), asset.getId());
            Long id = deprecation.getId();

            Response response = call(delete("/deprecations/" + id), data.o1().admin());

            assertAll(
                    () -> assertEquals(200, response.status(), response.toString()),
                    () -> assertEquals(0, rows("deprecation", id)),
                    () -> assertNull(reference("asset", "deprecation_id", asset.getId())),
                    () -> assertEquals(0, auditRecords(data)));
        }

        @Test
        void otherOrganization_403_forEveryChild() {
            CommonData data = data();
            Company company = data.o1().company();
            Asset asset = records.asset(company);
            WorkOrderMeterTrigger trigger = trigger(meter(company, asset), WorkOrderMeterTriggerCondition.MORE_THAN,
                    10);
            FloorPlan floorPlan = floorPlan(records.location(company));
            AssetDowntime downtime = records.downtime(asset);
            Deprecation deprecation = new Deprecation();
            deprecation.setCompany(company);
            Long deprecationId = deprecationRepository.saveAndFlush(deprecation).getId();
            User other = data.o2().admin();

            List<Integer> statuses = List.of(
                    call(delete("/work-order-meter-triggers/" + trigger.getId()), other).status(),
                    call(delete("/floor-plans/" + floorPlan.getId()), other).status(),
                    call(delete("/asset-downtimes/" + downtime.getId()), other).status(),
                    call(delete("/deprecations/" + deprecationId), other).status());

            assertAll(
                    () -> assertEquals(List.of(403, 403, 403, 403), statuses),
                    () -> assertEquals(1, rows("work_order_meter_trigger", trigger.getId())),
                    () -> assertEquals(1, rows("floor_plan", floorPlan.getId())),
                    () -> assertEquals(1, rows("asset_downtime", downtime.getId())),
                    () -> assertEquals(1, rows("deprecation", deprecationId)));
        }
    }

    @Nested
    class DisabledUser {

        @Test
        void asPrimaryUserOfANewAsset_200() {
            CommonData data = data();
            User technician = data.o1().technician();
            disable(technician);

            Response response = send(post("/assets"), "{\"name\":\"" + TestRecords.unique("P-14 asset")
                    + "\",\"primaryUser\":{\"id\":" + technician.getId() + "}}", data.o1().admin());

            assertEquals(technician.getId(), reference("asset", "primary_user_id", id(response)));
        }

        @Test
        void assignedToAnEditedAsset_200() {
            CommonData data = data();
            Asset asset = records.asset(data.o1().company());
            User technician = data.o1().technician();
            disable(technician);

            Response response = send(patch("/assets/" + asset.getId()), "{\"name\":\"" + asset.getName()
                    + "\",\"status\":\"OPERATIONAL\",\"assignedTo\":[{\"id\":" + technician.getId() + "}]}",
                    data.o1().admin());

            assertAll(
                    () -> assertEquals(200, response.status(), response.toString()),
                    () -> assertEquals(1, count("SELECT count(*) FROM t_asset_user_associations WHERE id_asset = ? "
                            + "AND id_user = ?", asset.getId(), technician.getId())));
        }

        @Test
        void asWorkerOfANewLocation_200() {
            CommonData data = data();
            User technician = data.o1().technician();
            disable(technician);

            Response response = send(post("/locations"), "{\"name\":\"" + TestRecords.unique("P-14 location")
                    + "\",\"workers\":[{\"id\":" + technician.getId() + "}]}", data.o1().admin());

            assertEquals(1, count("SELECT count(*) FROM t_location_user_associations WHERE id_location = ? "
                    + "AND id_user = ?", id(response), technician.getId()));
        }

        @Test
        void asUserOfANewMeter_200() {
            CommonData data = data();
            User technician = data.o1().technician();
            disable(technician);
            Asset asset = records.asset(data.o1().company());

            Response response = send(post("/meters"), "{\"name\":\"" + TestRecords.unique("P-14 meter")
                    + "\",\"updateFrequency\":1,\"asset\":{\"id\":" + asset.getId() + "},\"users\":[{\"id\":"
                    + technician.getId() + "}]}", data.o1().admin());

            assertEquals(1, count("SELECT count(*) FROM t_meter_user_associations WHERE id_meter = ? "
                    + "AND id_user = ?", id(response), technician.getId()));
        }

        @Test
        void asPrimaryUserOfANewMeterTrigger_200() {
            CommonData data = data();
            User technician = data.o1().technician();
            disable(technician);
            Meter meter = meter(data.o1().company(), records.asset(data.o1().company()));

            Response response = send(post("/work-order-meter-triggers"), "{\"name\":\""
                    + TestRecords.unique("P-14 trigger") + "\",\"title\":\"P-14\",\"triggerCondition\":\"MORE_THAN\","
                    + "\"value\":10,\"waitBefore\":0,\"meter\":{\"id\":" + meter.getId() + "},\"primaryUser\":{\"id\":"
                    + technician.getId() + "}}", data.o1().admin());

            assertEquals(technician.getId(), reference("work_order_meter_trigger", "primary_user_id",
                    id(response)));
        }
    }

    @Nested
    class DemoPurge {

        @Test
        void demoHierarchies_200_allDeleted() {
            // the hierarchies keep working with the protected references: Hibernate unlinks the child first
            CommonData data = data();
            Company company = data.o1().company();
            Location parentLocation = records.location(company);
            Location childLocation = records.location(company);
            Asset parentAsset = records.asset(company, null, true);
            Asset childAsset = records.asset(company, null, true);
            Meter meter = meter(company, parentAsset);
            jdbc.update("UPDATE location SET is_demo = true WHERE id IN (?, ?)", parentLocation.getId(),
                    childLocation.getId());
            jdbc.update("UPDATE location SET parent_location_id = ? WHERE id = ?", parentLocation.getId(),
                    childLocation.getId());
            jdbc.update("UPDATE asset SET parent_asset_id = ?, location_id = ? WHERE id = ?", parentAsset.getId(),
                    childLocation.getId(), childAsset.getId());
            jdbc.update("UPDATE meter SET is_demo = true WHERE id = ?", meter.getId());

            Response response = call(delete("/demo/demo-data"), data.o1().admin());

            assertAll(
                    () -> assertEquals(200, response.status(), response.toString()),
                    () -> assertEquals(0, count("SELECT count(*) FROM location WHERE id IN (?, ?)",
                            parentLocation.getId(), childLocation.getId())),
                    () -> assertEquals(0, count("SELECT count(*) FROM asset WHERE id IN (?, ?)",
                            parentAsset.getId(), childAsset.getId())),
                    () -> assertEquals(0, rows("meter", meter.getId())));
        }

        @Test
        void demoMeterWithAReading_409RecordInUse_nothingDeleted() {
            // before P-14: 200, the reading deleted with its meter. D-P14-9: the reading is not demo, so the whole
            // purge is rejected (PPB-20 d); P-18 gives the code DEMO_DATA_IN_USE
            CommonData data = data();
            Company company = data.o1().company();
            Asset asset = records.asset(company, null, true);
            Meter meter = meter(company, asset);
            Reading reading = reading(meter, 10);
            jdbc.update("UPDATE meter SET is_demo = true WHERE id = ?", meter.getId());

            Response response = call(delete("/demo/demo-data"), data.o1().admin());

            assertAll(
                    () -> assertEquals(409, response.status(), response.toString()),
                    () -> assertTrue(response.body().contains("RECORD_IN_USE"), response.toString()),
                    () -> assertEquals(1, rows("asset", asset.getId())),
                    () -> assertEquals(1, rows("meter", meter.getId())),
                    () -> assertEquals(1, rows("reading", reading.getId())));
        }
    }

    @Nested
    class Schema {

        @Test
        void foreignKeysToAssetLocationMeterAndTheirCategories() {
            // child table.column -> parent, constraint, ON DELETE (a NO ACTION, c CASCADE, n SET NULL), indexed
            List<String> keys = jdbc.queryForList("""
                    SELECT c.conrelid::regclass::text || '.' || a.attname || ' -> ' || c.confrelid::regclass::text
                           || ' ' || c.conname || ' ' || c.confdeltype::text || ' '
                           || (EXISTS (SELECT 1 FROM pg_index i WHERE i.indrelid = c.conrelid
                               AND i.indkey[0] = c.conkey[1] AND i.indisvalid AND i.indpred IS NULL))::text
                    FROM pg_constraint c
                    JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = c.conkey[1]
                    WHERE c.contype = 'f' AND c.confrelid IN ('asset'::regclass, 'location'::regclass,
                        'meter'::regclass, 'asset_category'::regclass, 'meter_category'::regclass,
                        'work_order_meter_trigger'::regclass)
                    ORDER BY 1""", String.class);

            assertEquals(List.of(
                    "asset.category_id -> asset_category fkhw44q3ymkd329b8b6mvqxatsh n false",
                    "asset.location_id -> location fkoo11h2f4j12wv0axk6d8u1wy0 a true",
                    "asset.parent_asset_id -> asset fk6qu6wqhpppa2w4y09l3tt4q56 a true",
                    "asset_downtime.asset_id -> asset fkrbg55bho0cxaorbbt7jwskrpo c false",
                    "custom_field_value.asset_id -> asset fk_custom_field_value_asset c true",
                    "custom_field_value.location_id -> location fk_custom_field_value_location c true",
                    "custom_field_value.meter_id -> meter fk_custom_field_value_meter c true",
                    "custom_field_value.work_order_meter_trigger_id -> work_order_meter_trigger fk_custom_field_value_work_order_meter_trigger a true",
                    "floor_plan.location_id -> location fkidx35wj5iwhfi5xrcs9jdsr2d c false",
                    "location.parent_location_id -> location fk9s1sco601u8l6ptdxf432h4kb a true",
                    "meter.asset_id -> asset fkswqod52v3556tp6obl9c8lvxi a true",
                    "meter.location_id -> location fk8jgoj4oi6b9sjqeuh9v592a6q a true",
                    "meter.meter_category_id -> meter_category fkoid61jgpxbr04np7rop8pag13 n false",
                    "own_user.location_id -> location fktmprdfwe8axkp8cobywe2gc1d a true",
                    "preventive_maintenance.asset_id -> asset fk2r1291umxjxrddelq96v3pl4n a true",
                    "preventive_maintenance.location_id -> location fk53hdmxmgd3fjb2fjsh43d1irf a true",
                    "reading.meter_id -> meter fkrk4bo4tq59pfymwvx2jnjvlae a true",
                    "request.asset_id -> asset fkml2rlcjiurmq5rjyw1gmsstf6 a true",
                    "request.location_id -> location fkj7y0rwrqngdydkkdcohwisqq6 a true",
                    "request_portal_field.asset_id -> asset fk_request_portal_field_asset a false",
                    "request_portal_field.location_id -> location fk_request_portal_field_location a false",
                    "t_asset_customer_associations.id_asset -> asset fkil356y7ax7m8tqfryd4p8pe6o c true",
                    "t_asset_file_associations.id_asset -> asset fk20hrlr3c09srq7yt5ibdal2pv c true",
                    "t_asset_part_associations.id_asset -> asset fklok6yse9itekmdxni6i1f58ys c true",
                    "t_asset_team_associations.id_asset -> asset fkqr34hfl16wsv4krhpdcatpeft c true",
                    "t_asset_user_associations.id_asset -> asset fktelhjpyo61q5q6ex771eug6y c true",
                    "t_asset_vendor_associations.id_asset -> asset fkpgtou9e97sr7cmfyxfp1hp9sb c true",
                    "t_location_customer_associations.id_location -> location fkrohs7o68xw77yvwvlo0prue70 c true",
                    "t_location_file_associations.id_location -> location fkpxrshqnllftrn323eubyaqqhq c true",
                    "t_location_team_associations.id_location -> location fkljl6bq0u1fc9qgyj3ry1pfu1n c true",
                    "t_location_user_associations.id_location -> location fk2vp8mot9fhshwsh8flivg2e61 c true",
                    "t_location_vendor_associations.id_location -> location fkb6alk3ojlofcs76q4hnce764b c true",
                    "t_meter_user_associations.id_meter -> meter fk8ikpoqiwcc3i40wdfjl2dynv7 c true",
                    "task_base.asset_id -> asset fkfcyjas01bhns54mn9uxk3xqx8 a true",
                    "task_base.meter_id -> meter fkgtij68ko3kykqk1od01ahoudg a true",
                    "work_order.asset_id -> asset fkp4hbmaa9hcp6myymsfyxg6fo2 a true",
                    "work_order.location_id -> location fkdbtdl51i4yup1hs6ek3ecqros a true",
                    "work_order_aud.location_id -> location FK_work_order_AUD_location_id a true",
                    "work_order_meter_trigger.asset_id -> asset fkdkxi8xmqmo48y8xtml1860nbc a true",
                    "work_order_meter_trigger.location_id -> location fkmdb3avajahyo8mc6wi38ls8xi a true",
                    "work_order_meter_trigger.meter_id -> meter fk87doqr843tj4vmxarqujm3sgy c false",
                    "work_order_meter_trigger_assigned_to.work_order_meter_trigger_id -> work_order_meter_trigger fk42orqs7y05ghgkis54tathjv c false",
                    "work_order_meter_trigger_customers.work_order_meter_trigger_id -> work_order_meter_trigger fkkvfp8se58k6wyyt8ctgv4r3f0 c false",
                    "work_order_meter_trigger_files.work_order_meter_trigger_id -> work_order_meter_trigger fkol2wwydmtktqbnc7lvv23r5jy c false",
                    "workflow_action.asset_id -> asset fkufu41twy3j9sfswo2tm7nkue a true",
                    "workflow_action.location_id -> location fk6nkenbmcv4vyxw23kwm4ftgaq a true",
                    "workflow_condition.asset_id -> asset fkhsrmx7f4fbfh4g1jdo5vray90 a true",
                    "workflow_condition.location_id -> location fkoueqwngifye55uqf2pj5u0257 a true"), keys);
        }

        @Test
        void foreignKeysOfThePackageTablesThatDeleteThemInCascade() {
            List<String> keys = jdbc.queryForList("""
                    SELECT c.conrelid::regclass::text || '.' || a.attname || ' -> ' || c.confrelid::regclass::text
                    FROM pg_constraint c
                    JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = c.conkey[1]
                    WHERE c.contype = 'f' AND c.confdeltype = 'c'
                      AND c.conrelid IN ('asset'::regclass, 'location'::regclass, 'meter'::regclass,
                        'reading'::regclass, 'asset_category'::regclass, 'meter_category'::regclass,
                        'floor_plan'::regclass, 'deprecation'::regclass, 'asset_downtime'::regclass,
                        'work_order_meter_trigger'::regclass)
                    ORDER BY 1""", String.class);

            assertEquals(List.of(
                    "asset.company_id -> company",
                    "asset_category.company_settings_id -> company_settings",
                    "asset_downtime.asset_id -> asset",
                    "asset_downtime.company_id -> company",
                    "deprecation.company_id -> company",
                    "floor_plan.location_id -> location",
                    "location.company_id -> company",
                    "meter.company_id -> company",
                    "meter_category.company_settings_id -> company_settings",
                    "work_order_meter_trigger.company_id -> company",
                    "work_order_meter_trigger.meter_id -> meter"), keys);
        }

        @Test
        void workOrderRevisionsCiteTheAssetWithoutAForeignKey() {
            assertEquals(1, count("SELECT count(*) FROM information_schema.columns WHERE table_schema = "
                    + "current_schema() AND table_name = 'work_order_aud' AND column_name = 'asset_id'"));
            assertEquals(0, count("SELECT count(*) FROM pg_constraint c JOIN pg_attribute a ON a.attrelid = "
                    + "c.conrelid AND a.attnum = c.conkey[1] WHERE c.contype = 'f' "
                    + "AND c.conrelid = 'work_order_aud'::regclass AND a.attname = 'asset_id'"));
        }
    }
}
