package com.grash.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grash.exception.ConflictException;
import com.grash.integration.support.FaultInjector;
import com.grash.integration.support.FaultInjector.Operation;
import com.grash.integration.support.TestApi;
import com.grash.integration.support.TestApi.Response;
import com.grash.integration.support.TestAssetRecords;
import com.grash.integration.support.TestOrganizations;
import com.grash.integration.support.TestOrganizations.CommonData;
import com.grash.integration.support.TestRecords;
import com.grash.model.Asset;
import com.grash.model.Company;
import com.grash.model.Meter;
import com.grash.model.User;
import com.grash.model.WorkOrder;
import com.grash.model.enums.Status;
import com.grash.repository.RequestPortalRepository;
import com.grash.service.RequestService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * P-14b: archiving and restoring an asset is recorded and changes nothing else (PPB-07, PPB-23); an archived asset
 * accepts no change but its restoration (PPB-08), no new reference (PPB-08), is left out of the operational lists and
 * selectors (PPB-09) and its children do not change (PPB-15).
 */
@AutoConfigureMockMvc
class AssetArchivingIntegrationTest extends AbstractIntegrationTest {

    static final String ASSET_RETIRED = "ASSET_RETIRED";

    private final TestOrganizations organizations;
    private final TestRecords records;
    private final TestAssetRecords module;
    private final TestApi api;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;
    private final FaultInjector faults;
    private final TransactionTemplate transaction;
    private final RequestService requestService;
    private final RequestPortalRepository requestPortalRepository;
    private final List<CommonData> created = new ArrayList<>();

