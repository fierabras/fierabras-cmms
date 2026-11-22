package com.grash.integration;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grash.integration.support.FaultInjector;
import com.grash.integration.support.FaultInjector.Fault;
import com.grash.integration.support.FaultInjector.Operation;
import com.grash.integration.support.TestApi;
import com.grash.integration.support.TestApi.Response;
import com.grash.integration.support.TestOrganizations;
import com.grash.integration.support.TestOrganizations.CommonData;
import com.grash.integration.support.TestRecords;
import com.grash.integration.support.TestWorkOrders;
import com.grash.integration.support.TestWorkOrders.Fixture;
import com.grash.model.FloorPlan;
import com.grash.model.Meter;
import com.grash.model.PreventiveMaintenance;
import com.grash.model.Request;
import com.grash.model.WorkOrder;
import com.grash.model.WorkOrderMeterTrigger;
import com.grash.model.enums.Status;
import com.grash.model.enums.WorkOrderMeterTriggerCondition;
import com.grash.repository.FileRepository;
import com.grash.repository.FloorPlanRepository;
import com.grash.repository.MeterRepository;
import com.grash.repository.PartRepository;
import com.grash.repository.PreventiveMaintenanceRepository;
import com.grash.repository.RequestRepository;
import com.grash.repository.WorkOrderMeterTriggerRepository;
import com.grash.repository.WorkOrderRepository;
import com.grash.service.FileService;
import com.grash.utils.AfterCommit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

/**
 * P-17 (PPB-18, PPB-19, PPB-25): a file in use is not deleted (CA-PPB-049, 050), and the stored objects of a deleted
 * file are deleted only after the commit, whatever the route (CA-PPB-051 to 053).
 */
@AutoConfigureMockMvc
class FileDeletionIntegrationTest extends AbstractIntegrationTest {

    private static final String DELETED_BODY = "{\"success\":true,\"message\":\"Deleted successfully\"}";
    private static final String FILE_IN_USE = "FILE_IN_USE";
    private static final String FILE_IN_USE_MESSAGE =
            "This file is used by other records and cannot be deleted. Remove it from those records first";

    /**
     * The 13 references to file that P-17 turns into NO ACTION, with their index.
     */
    private static final List<String[]> REFERENCES = List.of(
            new String[]{"asset", "image_id"}, new String[]{"company", "logo_id"},
            new String[]{"floor_plan", "image_id"}, new String[]{"location", "image_id"},
            new String[]{"meter", "image_id"}, new String[]{"own_user", "image_id"},
            new String[]{"part", "image_id"}, new String[]{"preventive_maintenance", "image_id"},
            new String[]{"request", "image_id"}, new String[]{"request", "audio_description_id"},
            new String[]{"work_order", "image_id"}, new String[]{"work_order_aud", "image_id"},
            new String[]{"work_order_meter_trigger", "image_id"});

    /**
     * The 10 association tables to file, which keep CASCADE.
     */
    private static final List<String[]> ASSOCIATIONS = List.of(
            new String[]{"comment_files", "files_id"}, new String[]{"preventive_maintenance_files", "files_id"},
            new String[]{"request_files", "files_id"}, new String[]{"t_asset_file_associations", "id_file"},
            new String[]{"t_location_file_associations", "id_file"},
            new String[]{"t_part_file_associations", "id_file"},
            new String[]{"t_request_file_associations", "id_file"},
            new String[]{"t_work_order_file_associations", "id_file"},
            new String[]{"work_order_files", "files_id"}, new String[]{"work_order_meter_trigger_files", "files_id"});

    private final TestOrganizations organizations;
    private final TestRecords records;
    private final TestApi api;
    private final TestWorkOrders workOrders;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;
    private final FaultInjector faults;
    private final WorkOrderRepository workOrderRepository;
    private final RequestRepository requestRepository;
    private final PreventiveMaintenanceRepository preventiveMaintenanceRepository;
    private final MeterRepository meterRepository;
    private final WorkOrderMeterTriggerRepository meterTriggerRepository;
    private final FloorPlanRepository floorPlanRepository;
    private final FileRepository fileRepository;
    private final FileService fileService;
    private final TransactionTemplate transaction;
    private final List<CommonData> created = new ArrayList<>();

