package com.grash.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grash.integration.support.TestApi;
import com.grash.integration.support.TestApi.Response;
import com.grash.integration.support.TestAssetRecords;
import com.grash.integration.support.TestOrganizations;
import com.grash.integration.support.TestOrganizations.CommonData;
import com.grash.integration.support.TestRecords;
import com.grash.model.Asset;
import com.grash.model.AssetDowntime;
import com.grash.model.Company;
import com.grash.model.Deprecation;
import com.grash.model.Meter;
import com.grash.model.User;
import com.grash.model.WorkOrder;
import com.grash.model.enums.AssetStatus;
import com.grash.model.enums.Status;
import com.grash.repository.DeprecationRepository;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * P-14b characterization: fixes how an asset was archived and restored before any change, what an archived asset
 * still accepted (edits, new references, changes to its downtimes and depreciation), where it was listed, how the
 * planning listed archived work orders and what deleting an asset did to its depreciation. The 403 and 404 answers
 * must keep passing after P-14b; the tests that show an archive or a restoration without audit record, a silent
 * restoration, an archived asset accepted or listed by default, a child of an archived asset changed, an archived
 * work order listed as unscheduled or an orphan depreciation are updated to the contract of P-14b, as step 8 of the
 * order allows.
 */
@AutoConfigureMockMvc
class AssetArchivingCharacterizationIntegrationTest extends AbstractIntegrationTest {

    private final TestOrganizations organizations;
    private final TestRecords records;
    private final TestAssetRecords module;
    private final TestApi api;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;
    private final DeprecationRepository deprecationRepository;
    private final WorkflowRepository workflowRepository;
    private final List<CommonData> created = new ArrayList<>();

    @Autowired
    AssetArchivingCharacterizationIntegrationTest(TestOrganizations organizations, TestRecords records,
                                                  TestAssetRecords module, MockMvc mockMvc,
                                                  ObjectMapper objectMapper, JdbcTemplate jdbc,
                                                  DeprecationRepository deprecationRepository,
                                                  WorkflowRepository workflowRepository) {
        this.workflowRepository = workflowRepository;
        this.organizations = organizations;
        this.records = records;
        this.module = module;
        this.api = new TestApi(mockMvc, objectMapper);
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
        this.deprecationRepository = deprecationRepository;
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

    // --- helpers ---

    private long count(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, Long.class, arguments);
    }

    private List<String> audit(CommonData data) {
        return jdbc.queryForList("SELECT action || ' ' || entity_type || ' ' || coalesce(entity_folio, '-') "
                + "FROM audit_log WHERE company_id = ? ORDER BY id", String.class, data.o1().company().getId());
    }