    @Autowired
    AssetArchivingIntegrationTest(TestOrganizations organizations, TestRecords records, TestAssetRecords module,
                                  MockMvc mockMvc, ObjectMapper objectMapper, JdbcTemplate jdbc,
                                  TransactionTemplate transaction, RequestService requestService,
                                  RequestPortalRepository requestPortalRepository) {
        this.transaction = transaction;
        this.requestService = requestService;
        this.requestPortalRepository = requestPortalRepository;
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

    private List<String> audit(CommonData data) {
        return jdbc.queryForList("SELECT action || ' ' || entity_type || ' ' || coalesce(entity_folio, '-') || ' ' "
                + "|| coalesce(entity_name, '-') FROM audit_log WHERE company_id = ? ORDER BY id", String.class,
                data.o1().company().getId());
    }

    private boolean archived(Asset asset) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT archived FROM asset WHERE id = ?", Boolean.class,
                asset.getId()));
    }

    private Asset archivedAsset(Company company) {
        Asset asset = records.asset(company, TestRecords.unique("A-P14b"), false);
        jdbc.update("UPDATE asset SET archived = true WHERE id = ?", asset.getId());
        return asset;
    }

    /**
     * The asset row without {@code archived} and the columns of the auditing of the entity itself.
     */
    private Map<String, Object> assetRow(Asset asset) {
        Map<String, Object> row = new HashMap<>(jdbc.queryForMap("SELECT * FROM asset WHERE id = ?",
                asset.getId()));
        row.keySet().removeAll(List.of("archived", "updated_at", "updated_by"));
        return row;
    }

    private Response send(MockHttpServletRequestBuilder request, String body, User user) {
        return api.call(request.contentType(MediaType.APPLICATION_JSON).content(body), api.login(user));
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

    static List<Long> ids(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).map(node -> node.get("id").asLong()).toList();
    }

    /**
     * Asserts a 409 {@code code} with the common body (PPB-26).
     */
    private void assertConflict(Response response, String code) {
        assertEquals(409, response.status(), response.toString());
        JsonNode body = json(response);
        assertAll(
                () -> assertFalse(body.get("success").asBoolean()),
                () -> assertEquals(code, body.get("code").asText(), response.toString()),
                () -> assertFalse(body.get("message").asText().isBlank()),
                () -> assertNull(body.get("blockingTypes"), response.toString()),
                () -> assertFalse(response.body().toLowerCase().contains("sql"), response.toString()));
    }

    private void assertRetired(Response response) {
        assertConflict(response, ASSET_RETIRED);
    }

    // =====================================================================================================
    // B1 · E-1, E-2: archive and restore (CA-PPB-023, 024; C-3)
    // =====================================================================================================

    @Nested
    class ArchiveAndRestore {

        @Test
        void ca023_archiveAndRestore_200_recordsRetireAndRestore_workOrdersMeterAndReadingsUnchanged() {
            CommonData data = data();
            Company company = data.o1().company();
            Asset asset = records.asset(company, TestRecords.unique("A-P14b"), false);
            WorkOrder first = records.workOrder(company, asset, Status.OPEN, false);
            WorkOrder second = records.workOrder(company, asset, Status.COMPLETE, false);
            Meter meter = module.meter(company, asset);
            module.reading(meter, 10);
            module.reading(meter, 20);
            String others = "SELECT (SELECT json_agg(w ORDER BY w.id)::text FROM work_order w WHERE w.asset_id = ?) "
                    + "|| (SELECT json_agg(m)::text FROM meter m WHERE m.id = ?) "
                    + "|| (SELECT json_agg(r ORDER BY r.id)::text FROM reading r WHERE r.meter_id = ?)";
            String before = jdbc.queryForObject(others, String.class, asset.getId(), meter.getId(), meter.getId());
            Map<String, Object> rowBefore = assetRow(asset);

            Response archive = send(patch("/assets/" + asset.getId()), "{\"archived\":true}", data.o1().admin());
            boolean archivedAfterArchive = archived(asset);
            Response restore = send(patch("/assets/" + asset.getId()), "{\"archived\":false}", data.o1().admin());

            assertAll(
                    () -> assertEquals(200, archive.status(), archive.toString()),
                    () -> assertTrue(archivedAfterArchive),
                    () -> assertEquals(200, restore.status(), restore.toString()),
                    () -> assertFalse(archived(asset)),
                    () -> assertEquals(List.of("RETIRE ASSET " + asset.getCustomId() + " " + asset.getName(),
                            "RESTORE ASSET " + asset.getCustomId() + " " + asset.getName()), audit(data)),
                    () -> assertEquals(before, jdbc.queryForObject(others, String.class, asset.getId(),
                            meter.getId(), meter.getId())),
                    () -> assertEquals(rowBefore, assetRow(asset)),
                    () -> assertEquals(2, count("SELECT count(*) FROM work_order WHERE id IN (?, ?)", first.getId(),
                            second.getId())));
        }

        @Test
        void ca024_technicianWithoutEdit_archive_403_unchanged_noRecord() {
            CommonData data = data();
            Asset asset = records.asset(data.o1().company());

            Response response = send(patch("/assets/" + asset.getId()), "{\"archived\":true}",
                    data.o1().technician());

            assertEquals(403, response.status(), response.toString());
            assertFalse(archived(asset));
            assertEquals(List.of(), audit(data));
        }

        @Test
        void technicianWithoutEdit_restore_403_otherOrganization_403_unknown_404() {
            CommonData data = data();
            Asset asset = archivedAsset(data.o1().company());

            Response technician = send(patch("/assets/" + asset.getId()), "{\"archived\":false}",
                    data.o1().technician());
            Response other = send(patch("/assets/" + asset.getId()), "{\"archived\":false}", data.o2().admin());
            Response unknown = send(patch("/assets/" + Long.MAX_VALUE), "{\"archived\":false}", data.o1().admin());

            assertEquals(403, technician.status(), technician.toString());
            assertEquals(403, other.status(), other.toString());
            assertEquals(404, unknown.status(), unknown.toString());
            assertTrue(archived(asset));
            assertEquals(List.of(), audit(data));
        }

        @Test
        void c3_onlyArchivedTrue_200_noOtherFieldChanges() {
            CommonData data = data();
            Company company = data.o1().company();
            Asset asset = records.asset(company, TestRecords.unique("A-P14b"), false);
            jdbc.update("UPDATE asset SET location_id = ?, description = 'P-14b', model = 'M-1' WHERE id = ?",
                    records.location(company).getId(), asset.getId());
            Map<String, Object> before = assetRow(asset);

            Response response = send(patch("/assets/" + asset.getId()), "{\"archived\":true}", data.o1().admin());

            assertEquals(200, response.status(), response.toString());
            assertTrue(archived(asset));
            assertEquals(before, assetRow(asset));
        }

        @Test
        void c3_archiveWithOtherFields_onlyArchivedChanges() {
            CommonData data = data();
            Asset asset = records.asset(data.o1().company(), TestRecords.unique("A-P14b"), false);
            Map<String, Object> before = assetRow(asset);

            Response response = send(patch("/assets/" + asset.getId()), "{\"archived\":true,\"name\":\"P-14b "
                    + "renamed\",\"status\":\"DOWN\",\"description\":\"changed\"}", data.o1().admin());

            assertEquals(200, response.status(), response.toString());
            assertTrue(archived(asset));
            assertEquals(before, assetRow(asset));
            assertEquals(0, count("SELECT count(*) FROM asset_downtime WHERE asset_id = ?", asset.getId()));
        }

        @Test
        void c3_restoreWithOtherFields_onlyArchivedChanges() {
            CommonData data = data();
            Company company = data.o1().company();
            Asset asset = archivedAsset(company);
            jdbc.update("UPDATE asset SET location_id = ? WHERE id = ?", records.location(company).getId(),
                    asset.getId());
            Map<String, Object> before = assetRow(asset);

            Response response = send(patch("/assets/" + asset.getId()), "{\"archived\":false,\"name\":\"P-14b "
                    + "renamed\",\"status\":\"DOWN\",\"description\":\"changed\"}", data.o1().admin());

            assertEquals(200, response.status(), response.toString());
            assertFalse(archived(asset));
            assertEquals(before, assetRow(asset));
            assertEquals(0, count("SELECT count(*) FROM asset_downtime WHERE asset_id = ?", asset.getId()));
            assertEquals(List.of("RESTORE ASSET " + asset.getCustomId() + " " + asset.getName()), audit(data));
        }

        @Test
        void auditWriteFails_archiveRolledBack() {
            CommonData data = data();
            Asset asset = records.asset(data.o1().company());

            try (FaultInjector.Fault fault = faults.failOn("audit_log", Operation.INSERT)) {
                Response response = send(patch("/assets/" + asset.getId()), "{\"archived\":true}",
                        data.o1().admin());
                assertNotEquals(200, response.status(), response.toString());
                assertEquals(1, fault.hits());
            }
            assertFalse(archived(asset));
        }
    }

    // =====================================================================================================
    // B1 · E-2: an archived asset admits only its restoration (PPB-08)
    // =====================================================================================================

    @Nested
    class ArchivedIsImmutable {

        @Test
        void e2_patchWithoutArchived_409Retired_unchanged_staysArchived() {
            CommonData data = data();
            Asset asset = archivedAsset(data.o1().company());
            Map<String, Object> before = assetRow(asset);

            Response response = send(patch("/assets/" + asset.getId()),
                    "{\"name\":\"P-14b renamed\",\"status\":\"OPERATIONAL\"}", data.o1().admin());

            assertRetired(response);
            assertTrue(archived(asset));
            assertEquals(before, assetRow(asset));
            assertEquals(List.of(), audit(data));
        }

        @Test
        void e2_patchWithArchivedTrue_409Retired_unchanged() {
            CommonData data = data();
            Asset asset = archivedAsset(data.o1().company());
            Map<String, Object> before = assetRow(asset);

            Response response = send(patch("/assets/" + asset.getId()),
                    "{\"archived\":true,\"name\":\"P-14b renamed\",\"status\":\"DOWN\"}", data.o1().admin());

            assertRetired(response);
            assertEquals(before, assetRow(asset));
            assertEquals(0, count("SELECT count(*) FROM asset_downtime WHERE asset_id = ?", asset.getId()));
        }

        @Test
        void e2_activeAssetEditedWithoutArchived_orWithFalse_ordinaryEdit_noRecord() {
            CommonData data = data();
            Asset asset = records.asset(data.o1().company());

            Response without = send(patch("/assets/" + asset.getId()),
                    "{\"name\":\"P-14b one\",\"status\":\"OPERATIONAL\"}", data.o1().admin());
            Response withFalse = send(patch("/assets/" + asset.getId()),
                    "{\"archived\":false,\"name\":\"P-14b two\",\"status\":\"OPERATIONAL\"}", data.o1().admin());

            assertEquals(200, without.status(), without.toString());
            assertEquals(200, withFalse.status(), withFalse.toString());
            assertFalse(archived(asset));
            assertEquals("P-14b two", jdbc.queryForObject("SELECT name FROM asset WHERE id = ?", String.class,
                    asset.getId()));
            assertEquals(List.of(), audit(data));
        }

        @Test
        void e2_archivedIsStillReadById() {
            CommonData data = data();
            Asset asset = archivedAsset(data.o1().company());

            Response response = call(get("/assets/" + asset.getId()), data.o1().admin());

            assertEquals(200, response.status(), response.toString());
            assertTrue(json(response).get("archived").asBoolean());
        }
    }

    // =====================================================================================================
    // B2 · E-3: no new reference to an archived asset (CA-PPB-025)
    // =====================================================================================================

    private static String ref(Asset asset) {
        return "{\"id\":" + asset.getId() + "}";
    }

    private Response createPreventiveMaintenance(Asset asset, User user) {
        String name = TestRecords.unique("P-14b PM");
        return send(post("/preventive-maintenances"), "{\"name\":\"" + name + "\",\"title\":\"" + name
                + "\",\"frequency\":1,\"recurrenceType\":\"DAILY\",\"recurrenceBasedOn\":\"SCHEDULED_DATE\","
                + "\"startsOn\":\"2099-01-01T00:00:00.000+00:00\",\"asset\":" + ref(asset) + "}", user);
    }

    private long createRequest(Asset asset, User user) {
        Response created = send(post("/requests"), "{\"title\":\"" + TestRecords.unique("P-14b request")
                + "\",\"asset\":" + ref(asset) + "}", user);
        assertEquals(200, created.status(), created.toString());
        return json(created).get("id").asLong();
    }

    private void archive(Asset asset) {
        jdbc.update("UPDATE asset SET archived = true WHERE id = ?", asset.getId());
    }

    private String workflowBody(Asset actionAsset, Asset conditionAsset) {
        return "{\"title\":\"" + TestRecords.unique("P-14b workflow") + "\",\"mainCondition\":\"WORK_ORDER_CREATED\","
                + "\"secondaryConditions\":[" + (conditionAsset == null ? "" : "{\"workOrderCondition\":\"ASSET_IS\","
                + "\"asset\":" + ref(conditionAsset) + "}") + "],\"action\":{\"workOrderAction\":\"ASSIGN_ASSET\","
                + "\"asset\":" + ref(actionAsset) + "}}";
    }

    @Nested
    class NewReferences {

        @Test
        void ca025_workOrderCreatedWithArchivedAsset_409Retired_notCreated() {
            CommonData data = data();
            Asset archived = archivedAsset(data.o1().company());
            long workOrders = count("SELECT count(*) FROM work_order WHERE company_id = ?",
                    data.o1().company().getId());

            Response response = send(post("/work-orders"), "{\"title\":\"" + TestRecords.unique("P-14b WO")
                    + "\",\"asset\":" + ref(archived) + "}", data.o1().admin());

            assertRetired(response);
            assertEquals(workOrders, count("SELECT count(*) FROM work_order WHERE company_id = ?",
                    data.o1().company().getId()));
            assertEquals(List.of(), audit(data));
        }

        @Test
        void workOrderChangedToArchivedAsset_409_unchanged_alreadyCiting_editedKeepingIt_200() {
            CommonData data = data();
            Company company = data.o1().company();
            Asset archived = archivedAsset(company);
            WorkOrder moved = records.workOrder(company, null, Status.OPEN, false);
            WorkOrder citing = records.workOrder(company, archived, Status.OPEN, false);

            Response change = send(patch("/work-orders/" + moved.getId()), "{\"title\":\"P-14b\",\"asset\":"
                    + ref(archived) + "}", data.o1().admin());
            Response keep = send(patch("/work-orders/" + citing.getId()), "{\"title\":\"P-14b kept\",\"asset\":"
                    + ref(archived) + "}", data.o1().admin());

            assertRetired(change);
            assertEquals(0, count("SELECT count(*) FROM work_order WHERE id = ? AND asset_id IS NOT NULL",
                    moved.getId()));
            assertEquals(200, keep.status(), keep.toString());
            assertEquals(archived.getId(), jdbc.queryForObject("SELECT asset_id FROM work_order WHERE id = ?",
                    Long.class, citing.getId()));
        }

        @Test
        void requestCreatedOrChangedToArchivedAsset_409_alreadyCiting_editedKeepingIt_200() {
            CommonData data = data();
            Company company = data.o1().company();
            Asset active = records.asset(company);
            Asset archived = archivedAsset(company);
            long requests = count("SELECT count(*) FROM request WHERE company_id = ?", company.getId());

            Response create = send(post("/requests"), "{\"title\":\"P-14b\",\"asset\":" + ref(archived) + "}",
                    data.o1().admin());
            assertRetired(create);
            assertEquals(requests, count("SELECT count(*) FROM request WHERE company_id = ?", company.getId()));

            long request = createRequest(active, data.o1().admin());
            Response change = send(patch("/requests/" + request), "{\"title\":\"P-14b\",\"asset\":" + ref(archived)
                    + "}", data.o1().admin());
            assertRetired(change);
            assertEquals(active.getId(), jdbc.queryForObject("SELECT asset_id FROM request WHERE id = ?", Long.class,
                    request));

            archive(active);
            Response keep = send(patch("/requests/" + request), "{\"title\":\"P-14b kept\",\"asset\":" + ref(active)
                    + "}", data.o1().admin());
            assertEquals(200, keep.status(), keep.toString());
        }

        @Test
        void approvingARequestThatCitesAnArchivedAsset_409Retired_requestStaysPending_noWorkOrder() {
            CommonData data = data();
            Company company = data.o1().company();
            Asset asset = records.asset(company);
            long request = createRequest(asset, data.o1().admin());
            archive(asset);

            Response approved = send(patch("/requests/" + request + "/approve"), "{}", data.o1().admin());

            assertRetired(approved);
            assertEquals(0, count("SELECT count(*) FROM work_order WHERE asset_id = ?", asset.getId()));
            assertEquals(0, count("SELECT count(*) FROM request WHERE id = ? AND work_order_id IS NOT NULL",
                    request));
        }

        @Test
        void d4_portalWhoseFieldCitesAnArchivedAsset_409Retired_noRequest() {
            CommonData data = data();
            Company company = data.o1().company();
            Asset asset = records.asset(company);
            var portal = records.portalCiting(company, null, asset);
            archive(asset);
            long requests = count("SELECT count(*) FROM request WHERE company_id = ?", company.getId());

            // the test profile requires a reCAPTCHA token on the portal endpoint (application-test.yml), so the
            // request goes straight to the service the endpoint calls, as INC-02 does
            com.grash.model.Request request = new com.grash.model.Request();
            request.setTitle("P-14b portal");
            ConflictException conflict = assertThrows(ConflictException.class, () -> transaction.executeWithoutResult(
                    status -> requestService.create(request, company,
                            requestPortalRepository.findById(portal.getId()).orElseThrow())));

            assertEquals(ASSET_RETIRED, conflict.getCode());
            assertEquals(requests, count("SELECT count(*) FROM request WHERE company_id = ?", company.getId()));
        }

        @Test
        void preventiveMaintenanceCreatedOrChangedToArchivedAsset_409_alreadyCiting_editedKeepingIt_200() {
            CommonData data = data();
            Company company = data.o1().company();
            Asset active = records.asset(company);
            Asset archived = archivedAsset(company);

            assertRetired(createPreventiveMaintenance(archived, data.o1().admin()));
            assertEquals(0, count("SELECT count(*) FROM preventive_maintenance WHERE asset_id = ?",
                    archived.getId()));

            Response created = createPreventiveMaintenance(active, data.o1().admin());
            assertEquals(200, created.status(), created.toString());
            long preventiveMaintenance = json(created).get("id").asLong();
            Response change = send(patch("/preventive-maintenances/" + preventiveMaintenance), "{\"title\":"
                    + "\"P-14b\",\"name\":\"P-14b\",\"asset\":" + ref(archived) + "}", data.o1().admin());
            assertRetired(change);
            assertEquals(active.getId(), jdbc.queryForObject("SELECT asset_id FROM preventive_maintenance "
                    + "WHERE id = ?", Long.class, preventiveMaintenance));

            archive(active);
            Response keep = send(patch("/preventive-maintenances/" + preventiveMaintenance), "{\"title\":"
                    + "\"P-14b kept\",\"name\":\"P-14b kept\",\"asset\":" + ref(active) + "}", data.o1().admin());
            assertEquals(200, keep.status(), keep.toString());
        }

        @Test
        void triggeringAPreventiveMaintenanceOfAnArchivedAsset_409Retired_noWorkOrder() {
            CommonData data = data();
            Asset asset = records.asset(data.o1().company());
            Response created = createPreventiveMaintenance(asset, data.o1().admin());
            long preventiveMaintenance = json(created).get("id").asLong();
            archive(asset);

            Response triggered = call(post("/preventive-maintenances/" + preventiveMaintenance
                    + "/trigger-work-order"), data.o1().admin());

            assertRetired(triggered);
            assertEquals(0, count("SELECT count(*) FROM work_order WHERE asset_id = ?", asset.getId()));
        }

        @Test
        void meterTriggerCreatedOrChangedToArchivedAsset_409_alreadyCiting_editedKeepingIt_200() {
            CommonData data = data();
            Company company = data.o1().company();
            Asset active = records.asset(company);
            Asset archived = archivedAsset(company);
            Meter meter = module.meter(company, active);
            String trigger = "{\"name\":\"" + TestRecords.unique("P-14b trigger") + "\",\"title\":\"P-14b\","
                    + "\"triggerCondition\":\"MORE_THAN\",\"value\":10,\"waitBefore\":0,\"meter\":{\"id\":"
                    + meter.getId() + "},\"asset\":";

            assertRetired(send(post("/work-order-meter-triggers"), trigger + ref(archived) + "}", data.o1().admin()));
            assertEquals(0, count("SELECT count(*) FROM work_order_meter_trigger WHERE asset_id = ?",
                    archived.getId()));

            Response created = send(post("/work-order-meter-triggers"), trigger + ref(active) + "}",
                    data.o1().admin());
            assertEquals(200, created.status(), created.toString());
            long id = json(created).get("id").asLong();
            assertRetired(send(patch("/work-order-meter-triggers/" + id), trigger + ref(archived) + "}",
                    data.o1().admin()));
            archive(active);
            Response keep = send(patch("/work-order-meter-triggers/" + id), trigger + ref(active) + "}",
                    data.o1().admin());
            assertEquals(200, keep.status(), keep.toString());
        }

        @Test
        void meterCreatedOnArchivedAsset_409Retired_notCreated() {
            CommonData data = data();
            Asset archived = archivedAsset(data.o1().company());

            Response response = send(post("/meters"), "{\"name\":\"" + TestRecords.unique("P-14b meter")
                    + "\",\"updateFrequency\":1,\"asset\":" + ref(archived) + "}", data.o1().admin());

            assertRetired(response);
            assertEquals(0, count("SELECT count(*) FROM meter WHERE asset_id = ?", archived.getId()));
        }

        @Test
        void childAssetCreatedOrMovedUnderArchivedAsset_409_activeChildOfArchivedParent_editedKeepingIt_200() {
            CommonData data = data();
            Company company = data.o1().company();
            Asset archived = archivedAsset(company);
            Asset other = records.asset(company);
            Asset child = records.asset(company);
            jdbc.update("UPDATE asset SET parent_asset_id = ? WHERE id = ?", archived.getId(), child.getId());

            Response create = send(post("/assets"), "{\"name\":\"" + TestRecords.unique("P-14b child")
                    + "\",\"parentAsset\":" + ref(archived) + "}", data.o1().admin());
            Response move = send(patch("/assets/" + other.getId()), "{\"name\":\"P-14b\",\"status\":"
                    + "\"OPERATIONAL\",\"parentAsset\":" + ref(archived) + "}", data.o1().admin());
            Response keep = send(patch("/assets/" + child.getId()), "{\"name\":\"P-14b kept\",\"status\":"
                    + "\"OPERATIONAL\",\"parentAsset\":" + ref(archived) + "}", data.o1().admin());

            assertRetired(create);
            assertRetired(move);
            assertEquals(200, keep.status(), keep.toString());
            assertEquals(1, count("SELECT count(*) FROM asset WHERE parent_asset_id = ?", archived.getId()));
        }

        @Test
        void newTaskOnArchivedAsset_409_tasksAlreadyCitingIt_replacedKeepingThem_200() {
            CommonData data = data();
            Company company = data.o1().company();
            Asset active = records.asset(company);
            Asset archived = archivedAsset(company);
            WorkOrder workOrder = records.workOrder(company, null, Status.OPEN, false);
            // options sent, as the web app does: without them the match of a saved task fails with a 500
            // (TaskController.tasksMatch, outside P-14b)
            String task = "[{\"label\":\"P-14b task\",\"taskType\":\"SUBTASK\",\"options\":[],\"asset\":";

            assertRetired(send(patch("/tasks/work-order/" + workOrder.getId()), task + ref(archived) + "}]",
                    data.o1().admin()));
            assertEquals(0, count("SELECT count(*) FROM task WHERE work_order_id = ?", workOrder.getId()));

            Response created = send(patch("/tasks/work-order/" + workOrder.getId()), task + ref(active) + "}]",
                    data.o1().admin());
            assertEquals(200, created.status(), created.toString());
            archive(active);
            Response keep = send(patch("/tasks/work-order/" + workOrder.getId()), task + ref(active) + "}]",
                    data.o1().admin());
            assertEquals(200, keep.status(), keep.toString());
            assertEquals(1, count("SELECT count(*) FROM task WHERE work_order_id = ?", workOrder.getId()));
        }

        @Test
        void checklistWithATaskOnArchivedAsset_409Retired() {
            CommonData data = data();
            Company company = data.o1().company();
            Asset archived = archivedAsset(company);

            Response response = send(post("/checklists"), "{\"name\":\"" + TestRecords.unique("P-14b checklist")
                    + "\",\"companySettings\":{\"id\":" + company.getCompanySettings().getId() + "},\"taskBases\":"
                    + "[{\"label\":\"P-14b task\",\"taskType\":\"SUBTASK\",\"asset\":" + ref(archived) + "}]}",
                    data.o1().admin());

            assertRetired(response);
            assertEquals(0, count("SELECT count(*) FROM task_base WHERE asset_id = ?", archived.getId()));
        }

        @Test
        void workflowCreatedOrChangedToArchivedAsset_409_workflowKept_alreadyCiting_editedKeepingIt_200() {
            CommonData data = data();
            Company company = data.o1().company();
            Asset active = records.asset(company);
            Asset archived = archivedAsset(company);

            assertRetired(send(post("/workflows"), workflowBody(archived, null), data.o1().admin()));
            assertRetired(send(post("/workflows"), workflowBody(active, archived), data.o1().admin()));
            assertEquals(0, count("SELECT count(*) FROM workflow WHERE company_id = ?", company.getId()));

            Response created = send(post("/workflows"), workflowBody(active, null), data.o1().admin());
            assertEquals(200, created.status(), created.toString());
            long workflow = json(created).get("id").asLong();
            Response change = send(patch("/workflows/" + workflow), workflowBody(archived, null), data.o1().admin());
            assertRetired(change);
            assertEquals(1, count("SELECT count(*) FROM workflow WHERE id = ?", workflow));

            archive(active);
            Response keep = send(patch("/workflows/" + workflow), workflowBody(active, null), data.o1().admin());
            assertEquals(200, keep.status(), keep.toString());
        }

        @Test
        void portalFieldCreatedOrChangedToArchivedAsset_409_alreadyCiting_editedKeepingIt_200() {
            CommonData data = data();
            Company company = data.o1().company();
            Asset active = records.asset(company);
            Asset archived = archivedAsset(company);
            String portal = "{\"title\":\"" + TestRecords.unique("P-14b portal") + "\",\"fields\":[{\"type\":"
                    + "\"ASSET\",\"asset\":";

            assertRetired(send(post("/request-portals"), portal + ref(archived) + "}]}", data.o1().admin()));
            assertEquals(0, count("SELECT count(*) FROM request_portal_field WHERE asset_id = ?", archived.getId()));

            Response created = send(post("/request-portals"), portal + ref(active) + "}]}", data.o1().admin());
            assertEquals(200, created.status(), created.toString());
            long id = json(created).get("id").asLong();
            assertRetired(send(patch("/request-portals/" + id), portal + ref(archived) + "}]}", data.o1().admin()));
            archive(active);
            Response keep = send(patch("/request-portals/" + id), portal + ref(active) + "}]}", data.o1().admin());
            assertEquals(200, keep.status(), keep.toString());
        }
    }
}