    @Autowired
    FileDeletionIntegrationTest(TestOrganizations organizations, TestRecords records, MockMvc mockMvc,
                                ObjectMapper objectMapper, JdbcTemplate jdbc,
                                WorkOrderRepository workOrderRepository, PartRepository partRepository,
                                FileRepository fileRepository, RequestRepository requestRepository,
                                PreventiveMaintenanceRepository preventiveMaintenanceRepository,
                                MeterRepository meterRepository,
                                WorkOrderMeterTriggerRepository meterTriggerRepository,
                                FloorPlanRepository floorPlanRepository, FileService fileService,
                                TransactionTemplate transaction) {
        this.organizations = organizations;
        this.records = records;
        this.api = new TestApi(mockMvc, objectMapper);
        this.workOrders = new TestWorkOrders(api, objectMapper, records, workOrderRepository, partRepository,
                fileRepository);
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
        this.faults = new FaultInjector(jdbc);
        this.workOrderRepository = workOrderRepository;
        this.requestRepository = requestRepository;
        this.preventiveMaintenanceRepository = preventiveMaintenanceRepository;
        this.meterRepository = meterRepository;
        this.meterTriggerRepository = meterTriggerRepository;
        this.floorPlanRepository = floorPlanRepository;
        this.fileRepository = fileRepository;
        this.fileService = fileService;
        this.transaction = transaction;
    }

    @AfterEach
    void cleanUp() {
        faults.removeAll();
        created.forEach(organizations::delete);
        created.clear();
    }

    // ----------------------------------------------------------------------------------------------- helpers

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

    /**
     * Uploads one file through the API, as the web app does, optionally as the image of a task; returns its id.
     */
    private Long upload(String token, Long taskId) {
        MockMultipartFile file = new MockMultipartFile("files", TestRecords.unique("p17") + ".txt",
                MediaType.TEXT_PLAIN_VALUE, "P-17".getBytes(StandardCharsets.UTF_8));
        MockMultipartHttpServletRequestBuilder request = multipart("/files/upload").file(file);
        request.param("folder", "p17").param("hidden", "false").param("type", "IMAGE");
        if (taskId != null) request.param("taskId", taskId.toString());
        Response response = api.call(request, token);
        assertTrue(response.is2xx(), "upload: " + response);
        return json(response).get(0).get("id").asLong();
    }

    /**
     * Gives the file a thumbnail stored in the double, as the thumbnail generation does; returns its path.
     */
    private String withThumbnail(Long fileId) {
        String thumbnail = storageDouble.upload("thumb".getBytes(StandardCharsets.UTF_8), "p17_thumb.jpg", "p17");
        jdbc.update("UPDATE file SET thumbnail_path = ? WHERE id = ?", thumbnail, fileId);
        return thumbnail;
    }

    private String path(Long fileId) {
        return jdbc.queryForObject("SELECT path FROM file WHERE id = ?", String.class, fileId);
    }