    private boolean archived(Asset asset) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT archived FROM asset WHERE id = ?", Boolean.class,
                asset.getId()));
    }

    private Asset archivedAsset(Company company) {
        Asset asset = records.asset(company);
        jdbc.update("UPDATE asset SET archived = true WHERE id = ?", asset.getId());
        return asset;
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

    private List<Long> ids(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).map(node -> node.get("id").asLong()).toList();
    }

    private Deprecation deprecationOf(Asset asset) {
        Deprecation deprecation = new Deprecation();
        deprecation.setPurchasePrice(1000);
        deprecation.setCompany(asset.getCompany());
        deprecation = deprecationRepository.saveAndFlush(deprecation);
        jdbc.update("UPDATE asset SET deprecation_id = ? WHERE id = ?", deprecation.getId(), asset.getId());
        return deprecation;
    }

    // =====================================================================================================
    // Archive and restore (CA-PPB-023, 024)
    // =====================================================================================================

    @Nested
    class ArchiveAndRestore {

        @Test
        void archiveThenRestore_200_recorded_workOrdersMeterAndReadingsUnchanged() {
            CommonData data = data();
            Company company = data.o1().company();
            Asset asset = records.asset(company);
            WorkOrder first = records.workOrder(company, asset, Status.OPEN, false);
            WorkOrder second = records.workOrder(company, asset, Status.COMPLETE, false);
            Meter meter = module.meter(company, asset);
            module.reading(meter, 10);
            List<Map<String, Object>> before = jdbc.queryForList("SELECT * FROM work_order WHERE id IN (?, ?) "
                    + "ORDER BY id", first.getId(), second.getId());
            List<Map<String, Object>> meterBefore = jdbc.queryForList("SELECT * FROM meter WHERE id = ?",
                    meter.getId());
            List<Map<String, Object>> readingsBefore = jdbc.queryForList("SELECT * FROM reading WHERE meter_id = ?",
                    meter.getId());

            // before P-14b: the PATCH needs the name and the status (see onlyArchived_500 below)
            String body = "\"name\":\"" + asset.getName() + "\",\"status\":\"OPERATIONAL\"}";
            Response archive = send(patch("/assets/" + asset.getId()), "{\"archived\":true," + body,
                    data.o1().admin());
            boolean archivedAfterArchive = archived(asset);
            Response restore = send(patch("/assets/" + asset.getId()), "{\"archived\":false," + body,
                    data.o1().admin());

            assertAll(
                    () -> assertEquals(200, archive.status(), archive.toString()),
                    () -> assertTrue(archivedAfterArchive),
                    () -> assertEquals(200, restore.status(), restore.toString()),
                    () -> assertFalse(archived(asset)),
                    // P-14b (E-1): before, neither operation was recorded
                    () -> assertEquals(List.of("RETIRE ASSET -", "RESTORE ASSET -"), audit(data)),
                    () -> assertEquals(before, jdbc.queryForList("SELECT * FROM work_order WHERE id IN (?, ?) "
                            + "ORDER BY id", first.getId(), second.getId())),
                    () -> assertEquals(meterBefore, jdbc.queryForList("SELECT * FROM meter WHERE id = ?",
                            meter.getId())),
                    () -> assertEquals(readingsBefore, jdbc.queryForList("SELECT * FROM reading WHERE meter_id = ?",
                            meter.getId())));
        }

        @Test
        void onlyArchived_200_archived() {
            CommonData data = data();
            Asset asset = records.asset(data.o1().company());

            Response response = send(patch("/assets/" + asset.getId()), "{\"archived\":true}", data.o1().admin());

            // P-14b (D-5): before, 500, because the controller read the status of the body, which is null
            assertEquals(200, response.status(), response.toString());
            assertTrue(archived(asset));
        }

        @Test
        void archive_keepsTheOmittedFields() {
            CommonData data = data();
            Company company = data.o1().company();
            Asset asset = records.asset(company);
            jdbc.update("UPDATE asset SET location_id = ?, description = 'P-14b' WHERE id = ?",
                    records.location(company).getId(), asset.getId());

            Response response = send(patch("/assets/" + asset.getId()), "{\"archived\":true,\"name\":\""
                    + asset.getName() + "\",\"status\":\"OPERATIONAL\"}", data.o1().admin());

            assertEquals(200, response.status(), response.toString());
            // P-14b (D-5): before, the mapper copied every field of the DTO, so archiving with a partial body emptied
            // the rest; now archiving changes only archived
            assertTrue(archived(asset));
            assertEquals(1, count("SELECT count(*) FROM asset WHERE id = ? AND location_id IS NOT NULL "
                    + "AND description = 'P-14b'", asset.getId()));
        }

        @Test
        void technicianNotCreator_archive_403_unchanged() {
            CommonData data = data();
            Asset asset = records.asset(data.o1().company());

            Response response = send(patch("/assets/" + asset.getId()), "{\"archived\":true}",
                    data.o1().technician());

            assertEquals(403, response.status(), response.toString());
            assertFalse(archived(asset));
            assertEquals(List.of(), audit(data));
        }

        @Test
        void otherOrganization_archive_403_unknown_404() {
            CommonData data = data();
            Asset asset = records.asset(data.o1().company());

            Response other = send(patch("/assets/" + asset.getId()), "{\"archived\":true}", data.o2().admin());
            Response unknown = send(patch("/assets/" + Long.MAX_VALUE), "{\"archived\":true}", data.o1().admin());

            assertEquals(403, other.status(), other.toString());
            assertEquals(404, unknown.status(), unknown.toString());
            assertFalse(archived(asset));
        }
    }

    // =====================================================================================================
    // Edit an archived asset (PPB-08)
    // =====================================================================================================

    @Nested
    class EditArchived {

        @Test
        void patchWithoutArchived_409_staysArchived_nameUnchanged() {
            CommonData data = data();
            Asset asset = archivedAsset(data.o1().company());

            Response response = send(patch("/assets/" + asset.getId()),
                    "{\"name\":\"P-14b renamed\",\"status\":\"OPERATIONAL\"}", data.o1().admin());

            // P-14b (E-2): before, 200; the primitive archived of the DTO was false when omitted, so the edit
            // restored it
            assertEquals(409, response.status(), response.toString());
            assertTrue(archived(asset));
            assertEquals(asset.getName(), jdbc.queryForObject("SELECT name FROM asset WHERE id = ?", String.class,
                    asset.getId()));
            assertEquals(List.of(), audit(data));
        }

        @Test
        void patchWithArchivedTrue_409_nameUnchanged() {
            CommonData data = data();
            Asset asset = archivedAsset(data.o1().company());

            Response response = send(patch("/assets/" + asset.getId()),
                    "{\"archived\":true,\"name\":\"P-14b renamed\",\"status\":\"OPERATIONAL\"}", data.o1().admin());

            // P-14b (E-2): before, 200 and the name changed
            assertEquals(409, response.status(), response.toString());
            assertTrue(archived(asset));
            assertEquals(asset.getName(), jdbc.queryForObject("SELECT name FROM asset WHERE id = ?", String.class,
                    asset.getId()));
        }

        @Test
        void statusDown_onArchived_409_noDowntime() {
            CommonData data = data();
            Asset asset = archivedAsset(data.o1().company());

            Response response = send(patch("/assets/" + asset.getId()),
                    "{\"archived\":true,\"name\":\"" + asset.getName() + "\",\"status\":\"DOWN\"}", data.o1().admin());

            // P-14b (E-2, E-5): before, 200 and a downtime was opened
            assertEquals(409, response.status(), response.toString());
            assertEquals(0, count("SELECT count(*) FROM asset_downtime WHERE asset_id = ?", asset.getId()));
        }
    }

    // =====================================================================================================
    // New references to an archived asset (CA-PPB-025)
    // =====================================================================================================

    @Nested
    class NewReferences {

        @Test
        void workOrderCreatedWithArchivedAsset_409_notCreated() {
            CommonData data = data();
            Asset asset = archivedAsset(data.o1().company());

            Response response = send(post("/work-orders"), "{\"title\":\"" + TestRecords.unique("P-14b WO")
                    + "\",\"asset\":{\"id\":" + asset.getId() + "}}", data.o1().admin());

            // P-14b (E-3): before, 200 and the reference was created
            assertEquals(409, response.status(), response.toString());
            assertEquals(0, count("SELECT count(*) FROM work_order WHERE asset_id = ?", asset.getId()));
        }

        @Test
        void requestCreatedWithArchivedAsset_409_notCreated() {
            CommonData data = data();
            Asset asset = archivedAsset(data.o1().company());

            Response response = send(post("/requests"), "{\"title\":\"" + TestRecords.unique("P-14b request")
                    + "\",\"asset\":{\"id\":" + asset.getId() + "}}", data.o1().admin());

            // P-14b (E-3): before, 200 and the reference was created
            assertEquals(409, response.status(), response.toString());
            assertEquals(0, count("SELECT count(*) FROM request WHERE asset_id = ?", asset.getId()));
        }

        @Test
        void meterCreatedOnArchivedAsset_409_notCreated() {
            CommonData data = data();
            Asset asset = archivedAsset(data.o1().company());

            Response response = send(post("/meters"), "{\"name\":\"" + TestRecords.unique("P-14b meter")
                    + "\",\"updateFrequency\":1,\"asset\":{\"id\":" + asset.getId() + "}}", data.o1().admin());

            // P-14b (E-3): before, 200 and the reference was created
            assertEquals(409, response.status(), response.toString());
            assertEquals(0, count("SELECT count(*) FROM meter WHERE asset_id = ?", asset.getId()));
        }

        @Test
        void childAssetCreatedUnderArchivedAsset_409_notCreated() {
            CommonData data = data();
            Asset asset = archivedAsset(data.o1().company());

            Response response = send(post("/assets"), "{\"name\":\"" + TestRecords.unique("P-14b child")
                    + "\",\"parentAsset\":{\"id\":" + asset.getId() + "}}", data.o1().admin());

            // P-14b (E-3): before, 200 and the reference was created
            assertEquals(409, response.status(), response.toString());
            assertEquals(0, count("SELECT count(*) FROM asset WHERE parent_asset_id = ?", asset.getId()));
        }

        @Test
        void preventiveMaintenanceCreatedWithArchivedAsset_409_notCreated() {
            CommonData data = data();
            Asset asset = archivedAsset(data.o1().company());
            String name = TestRecords.unique("P-14b PM");

            Response response = send(post("/preventive-maintenances"), "{\"name\":\"" + name + "\",\"title\":\""
                    + name + "\",\"frequency\":1,\"recurrenceType\":\"DAILY\",\"recurrenceBasedOn\":"
                    + "\"SCHEDULED_DATE\",\"startsOn\":\"2099-01-01T00:00:00.000+00:00\",\"asset\":{\"id\":"
                    + asset.getId() + "}}", data.o1().admin());

            // P-14b (E-3): before, 200 and the reference was created
            assertEquals(409, response.status(), response.toString());
            assertEquals(0, count("SELECT count(*) FROM preventive_maintenance WHERE asset_id = ?", asset.getId()));
        }

        @Test
        void meterTriggerCreatedWithArchivedAsset_409_notCreated() {
            CommonData data = data();
            Company company = data.o1().company();
            Asset asset = archivedAsset(company);
            Meter meter = module.meter(company, records.asset(company));

            Response response = send(post("/work-order-meter-triggers"), "{\"name\":\""
                    + TestRecords.unique("P-14b trigger") + "\",\"title\":\"P-14b\",\"triggerCondition\":"
                    + "\"MORE_THAN\",\"value\":10,\"waitBefore\":0,\"meter\":{\"id\":" + meter.getId()
                    + "},\"asset\":{\"id\":" + asset.getId() + "}}", data.o1().admin());

            // P-14b (E-3): before, 200 and the reference was created
            assertEquals(409, response.status(), response.toString());
            assertEquals(0, count("SELECT count(*) FROM work_order_meter_trigger WHERE asset_id = ?",
                    asset.getId()));
        }

        @Test
        void approvingARequestThatCitesAnArchivedAsset_409_noWorkOrder() {
            CommonData data = data();
            Asset asset = records.asset(data.o1().company());
            Response created = send(post("/requests"), "{\"title\":\"" + TestRecords.unique("P-14b request")
                    + "\",\"asset\":{\"id\":" + asset.getId() + "}}", data.o1().admin());
            long request = json(created).get("id").asLong();
            jdbc.update("UPDATE asset SET archived = true WHERE id = ?", asset.getId());

            Response approved = send(patch("/requests/" + request + "/approve"), "{}", data.o1().admin());

            // P-14b (E-3): before, 200 and the reference was created
            assertEquals(409, approved.status(), approved.toString());
            assertEquals(0, count("SELECT count(*) FROM work_order WHERE asset_id = ?", asset.getId()));
        }

        @Test
        void triggeringAPreventiveMaintenanceOfAnArchivedAsset_409_noWorkOrder() {
            CommonData data = data();
            Asset asset = records.asset(data.o1().company());
            String name = TestRecords.unique("P-14b PM");
            Response created = send(post("/preventive-maintenances"), "{\"name\":\"" + name + "\",\"title\":\""
                    + name + "\",\"frequency\":1,\"recurrenceType\":\"DAILY\",\"recurrenceBasedOn\":"
                    + "\"SCHEDULED_DATE\",\"startsOn\":\"2099-01-01T00:00:00.000+00:00\",\"asset\":{\"id\":"
                    + asset.getId() + "}}", data.o1().admin());
            long preventiveMaintenance = json(created).get("id").asLong();
            jdbc.update("UPDATE asset SET archived = true WHERE id = ?", asset.getId());

            Response triggered = call(post("/preventive-maintenances/" + preventiveMaintenance
                    + "/trigger-work-order"), data.o1().admin());

            // P-14b (E-3): before, 200 and the reference was created
            assertEquals(409, triggered.status(), triggered.toString());
            assertEquals(0, count("SELECT count(*) FROM work_order WHERE asset_id = ?", asset.getId()));
        }

        @Test
        void readingThatFiresATriggerOfAnArchivedAsset_200_noWorkOrder() {
            CommonData data = data();
            Company company = data.o1().company();
            Asset asset = records.asset(company);
            Meter meter = module.meter(company, asset);
            var trigger = module.trigger(meter, com.grash.model.enums.WorkOrderMeterTriggerCondition.MORE_THAN, 10);
            jdbc.update("UPDATE work_order_meter_trigger SET asset_id = ? WHERE id = ?", asset.getId(),
                    trigger.getId());
            jdbc.update("UPDATE asset SET archived = true WHERE id = ?", asset.getId());

            Response response = send(post("/readings"), "{\"value\":98,\"meter\":{\"id\":" + meter.getId() + "}}",
                    data.o1().admin());

            assertEquals(200, response.status(), response.toString());
            // P-14b (D-1): before, the trigger generated a work order that cited the archived asset
            assertEquals(0, count("SELECT count(*) FROM work_order WHERE asset_id = ?", asset.getId()));
        }

        @Test
        void workflowThatAssignsAnArchivedAsset_skipsItsAction() {
            CommonData data = data();
            Company company = data.o1().company();
            Asset archived = archivedAsset(company);
            com.grash.model.WorkflowAction action = new com.grash.model.WorkflowAction();
            action.setCompany(company);
            action.setWorkOrderAction(com.grash.model.enums.workflow.WorkOrderAction.ASSIGN_ASSET);
            action.setAsset(archived);
            com.grash.model.Workflow workflow = new com.grash.model.Workflow();
            workflow.setCompany(company);
            workflow.setTitle(TestRecords.unique("P-14b workflow"));
            workflow.setMainCondition(com.grash.model.enums.workflow.WFMainCondition.WORK_ORDER_CREATED);
            workflow.setAction(action);
            workflowRepository.saveAndFlush(workflow);

            Response response = send(post("/work-orders"), "{\"title\":\"" + TestRecords.unique("P-14b WO")
                    + "\"}", data.o1().admin());

            assertEquals(200, response.status(), response.toString());
            // P-14b (D-2): before, the automation assigned the archived asset to the new work order
            assertNull(jdbc.queryForObject("SELECT asset_id FROM work_order WHERE id = ?",
                    Long.class, json(response).get("id").asLong()));
        }

        @Test
        void workOrderMovedToArchivedAsset_409_unchanged() {
            CommonData data = data();
            Company company = data.o1().company();
            Asset archived = archivedAsset(company);
            WorkOrder workOrder = records.workOrder(company, null, Status.OPEN, false);

            Response response = send(patch("/work-orders/" + workOrder.getId()), "{\"title\":\"P-14b\","
                    + "\"asset\":{\"id\":" + archived.getId() + "}}", data.o1().admin());

            // P-14b (E-3): before, 200 and the work order cited the archived asset
            assertEquals(409, response.status(), response.toString());
            assertEquals(0, count("SELECT count(*) FROM work_order WHERE id = ? AND asset_id IS NOT NULL",
                    workOrder.getId()));
        }

        @Test
        void workOrderAlreadyCitingArchivedAsset_editedKeepingIt_200() {
            CommonData data = data();
            Company company = data.o1().company();
            Asset archived = archivedAsset(company);
            WorkOrder workOrder = records.workOrder(company, archived, Status.OPEN, false);

            Response response = send(patch("/work-orders/" + workOrder.getId()), "{\"title\":\"P-14b kept\","
                    + "\"asset\":{\"id\":" + archived.getId() + "}}", data.o1().admin());

            assertEquals(200, response.status(), response.toString());
            assertEquals(archived.getId(), jdbc.queryForObject("SELECT asset_id FROM work_order WHERE id = ?",
                    Long.class, workOrder.getId()));
        }
    }

    // =====================================================================================================
    // Listings and selectors (CA-PPB-027, 028)
    // =====================================================================================================

    @Nested
    class Listings {

        @Test
        void searchWithoutFilters_miniAndHierarchy_leaveOutTheArchivedAsset() {
            CommonData data = data();
            Company company = data.o1().company();
            Asset active = records.asset(company);
            Asset archived = archivedAsset(company);

            Response search = send(post("/assets/search"), "{\"pageSize\":100}", data.o1().admin());
            Response mini = call(get("/assets/mini"), data.o1().admin());
            Response roots = call(get("/assets/children/0/paginated").param("size", "100"), data.o1().admin());

            assertEquals(200, search.status(), search.toString());
            assertEquals(200, mini.status(), mini.toString());
            assertEquals(200, roots.status(), roots.toString());
            // P-14b (E-4, D-3): before, the three included it and only the client left it out
            assertTrue(ids(json(search).get("content")).contains(active.getId()));
            assertFalse(ids(json(search).get("content")).contains(archived.getId()));
            assertTrue(ids(json(mini)).contains(active.getId()));
            assertFalse(ids(json(mini)).contains(archived.getId()));
            assertTrue(ids(json(roots).get("content")).contains(active.getId()));
            assertFalse(ids(json(roots).get("content")).contains(archived.getId()));
        }

        @Test
        void searchWithArchivedFilter_returnsOnlyTheArchivedAsset() {
            CommonData data = data();
            Company company = data.o1().company();
            Asset active = records.asset(company);
            Asset archived = archivedAsset(company);

            Response search = send(post("/assets/search"), "{\"pageSize\":100,\"filterFields\":[{\"field\":"
                    + "\"archived\",\"operation\":\"eq\",\"value\":true}]}", data.o1().admin());

            assertEquals(200, search.status(), search.toString());
            List<Long> found = ids(json(search).get("content"));
            assertTrue(found.contains(archived.getId()));
            assertFalse(found.contains(active.getId()));
        }

        @Test
        void archivedAsset_detailAndWorkOrderHistory_200() {
            CommonData data = data();
            Company company = data.o1().company();
            Asset archived = archivedAsset(company);
            WorkOrder workOrder = records.workOrder(company, archived, Status.COMPLETE, false);

            Response detail = call(get("/assets/" + archived.getId()), data.o1().admin());
            Response history = call(get("/work-orders/asset/" + archived.getId()), data.o1().admin());

            assertEquals(200, detail.status(), detail.toString());
            assertTrue(json(detail).get("archived").asBoolean());
            assertEquals(200, history.status(), history.toString());
            assertTrue(ids(json(history)).contains(workOrder.getId()));
        }
    }

    // =====================================================================================================
    // Children of an archived asset (CA-PPB-119, 123)
    // =====================================================================================================

    @Nested
    class Children {

        @Test
        void downtimeOfArchivedAsset_createEditDelete_409() {
            CommonData data = data();
            Asset asset = archivedAsset(data.o1().company());
            AssetDowntime downtime = records.downtime(asset);

            Response create = send(post("/asset-downtimes"), "{\"asset\":{\"id\":" + asset.getId()
                    + "},\"startsOn\":\"" + Instant.now().plusSeconds(60) + "\",\"duration\":3600}", data.o1().admin());
            Response edit = send(patch("/asset-downtimes/" + downtime.getId()), "{\"duration\":60,\"startsOn\":\""
                    + Instant.now().plusSeconds(120) + "\"}", data.o1().admin());
            Response remove = call(delete("/asset-downtimes/" + downtime.getId()), data.o1().admin());

            // P-14b (E-5, CA-PPB-119): before, the three answered 200
            assertEquals(409, create.status(), create.toString());
            assertEquals(409, edit.status(), edit.toString());
            assertEquals(409, remove.status(), remove.toString());
            assertEquals(1, count("SELECT count(*) FROM asset_downtime WHERE asset_id = ?", asset.getId()));
        }

        @Test
        void depreciationOfArchivedAsset_editDelete_409() {
            CommonData data = data();
            Asset asset = archivedAsset(data.o1().company());
            Deprecation deprecation = deprecationOf(asset);

            Response edit = send(patch("/deprecations/" + deprecation.getId()), "{\"purchasePrice\":5}",
                    data.o1().admin());
            Response remove = call(delete("/deprecations/" + deprecation.getId()), data.o1().admin());

            // P-14b (E-5, CA-PPB-123): before, both answered 200 and the depreciation was deleted
            assertEquals(409, edit.status(), edit.toString());
            assertEquals(409, remove.status(), remove.toString());
            assertEquals(1, count("SELECT count(*) FROM deprecation WHERE id = ?", deprecation.getId()));
        }

        @Test
        void closingTheLastWorkOrderOfArchivedAsset_keepsItsRunningDowntime() {
            CommonData data = data();
            Company company = data.o1().company();
            Asset asset = archivedAsset(company);
            jdbc.update("UPDATE asset SET status = ? WHERE id = ?", AssetStatus.DOWN.ordinal(), asset.getId());
            AssetDowntime downtime = records.downtime(asset);
            jdbc.update("UPDATE asset_downtime SET starts_on = now() - interval '1 hour' WHERE id = ?",
                    downtime.getId());
            WorkOrder workOrder = records.workOrder(company, asset, Status.OPEN, false);

            Response response = send(patch("/work-orders/" + workOrder.getId() + "/change-status"),
                    "{\"status\":\"COMPLETE\"}", data.o1().admin());

            assertEquals(200, response.status(), response.toString());
            // P-14b (E-5): before, the automatic downtime of the archived asset was closed and its status changed
            assertEquals(0L, count("SELECT duration FROM asset_downtime WHERE id = ?", downtime.getId()));
            assertEquals(AssetStatus.DOWN.ordinal(), jdbc.queryForObject("SELECT status FROM asset WHERE id = ?",
                    Integer.class, asset.getId()));
        }

        @Test
        void deletingAnAssetWithADepreciation_deletesItsDepreciation() {
            CommonData data = data();
            Asset asset = records.asset(data.o1().company());
            Deprecation deprecation = deprecationOf(asset);

            Response response = call(delete("/assets/" + asset.getId()), data.o1().admin());

            assertEquals(200, response.status(), response.toString());
            // P-14b (E-6, TD-P14-05): before, the depreciation was left without asset
            assertEquals(0, count("SELECT count(*) FROM deprecation WHERE id = ?", deprecation.getId()));
        }
    }

    // =====================================================================================================
    // Planning (PPB-09)
    // =====================================================================================================

    @Nested
    class Planning {

        @Test
        void archivedWorkOrder_isNotListedAsUnscheduled() {
            CommonData data = data();
            WorkOrder archived = records.workOrder(data.o1().company(), null, Status.OPEN, false);
            jdbc.update("UPDATE work_order SET archived = true WHERE id = ?", archived.getId());

            Response response = call(get("/workload/unscheduled"), data.o1().admin());

            assertEquals(200, response.status(), response.toString());
            // P-14b (E-7): before, the archived work order was listed
            assertFalse(ids(json(response).get("workOrders")).contains(archived.getId()));
        }
    }
}
