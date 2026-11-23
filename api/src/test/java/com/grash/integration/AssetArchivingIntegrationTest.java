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
import com.grash.model.Company;
import com.grash.model.Meter;
import com.grash.model.User;
import com.grash.model.WorkOrder;
import com.grash.model.enums.Status;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

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
    private final List<CommonData> created = new ArrayList<>();

    @Autowired
    AssetArchivingIntegrationTest(TestOrganizations organizations, TestRecords records, TestAssetRecords module,
                                  MockMvc mockMvc, ObjectMapper objectMapper, JdbcTemplate jdbc) {
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
}