    private long count(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, Long.class, arguments);
    }

    private long files(Long id) {
        return count("SELECT count(*) FROM file WHERE id = ?", id);
    }

    private List<Map<String, Object>> row(String table, Long id) {
        return jdbc.queryForList("SELECT * FROM " + table + " WHERE id = ?", id);
    }

    private List<String> audit(CommonData data) {
        return jdbc.queryForList("SELECT action || ' ' || entity_type || ' ' || entity_id || ' ' || cascade_rows::text "
                + "|| ' ' || storage_objects FROM audit_log WHERE company_id = ? ORDER BY id", String.class,
                data.o1().company().getId());
    }

    private List<Object> deletedObjects() {
        return storageDouble.calls("delete").stream().map(call -> call.arguments().get(0)).toList();
    }

    private Response deleteFile(Long id, String token) {
        return api.call(delete("/files/" + id), token);
    }

    /**
     * Checks the 409 with its code and English message, and returns its blocking types.
     */
    private List<String> assertConflict(Response response, String code, String message) {
        JsonNode body = json(response);
        assertAll(
                () -> assertEquals(409, response.status(), response.toString()),
                () -> assertFalse(body.get("success").asBoolean(), response.toString()),
                () -> assertEquals(code, body.get("code").asText(), response.toString()),
                () -> assertEquals(message, body.get("message").asText(), response.toString()),
                () -> assertFalse(response.body().toLowerCase().contains("constraint"), response.toString()),
                () -> assertFalse(response.body().toLowerCase().contains("sql"), response.toString()));
        List<String> types = new ArrayList<>();
        if (body.has("blockingTypes")) body.get("blockingTypes").forEach(type -> types.add(type.asText()));
        return types;
    }

    private List<String> assertFileInUse(Response response) {
        return assertConflict(response, FILE_IN_USE, FILE_IN_USE_MESSAGE);
    }

    private void assertCode(Response response, String code) {
        assertEquals(409, response.status(), response.toString());
        assertEquals(code, json(response).get("code").asText(), response.toString());
    }

    private Request request(CommonData data) {
        Request request = new Request();
        request.setTitle(TestRecords.unique("P-17 request"));
        request.setCompany(data.o1().company());
        return requestRepository.saveAndFlush(request);
    }

    private PreventiveMaintenance preventiveMaintenance(CommonData data) {
        PreventiveMaintenance preventiveMaintenance = new PreventiveMaintenance();
        preventiveMaintenance.setName(TestRecords.unique("P-17 PM"));
        preventiveMaintenance.setTitle(preventiveMaintenance.getName());
        preventiveMaintenance.setCompany(data.o1().company());
        return preventiveMaintenanceRepository.saveAndFlush(preventiveMaintenance);
    }

    private Meter meter(CommonData data) {
        Meter meter = new Meter();
        meter.setName(TestRecords.unique("P-17 meter"));
        meter.setUpdateFrequency(1);
        meter.setAsset(records.asset(data.o1().company()));
        meter.setCompany(data.o1().company());
        return meterRepository.saveAndFlush(meter);
    }

    private WorkOrderMeterTrigger trigger(CommonData data) {
        WorkOrderMeterTrigger trigger = new WorkOrderMeterTrigger();
        trigger.setName(TestRecords.unique("P-17 trigger"));
        trigger.setTitle(trigger.getName());
        trigger.setTriggerCondition(WorkOrderMeterTriggerCondition.MORE_THAN);
        trigger.setValue(10);
        trigger.setMeter(meter(data));
        trigger.setCompany(data.o1().company());
        return meterTriggerRepository.saveAndFlush(trigger);
    }

    private FloorPlan floorPlan(CommonData data) {
        FloorPlan floorPlan = new FloorPlan();
        floorPlan.setName(TestRecords.unique("P-17 floor plan"));
        floorPlan.setLocation(records.location(data.o1().company()));
        return floorPlanRepository.saveAndFlush(floorPlan);
    }

    /**
     * One task of {@code preventiveMaintenance}, created through the API.
     */
    private Long preventiveMaintenanceTask(PreventiveMaintenance preventiveMaintenance, String token) {
        Response response = workOrders.call(patch("/tasks/preventive-maintenance/" + preventiveMaintenance.getId()),
                token, List.of(Map.of("label", TestRecords.unique("P-17 PM task"), "taskType", "SUBTASK",
                        "options", List.of())));
        assertTrue(response.is2xx(), response.toString());
        return json(response).get(0).get("id").asLong();
    }

    /**
     * Makes {@code source} ("table.column") cite {@code file} and returns how to read the citing rows.
     */
    private Supplier<List<Map<String, Object>>> cite(String source, CommonData data, Long file) {
        String[] parts = source.split("\\.");
        String table = parts[0];
        String column = parts[1];
        if (table.equals("work_order_aud")) {
            WorkOrder workOrder = records.workOrder(data.o1().company(), null, Status.OPEN, false);
            workOrder.setImage(fileRepository.findById(file).orElseThrow());
            workOrderRepository.saveAndFlush(workOrder);
            workOrder.setImage(null);
            workOrderRepository.saveAndFlush(workOrder);
            assertEquals(0, count("SELECT count(*) FROM work_order WHERE image_id = ?", file));
            assertTrue(count("SELECT count(*) FROM work_order_aud WHERE id = ? AND image_id = ?", workOrder.getId(),
                    file) > 0, "a revision cites the file");
            return () -> jdbc.queryForList("SELECT * FROM work_order_aud WHERE id = ? ORDER BY rev",
                    workOrder.getId());
        }
        Long owner = switch (table) {
            case "asset", "t_asset_file_associations" -> records.asset(data.o1().company()).getId();
            case "company" -> data.o1().company().getId();
            case "floor_plan" -> floorPlan(data).getId();
            case "location", "t_location_file_associations" -> records.location(data.o1().company()).getId();
            case "meter" -> meter(data).getId();
            case "own_user" -> data.o1().technician().getId();
            case "part", "t_part_file_associations" -> records.part(data.o1().company(), data.o1().admin()).getId();
            case "preventive_maintenance", "preventive_maintenance_files" -> preventiveMaintenance(data).getId();
            case "request", "request_files", "t_request_file_associations" -> request(data).getId();
            case "work_order", "work_order_files", "t_work_order_file_associations" ->
                    records.workOrder(data.o1().company(), null, Status.OPEN, false).getId();
            case "work_order_meter_trigger", "work_order_meter_trigger_files" -> trigger(data).getId();
            case "comment_files" -> workOrders.create(data.o1().admin(), data.o1().technician(), Status.OPEN, false)
                    .comment();
            default -> throw new IllegalArgumentException(source);
        };
        String ownerColumn = switch (table) {
            case "comment_files" -> "comment_id";
            case "preventive_maintenance_files" -> "preventive_maintenance_id";
            case "request_files" -> "request_id";
            case "work_order_files" -> "work_order_id";
            case "work_order_meter_trigger_files" -> "work_order_meter_trigger_id";
            case "t_asset_file_associations" -> "id_asset";
            case "t_location_file_associations" -> "id_location";
            case "t_part_file_associations" -> "id_part";
            case "t_request_file_associations" -> "id_request";
            case "t_work_order_file_associations" -> "id_work_order";
            default -> null;
        };
        if (ownerColumn == null) {
            jdbc.update("UPDATE " + table + " SET " + column + " = ? WHERE id = ?", file, owner);
            return () -> row(table, owner);
        }
        jdbc.update("INSERT INTO " + table + " (" + ownerColumn + ", " + column + ") VALUES (?, ?)", owner, file);
        return () -> jdbc.queryForList("SELECT * FROM " + table + " WHERE " + column + " = ?", file);
    }

    // ----------------------------------------------------------------------------------------------- tests

    @Nested
    class InUse {

        @Test
        void ca049_audioOfARequest_attachmentOfAWorkOrder_imageOfAnAsset_409_referencesIntact_noRecord() {
            CommonData data = data();
            String admin = api.login(data.o1().admin());
            Long f1 = upload(admin, null);
            Request s4 = request(data);
            jdbc.update("UPDATE request SET audio_description_id = ? WHERE id = ?", f1, s4.getId());
            Fixture w14 = workOrders.create(data.o1().admin(), data.o1().technician(), Status.COMPLETE, false);
            Long f2 = w14.file();
            Long f3 = upload(admin, null);
            Long x10 = records.asset(data.o1().company()).getId();
            jdbc.update("UPDATE asset SET image_id = ? WHERE id = ?", f3, x10);
            List<Map<String, Object>> request = row("request", s4.getId());
            Map<String, Object> workOrder = TestWorkOrders.rows(jdbc, w14.id(), w14.part());
            List<Map<String, Object>> asset = row("asset", x10);
            List<Map<String, Object>> fileRows = jdbc.queryForList("SELECT * FROM file WHERE id IN (?, ?, ?) "
                    + "ORDER BY id", f1, f2, f3);
            storageDouble.reset();

            Response r1 = deleteFile(f1, admin);
            Response r2 = deleteFile(f2, admin);
            Response r3 = deleteFile(f3, admin);

            assertAll(
                    () -> assertEquals(List.of("REQUEST"), assertFileInUse(r1)),
                    () -> assertEquals(List.of("WORK_ORDER"), assertFileInUse(r2)),
                    () -> assertEquals(List.of("ASSET"), assertFileInUse(r3)),
                    () -> assertEquals(request, row("request", s4.getId()), "the request is intact"),
                    () -> assertEquals(workOrder, TestWorkOrders.rows(jdbc, w14.id(), w14.part()),
                            "the work order and its attachment are intact"),
                    () -> assertEquals(asset, row("asset", x10), "the asset is intact"),
                    () -> assertEquals(fileRows, jdbc.queryForList("SELECT * FROM file WHERE id IN (?, ?, ?) "
                            + "ORDER BY id", f1, f2, f3), "the files are intact"),
                    () -> assertEquals(List.of(), audit(data), "no audit record"),
                    () -> assertEquals(List.of(), storageDouble.calls("delete"), "no stored object deleted"),
                    () -> assertTrue(mailDouble.calls().isEmpty(), "no mail"),
                    () -> assertTrue(webhookDouble.calls().isEmpty(), "no webhook"));
        }

        @ParameterizedTest(name = "{0} → {1}")
        @CsvSource({
                "asset.image_id, ASSET", "t_asset_file_associations.id_file, ASSET",
                "comment_files.files_id, COMMENT", "company.logo_id, COMPANY",
                "floor_plan.image_id, FLOOR_PLAN", "location.image_id, LOCATION",
                "t_location_file_associations.id_file, LOCATION", "meter.image_id, METER",
                "own_user.image_id, USER", "part.image_id, PART", "t_part_file_associations.id_file, PART",
                "preventive_maintenance.image_id, PREVENTIVE_MAINTENANCE",
                "preventive_maintenance_files.files_id, PREVENTIVE_MAINTENANCE",
                "request.image_id, REQUEST", "request.audio_description_id, REQUEST",
                "request_files.files_id, REQUEST", "t_request_file_associations.id_file, REQUEST",
                "work_order.image_id, WORK_ORDER", "work_order_files.files_id, WORK_ORDER",
                "t_work_order_file_associations.id_file, WORK_ORDER",
                "work_order_aud.image_id, WORK_ORDER_HISTORY",
                "work_order_meter_trigger.image_id, WORK_ORDER_METER_TRIGGER",
                "work_order_meter_trigger_files.files_id, WORK_ORDER_METER_TRIGGER"})
        void everySource_409WithItsType_citingRowsIntact(String source, String type) {
            CommonData data = data();
            String admin = api.login(data.o1().admin());
            Long file = upload(admin, null);
            Supplier<List<Map<String, Object>>> citing = cite(source, data, file);
            List<Map<String, Object>> before = citing.get();
            storageDouble.reset();

            Response response = deleteFile(file, admin);

            assertAll(
                    () -> assertEquals(List.of(type), assertFileInUse(response)),
                    () -> assertEquals(before, citing.get(), "the citing rows are intact"),
                    () -> assertEquals(1, files(file)),
                    () -> assertEquals(List.of(), audit(data), "no audit record"),
                    () -> assertEquals(List.of(), storageDouble.calls("delete"), "no stored object deleted"));
        }

        @Test
        void technicianWithoutDeleteOther_onAFileInUse_403_withoutTheTypes() {
            // PPB-26: 404, 403, then 409
            CommonData data = data();
            Long file = upload(api.login(data.o1().admin()), null);
            Long asset = records.asset(data.o1().company()).getId();
            jdbc.update("UPDATE asset SET image_id = ? WHERE id = ?", file, asset);

            Response response = deleteFile(file, api.login(data.o1().technician()));

            assertAll(
                    () -> assertEquals(403, response.status(), response.toString()),
                    () -> assertFalse(response.body().contains(FILE_IN_USE), response.toString()),
                    () -> assertFalse(response.body().contains("ASSET"), response.toString()),
                    () -> assertEquals(1, files(file)));
        }
    }

    @Nested
    class Unused {

        @Test
        void ca050_unused_200_deleted_recordedWithItsStoredObject() {
            CommonData data = data();
            String admin = api.login(data.o1().admin());
            Long file = upload(admin, null);
            String name = jdbc.queryForObject("SELECT name FROM file WHERE id = ?", String.class, file);

            Response response = deleteFile(file, admin);

            assertAll(
                    () -> assertEquals(200, response.status(), response.toString()),
                    () -> assertEquals(DELETED_BODY, response.body()),
                    () -> assertEquals(0, files(file)),
                    () -> assertEquals(List.of("DELETE FILE " + file + " {} 1"), audit(data)),
                    () -> assertEquals(name, jdbc.queryForObject("SELECT entity_name FROM audit_log "
                            + "WHERE company_id = ?", String.class, data.o1().company().getId())));
        }
    }

    @Nested
    class StoredObjects {

        @Test
        void ca051_fileDeleted_itsObjectAndThumbnailAreDeletedAfterTheCommit() {
            CommonData data = data();
            String admin = api.login(data.o1().admin());
            Long f4 = upload(admin, null);
            String path = path(f4);
            String thumbnail = withThumbnail(f4);

            Response response = deleteFile(f4, admin);

            assertAll(
                    () -> assertEquals(200, response.status(), response.toString()),
                    () -> assertEquals(List.of(path, thumbnail), deletedObjects()),
                    () -> assertFalse(storageDouble.contains(path), "the object is gone"),
                    () -> assertFalse(storageDouble.contains(thumbnail), "the thumbnail is gone"));
        }

        @Test
        void ca051_taskOfAnOpenWorkOrderRemoved_theObjectOfItsImageIsDeleted() {
            CommonData data = data();
            String admin = api.login(data.o1().admin());
            Fixture f = workOrders.create(data.o1().admin(), data.o1().technician(), Status.OPEN, false);
            Long f5 = upload(admin, f.task());
            String path = path(f5);

            Response response = api.call(delete("/tasks/" + f.task()), admin);

            assertAll(
                    () -> assertEquals(200, response.status(), response.toString()),
                    () -> assertEquals(0, files(f5)),
                    () -> assertEquals(List.of(path), deletedObjects()),
                    () -> assertFalse(storageDouble.contains(path), "the object is gone"),
                    () -> assertEquals(List.of("DELETE TASK " + f.task() + " {\"file\": 1} 1"), audit(data)));
        }

        @Test
        void taskReplacedOnAnOpenWorkOrder_theObjectOfItsImageIsDeleted() {
            CommonData data = data();
            String admin = api.login(data.o1().admin());
            Fixture f = workOrders.create(data.o1().admin(), data.o1().technician(), Status.OPEN, false);
            String path = path(upload(admin, f.task()));

            Response response = workOrders.call(patch("/tasks/work-order/" + f.id()), admin, List.of());

            assertAll(
                    () -> assertEquals(200, response.status(), response.toString()),
                    () -> assertEquals(List.of(path), deletedObjects()));
        }

        @Test
        void taskOfAPreventiveMaintenanceReplaced_theObjectOfItsImageIsDeleted() {
            CommonData data = data();
            String admin = api.login(data.o1().admin());
            PreventiveMaintenance preventiveMaintenance = preventiveMaintenance(data);
            String path = path(upload(admin, preventiveMaintenanceTask(preventiveMaintenance, admin)));

            Response response = workOrders.call(patch("/tasks/preventive-maintenance/" + preventiveMaintenance.getId()),
                    admin, List.of());

            assertAll(
                    () -> assertEquals(200, response.status(), response.toString()),
                    () -> assertEquals(List.of(path), deletedObjects()));
        }

        @Test
        void preventiveMaintenanceDeleted_theObjectsOfTheImagesOfItsTasksAreDeleted() {
            // Route 3 of the inventory (D-P17-3, C-4)
            CommonData data = data();
            String admin = api.login(data.o1().admin());
            PreventiveMaintenance preventiveMaintenance = preventiveMaintenance(data);
            Long image = upload(admin, preventiveMaintenanceTask(preventiveMaintenance, admin));
            String path = path(image);

            Response response = api.call(delete("/preventive-maintenances/" + preventiveMaintenance.getId()), admin);

            assertAll(
                    () -> assertEquals(200, response.status(), response.toString()),
                    () -> assertEquals(0, files(image)),
                    () -> assertEquals(List.of(path), deletedObjects()));
        }

        @Test
        void imageOfATaskOfAnOpenWorkOrder_deletedByItsEndpoint_objectDeleted() {
            CommonData data = data();
            String admin = api.login(data.o1().admin());
            Fixture f = workOrders.create(data.o1().admin(), data.o1().technician(), Status.OPEN, false);
            Long image = upload(admin, f.task());
            String path = path(image);

            Response response = deleteFile(image, admin);

            assertAll(
                    () -> assertEquals(200, response.status(), response.toString()),
                    () -> assertEquals(List.of(path), deletedObjects()));
        }

        @Test
        void imageOfATaskOfAPreventiveMaintenance_deletedByItsEndpoint_200() {
            CommonData data = data();
            String admin = api.login(data.o1().admin());
            PreventiveMaintenance preventiveMaintenance = preventiveMaintenance(data);
            Long image = upload(admin, preventiveMaintenanceTask(preventiveMaintenance, admin));

            Response response = deleteFile(image, admin);

            assertAll(
                    () -> assertEquals(200, response.status(), response.toString()),
                    () -> assertEquals(0, files(image)));
        }
    }

    @Nested
    class StorageFailure {

        @Test
        void ca052_storageFailsToDelete_200_recordGone_failureLoggedWithThePath_eachObjectTried() {
            CommonData data = data();
            String admin = api.login(data.o1().admin());
            Long f6 = upload(admin, null);
            String path = path(f6);
            String thumbnail = withThumbnail(f6);
            storageDouble.failOn("delete",
                    new IllegalStateException("P-17 storage down at https://storage.test/p17?X-Amz-Signature=secret"));
            Logger logger = (Logger) LoggerFactory.getLogger(FileService.class);
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            logger.addAppender(appender);
            Response response;
            try {
                response = deleteFile(f6, admin);
            } finally {
                logger.detachAppender(appender);
            }
            List<ILoggingEvent> failures = appender.list.stream()
                    .filter(event -> event.getFormattedMessage().contains(path)
                            || event.getFormattedMessage().contains(thumbnail)).toList();

            assertAll(
                    () -> assertEquals(200, response.status(), response.toString()),
                    () -> assertEquals(DELETED_BODY, response.body()),
                    () -> assertEquals(0, files(f6), "the record does not come back"),
                    () -> assertEquals(List.of(path, thumbnail), deletedObjects(),
                            "the failure of one object does not stop the next"),
                    () -> assertEquals(2, failures.size(), "one record per object: " + appender.list),
                    () -> assertTrue(failures.stream().allMatch(event -> event.getLevel() == Level.ERROR)),
                    () -> assertTrue(failures.stream().noneMatch(event -> event.getFormattedMessage()
                            .contains("Signature") || event.getFormattedMessage().contains("https://")),
                            "no signed address in the record"),
                    () -> assertTrue(failures.stream().allMatch(event -> event.getThrowableProxy() == null),
                            "no trace in the record"));
        }

        @Test
        void storageFailsToDelete_theOtherEffectsOfTheCommitStillRun() {
            storageDouble.failOn("delete", new IllegalStateException("P-17 storage down"));
            AtomicBoolean otherEffect = new AtomicBoolean();

            transaction.executeWithoutResult(status -> {
                fileService.deleteObjectsAfterCommit(List.of("p17/one", "p17/two"));
                AfterCommit.run(() -> otherEffect.set(true));
            });

            assertAll(
                    () -> assertEquals(List.of("p17/one", "p17/two"), deletedObjects()),
                    () -> assertTrue(otherEffect.get(), "the next effect after the commit ran"));
        }
    }

    @Nested
    class RolledBack {

        @Test
        void ca053_transactionFailsAtCommit_error_recordAndObjectExist_noStorageDelete() {
            CommonData data = data();
            String admin = api.login(data.o1().admin());
            Long f7 = upload(admin, null);
            String path = path(f7);
            Response response;
            try (Fault fault = faults.failAtCommit("file", Operation.DELETE)) {
                response = deleteFile(f7, admin);
                assertEquals(1, fault.hits(), "the fault point was crossed");
            } catch (Exception e) {
                throw new AssertionError(e);
            }

            assertAll(
                    () -> assertFalse(response.is2xx(), response.toString()),
                    () -> assertEquals(1, files(f7)),
                    () -> assertTrue(storageDouble.contains(path), "the object exists"),
                    () -> assertEquals(List.of(), storageDouble.calls("delete"), "no storage delete"),
                    () -> assertEquals(List.of(), audit(data)));
        }

        @Test
        void auditWriteFails_error_fileAndObjectExist_noStorageDelete() {
            CommonData data = data();
            String admin = api.login(data.o1().admin());
            Long file = upload(admin, null);
            String path = path(file);
            Response response;
            try (Fault ignored = faults.failOn("audit_log", Operation.INSERT)) {
                response = deleteFile(file, admin);
            } catch (Exception e) {
                throw new AssertionError(e);
            }

            assertAll(
                    () -> assertFalse(response.is2xx(), response.toString()),
                    () -> assertEquals(1, files(file)),
                    () -> assertTrue(storageDouble.contains(path), "the object exists"),
                    () -> assertEquals(List.of(), storageDouble.calls("delete"), "no storage delete"));
        }
    }

    /**
     * D-P17-2 (PPB-15): the image of a task of a closed or archived work order is not deleted by its endpoint.
     */
    @Nested
    class TaskOfAClosedWorkOrder {

        @Test
        void imageOfATaskOfAClosedWorkOrder_409WorkOrderClosed_fileAndObjectKept() {
            CommonData data = data();
            String admin = api.login(data.o1().admin());
            Fixture f = workOrders.create(data.o1().admin(), data.o1().technician(), Status.OPEN, false);
            Long image = upload(admin, f.task());
            String path = path(image);
            workOrders.moveTo(f.id(), Status.COMPLETE, false);
            storageDouble.reset();

            Response response = deleteFile(image, admin);

            assertAll(
                    () -> assertCode(response, "WORK_ORDER_CLOSED"),
                    () -> assertEquals(1, files(image)),
                    () -> assertTrue(storageDouble.contains(path)),
                    () -> assertEquals(List.of(), storageDouble.calls("delete")),
                    () -> assertEquals(List.of(), audit(data)));
        }

        @Test
        void imageOfATaskOfAnArchivedWorkOrder_409WorkOrderRetired_fileKept() {
            CommonData data = data();
            String admin = api.login(data.o1().admin());
            Fixture f = workOrders.create(data.o1().admin(), data.o1().technician(), Status.OPEN, false);
            Long image = upload(admin, f.task());
            workOrders.moveTo(f.id(), Status.OPEN, true);

            Response response = deleteFile(image, admin);

            assertAll(
                    () -> assertCode(response, "WORK_ORDER_RETIRED"),
                    () -> assertEquals(1, files(image)),
                    () -> assertEquals(List.of(), storageDouble.calls("delete")));
        }
    }

    /**
     * C-1 of G-1: removing a task whose image another record cites answers 409, never 500, by every route.
     */
    @Nested
    class TaskWithACitedImage {

        private Long citedByAnAsset(CommonData data, Long image) {
            Long asset = records.asset(data.o1().company()).getId();
            jdbc.update("UPDATE asset SET image_id = ? WHERE id = ?", image, asset);
            return asset;
        }

        private void assertKept(Response response, Long task, Long image, Long asset) {
            assertAll(
                    () -> assertCode(response, "RECORD_IN_USE"),
                    () -> assertEquals(1, count("SELECT count(*) FROM task WHERE id = ?", task)),
                    () -> assertEquals(1, files(image)),
                    () -> assertEquals(image, jdbc.queryForObject("SELECT image_id FROM asset WHERE id = ?",
                            Long.class, asset)),
                    () -> assertEquals(List.of(), storageDouble.calls("delete")));
        }

        @Test
        void deleteTheTaskOfAWorkOrder_409() {
            CommonData data = data();
            String admin = api.login(data.o1().admin());
            Fixture f = workOrders.create(data.o1().admin(), data.o1().technician(), Status.OPEN, false);
            Long image = upload(admin, f.task());
            Long asset = citedByAnAsset(data, image);

            Response response = api.call(delete("/tasks/" + f.task()), admin);

            assertKept(response, f.task(), image, asset);
        }

        @Test
        void replaceTheTasksOfAWorkOrder_409() {
            CommonData data = data();
            String admin = api.login(data.o1().admin());
            Fixture f = workOrders.create(data.o1().admin(), data.o1().technician(), Status.OPEN, false);
            Long image = upload(admin, f.task());
            Long asset = citedByAnAsset(data, image);

            Response response = workOrders.call(patch("/tasks/work-order/" + f.id()), admin, List.of());

            assertKept(response, f.task(), image, asset);
        }

        @Test
        void replaceTheTasksOfAPreventiveMaintenance_409() {
            CommonData data = data();
            String admin = api.login(data.o1().admin());
            PreventiveMaintenance preventiveMaintenance = preventiveMaintenance(data);
            Long task = preventiveMaintenanceTask(preventiveMaintenance, admin);
            Long image = upload(admin, task);
            Long asset = citedByAnAsset(data, image);

            Response response = workOrders.call(patch("/tasks/preventive-maintenance/" + preventiveMaintenance.getId()),
                    admin, List.of());

            assertKept(response, task, image, asset);
        }

        @Test
        void deleteThePreventiveMaintenance_409() {
            CommonData data = data();
            String admin = api.login(data.o1().admin());
            PreventiveMaintenance preventiveMaintenance = preventiveMaintenance(data);
            Long task = preventiveMaintenanceTask(preventiveMaintenance, admin);
            Long image = upload(admin, task);
            Long asset = citedByAnAsset(data, image);

            Response response = api.call(delete("/preventive-maintenances/" + preventiveMaintenance.getId()), admin);

            assertKept(response, task, image, asset);
            assertEquals(1, count("SELECT count(*) FROM preventive_maintenance WHERE id = ?",
                    preventiveMaintenance.getId()));
        }
    }

    @Nested
    class Schema {

        private static final String RULE = "SELECT c.confdeltype::text FROM pg_constraint c "
                + "JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = c.conkey[1] "
                + "WHERE c.contype = 'f' AND c.confrelid = 'file'::regclass AND c.conrelid = ?::regclass "
                + "AND a.attname = ?";
        private static final String INDEXED = "SELECT EXISTS (SELECT 1 FROM pg_index i "
                + "JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = i.indkey[0] "
                + "WHERE i.indrelid = ?::regclass AND a.attname = ? AND i.indisvalid AND i.indpred IS NULL)";

        @Test
        void the13References_areNoAction_andLeadAValidIndex() {
            assertAll(REFERENCES.stream().map(key -> () -> {
                assertEquals("a", jdbc.queryForObject(RULE, String.class, key[0], key[1]), key[0] + "." + key[1]);
                assertTrue(jdbc.queryForObject(INDEXED, Boolean.class, key[0], key[1]),
                        "index on " + key[0] + "." + key[1]);
            }));
        }

        @Test
        void theAssociationsKeepCascade_andTheSignaturesKeepSetNull() {
            assertAll(ASSOCIATIONS.stream().map(key -> () -> assertEquals("c",
                    jdbc.queryForObject(RULE, String.class, key[0], key[1]), key[0] + "." + key[1])));
            assertEquals("n", jdbc.queryForObject(RULE, String.class, "work_order", "signature_id"));
            assertEquals("n", jdbc.queryForObject(RULE, String.class, "work_order_aud", "signature_id"));
            assertEquals(25, count("SELECT count(*) FROM pg_constraint WHERE contype = 'f' "
                    + "AND confrelid = 'file'::regclass"));
        }
    }

    /**
     * The test of the independent audit H-1 for H1-02 ({@code closedOrderKeepsAttachedFile}), with its original
     * assertion.
     */
    @Nested
    class Audit {

        @Test
        void h1_02_closedOrderKeepsAttachedFile() {
            CommonData data = data();
            String admin = api.login(data.o1().admin());
            var f = workOrders.create(data.o1().admin(), data.o1().technician(), Status.COMPLETE, false);
            String sql = "SELECT count(*) FROM work_order_files WHERE work_order_id=? AND files_id=?";
            assertEquals(1, jdbc.queryForObject(sql, Integer.class, f.id(), f.file()));
            var result = api.call(delete("/files/" + f.file()), admin);
            int links = jdbc.queryForObject(sql, Integer.class, f.id(), f.file());
            System.out.println("H1 attachment: HTTP=" + result.status() + ", links remaining=" + links);
            assertEquals(1, links);
        }
    }
}
