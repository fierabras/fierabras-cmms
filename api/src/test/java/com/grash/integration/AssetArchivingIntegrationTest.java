package com.grash.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grash.advancedsearch.SearchCriteria;
import com.grash.exception.ConflictException;
import com.grash.integration.support.FaultInjector.Operation;
import com.grash.integration.support.FaultInjector;
import com.grash.integration.support.TestApi.Response;
import com.grash.integration.support.TestApi;
import com.grash.integration.support.TestAssetRecords;
import com.grash.integration.support.TestOrganizations.CommonData;
import com.grash.integration.support.TestOrganizations;
import com.grash.integration.support.TestRecords;
import com.grash.model.Asset;
import com.grash.model.AssetDowntime;
import com.grash.model.Company;
import com.grash.model.Deprecation;
import com.grash.model.enums.AssetStatus;
import com.grash.model.enums.Status;
import com.grash.model.enums.workflow.RequestAction;
import com.grash.model.enums.workflow.TaskAction;
import com.grash.model.enums.workflow.WFMainCondition;
import com.grash.model.enums.workflow.WorkOrderAction;
import com.grash.model.Meter;
import com.grash.model.Request;
import com.grash.model.User;
import com.grash.model.WorkOrder;
import com.grash.model.Workflow;
import com.grash.model.WorkflowAction;
import com.grash.repository.DeprecationRepository;
import com.grash.repository.RequestPortalRepository;
import com.grash.job.WorkOrderCreationJob;
import com.grash.repository.UserRepository;
import com.grash.repository.WorkflowRepository;
import com.grash.service.AssetService;
import com.grash.service.RequestService;
import com.grash.service.WorkOrderService;
import com.grash.service.XlsxExportService;
import com.grash.utils.CsvFileGenerator;
import com.grash.utils.Helper;
import com.grash.utils.XlsxTestUtils;
import org.junit.jupiter.api.AfterEach;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.impl.matchers.GroupMatcher;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
    private final CsvFileGenerator csvFileGenerator;
    private final AssetService assetService;
    private final XlsxExportService xlsxExportService;
    private final WorkOrderService workOrderService;
    private final UserRepository userRepository;
    private final MessageSource messageSource;
    private final DeprecationRepository deprecationRepository;
    private final WorkOrderCreationJob workOrderCreationJob;
    private final Scheduler scheduler;
    private final WorkflowRepository workflowRepository;
    private final List<CommonData> created = new ArrayList<>();

    @Autowired
    AssetArchivingIntegrationTest(TestOrganizations organizations, TestRecords records, TestAssetRecords module,
                                  MockMvc mockMvc, ObjectMapper objectMapper, JdbcTemplate jdbc,
                                  TransactionTemplate transaction, RequestService requestService,
                                  RequestPortalRepository requestPortalRepository,
                                  CsvFileGenerator csvFileGenerator,
                                  AssetService assetService,
                                  XlsxExportService xlsxExportService,
                                  WorkOrderService workOrderService,
                                  UserRepository userRepository,
                                  MessageSource messageSource, DeprecationRepository deprecationRepository,
                                  WorkOrderCreationJob workOrderCreationJob, Scheduler scheduler,
                                  WorkflowRepository workflowRepository) {
        this.workOrderCreationJob = workOrderCreationJob;
        this.scheduler = scheduler;
        this.workflowRepository = workflowRepository;
        this.deprecationRepository = deprecationRepository;
        this.csvFileGenerator = csvFileGenerator;
        this.assetService = assetService;
        this.xlsxExportService = xlsxExportService;
        this.workOrderService = workOrderService;
        this.userRepository = userRepository;
        this.messageSource = messageSource;
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
        void inv10_otherOrganizationCitesAnArchivedAsset_neverAConflict_nothingCreated() {
            CommonData data = data();
            Asset archived = archivedAsset(data.o1().company());
            long workOrders = count("SELECT count(*) FROM work_order WHERE asset_id = ?", archived.getId());

            Response workOrder = send(post("/work-orders"), "{\"title\":\"" + TestRecords.unique("P-14b WO")
                    + "\",\"asset\":" + ref(archived) + "}", data.o2().admin());
            Response child = send(post("/assets"), "{\"name\":\"" + TestRecords.unique("P-14b child")
                    + "\",\"parentAsset\":" + ref(archived) + "}", data.o2().admin());
            Response request = send(post("/requests"), "{\"title\":\"P-14b\",\"asset\":" + ref(archived) + "}",
                    data.o2().admin());
            Response meter = send(post("/meters"), "{\"name\":\"" + TestRecords.unique("P-14b meter")
                    + "\",\"updateFrequency\":1,\"asset\":" + ref(archived) + "}", data.o2().admin());
            Response workflow = send(post("/workflows"), workflowBody(archived, null), data.o2().admin());

            // the 403 of the organization comes before the 409: O2 does not learn that the asset is archived
            assertEquals(403, workOrder.status(), workOrder.toString());
            assertEquals(403, child.status(), child.toString());
            assertEquals(403, request.status(), request.toString());
            assertEquals(403, meter.status(), meter.toString());
            assertEquals(403, workflow.status(), workflow.toString());
            assertEquals(0, count("SELECT count(*) FROM request WHERE asset_id = ?", archived.getId()));
            assertEquals(0, count("SELECT count(*) FROM meter WHERE asset_id = ?", archived.getId()));
            assertEquals(workOrders, count("SELECT count(*) FROM work_order WHERE asset_id = ?", archived.getId()));
            assertEquals(0, count("SELECT count(*) FROM asset WHERE parent_asset_id = ?", archived.getId()));
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
            Request request = new Request();
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
        void checklistChangedToArchivedAsset_409_alreadyCitingIt_editedKeepingIt_200() {
            CommonData data = data();
            Company company = data.o1().company();
            Asset active = records.asset(company);
            Asset archived = archivedAsset(company);
            String checklist = "{\"name\":\"" + TestRecords.unique("P-14b checklist") + "\",\"companySettings\":"
                    + "{\"id\":" + company.getCompanySettings().getId() + "},\"taskBases\":[{\"label\":"
                    + "\"P-14b task\",\"taskType\":\"SUBTASK\",\"asset\":";
            Response created = send(post("/checklists"), checklist + ref(active) + "}]}", data.o1().admin());
            assertEquals(200, created.status(), created.toString());
            long id = json(created).get("id").asLong();

            assertRetired(send(patch("/checklists/" + id), checklist + ref(archived) + "}]}", data.o1().admin()));
            assertEquals(0, count("SELECT count(*) FROM task_base WHERE asset_id = ?", archived.getId()));
            archive(active);
            Response keep = send(patch("/checklists/" + id), checklist + ref(active) + "}]}", data.o1().admin());
            assertEquals(200, keep.status(), keep.toString());
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

    // =====================================================================================================
    // B3 · E-4: visibility on the server (CA-PPB-027, 028; D-3; C-2)
    // =====================================================================================================

    private List<Long> searched(String body, User user) {
        Response response = send(post("/assets/search"), body, user);
        assertEquals(200, response.status(), response.toString());
        return ids(json(response).get("content"));
    }

    private List<Long> listed(MockHttpServletRequestBuilder request, User user) {
        Response response = call(request, user);
        assertEquals(200, response.status(), response.toString());
        JsonNode body = json(response);
        return ids(body.isArray() ? body : body.get("content"));
    }

    @Nested
    class Visibility {

        @Test
        void ca027_defaultListAndSelector_onlyTheActive_withTheFilter_theArchived() {
            CommonData data = data();
            Company company = data.o1().company();
            Asset x7 = records.asset(company);
            Asset x8 = archivedAsset(company);
            User admin = data.o1().admin();

            List<Long> byDefault = searched("{\"pageSize\":100}", admin);
            List<Long> selector = listed(get("/assets/mini"), admin);
            List<Long> filtered = searched("{\"pageSize\":100,\"filterFields\":[{\"field\":\"archived\","
                    + "\"operation\":\"eq\",\"value\":true}]}", admin);
            List<Long> notArchived = searched("{\"pageSize\":100,\"filterFields\":[{\"field\":\"archived\","
                    + "\"operation\":\"eq\",\"value\":false}]}", admin);

            assertAll(
                    () -> assertTrue(byDefault.contains(x7.getId())),
                    () -> assertFalse(byDefault.contains(x8.getId())),
                    () -> assertTrue(selector.contains(x7.getId())),
                    () -> assertFalse(selector.contains(x8.getId())),
                    () -> assertTrue(filtered.contains(x8.getId())),
                    () -> assertFalse(filtered.contains(x7.getId())),
                    () -> assertEquals(byDefault, notArchived));
        }

        @Test
        void selectorsByLocation_andOfThePortal_excludeTheArchived() {
            CommonData data = data();
            Company company = data.o1().company();
            var location = records.location(company);
            Asset active = records.asset(company);
            Asset archived = archivedAsset(company);
            jdbc.update("UPDATE asset SET location_id = ? WHERE id IN (?, ?)", location.getId(), active.getId(),
                    archived.getId());
            var portal = records.portalCiting(company, location, null);

            List<Long> byLocation = listed(get("/assets/mini").param("locationId", location.getId().toString()),
                    data.o1().admin());
            Response publicMini = api.call(get("/assets/public/mini/" + portal.getUuid()), null);
            Response publicByLocation = api.call(get("/assets/public/mini/" + portal.getUuid())
                    .param("locationId", location.getId().toString()), null);
            Response detailOfLocation = call(get("/assets/location/" + location.getId()), data.o1().admin());

            assertEquals(List.of(active.getId()), byLocation);
            assertEquals(200, publicMini.status(), publicMini.toString());
            assertTrue(ids(json(publicMini)).contains(active.getId()));
            assertFalse(ids(json(publicMini)).contains(archived.getId()));
            assertEquals(List.of(active.getId()), ids(json(publicByLocation)));
            assertEquals(List.of(active.getId()), ids(json(detailOfLocation)));
        }

        @Test
        void d3_hierarchy_excludesTheArchived_hasChildrenCountsTheActive() {
            CommonData data = data();
            Company company = data.o1().company();
            User admin = data.o1().admin();
            Asset root = records.asset(company);
            Asset archivedRoot = archivedAsset(company);
            Asset activeChild = records.asset(company);
            Asset archivedChild = archivedAsset(company);
            Asset onlyArchivedChildren = records.asset(company);
            Asset hiddenChild = archivedAsset(company);
            jdbc.update("UPDATE asset SET parent_asset_id = ? WHERE id IN (?, ?)", root.getId(), activeChild.getId(),
                    archivedChild.getId());
            jdbc.update("UPDATE asset SET parent_asset_id = ? WHERE id = ?", onlyArchivedChildren.getId(),
                    hiddenChild.getId());

            List<Long> roots = listed(get("/assets/children/0/paginated").param("size", "100"), admin);
            List<Long> rootsDeprecated = listed(get("/assets/children/0"), admin);
            List<Long> children = listed(get("/assets/children/" + root.getId() + "/paginated"), admin);
            List<Long> childrenDeprecated = listed(get("/assets/children/" + root.getId()), admin);
            Response withActiveChild = call(get("/assets/" + root.getId()), admin);
            Response withArchivedChild = call(get("/assets/" + onlyArchivedChildren.getId()), admin);

            assertAll(
                    () -> assertTrue(roots.contains(root.getId())),
                    () -> assertFalse(roots.contains(archivedRoot.getId())),
                    () -> assertTrue(rootsDeprecated.contains(root.getId())),
                    () -> assertFalse(rootsDeprecated.contains(archivedRoot.getId())),
                    () -> assertEquals(List.of(activeChild.getId()), children),
                    () -> assertEquals(List.of(activeChild.getId()), childrenDeprecated),
                    () -> assertTrue(json(withActiveChild).get("hasChildren").asBoolean()),
                    () -> assertFalse(json(withArchivedChild).get("hasChildren").asBoolean()));
        }

        @Test
        void d3_assetsOfAPart_excludeTheArchived() {
            CommonData data = data();
            Company company = data.o1().company();
            var part = records.part(company, null);
            Asset active = records.asset(company);
            Asset archived = archivedAsset(company);
            jdbc.update("INSERT INTO t_asset_part_associations (id_asset, id_part) VALUES (?, ?), (?, ?)",
                    active.getId(), part.getId(), archived.getId(), part.getId());

            assertEquals(List.of(active.getId()), listed(get("/assets/part/" + part.getId()), data.o1().admin()));
        }

        @Test
        void c2_activeChildOfAnArchivedParent_isSearchedAndReadById() {
            CommonData data = data();
            Company company = data.o1().company();
            Asset parent = archivedAsset(company);
            Asset child = records.asset(company);
            jdbc.update("UPDATE asset SET parent_asset_id = ? WHERE id = ?", parent.getId(), child.getId());

            List<Long> byDefault = searched("{\"pageSize\":100}", data.o1().admin());
            Response detail = call(get("/assets/" + child.getId()), data.o1().admin());

            assertTrue(byDefault.contains(child.getId()));
            assertEquals(200, detail.status(), detail.toString());
            assertEquals(parent.getId(), json(detail).get("parentAsset").get("id").asLong());
        }

        @Test
        void ca028_archived_detailHistoryAndBothExports_withTheirState() throws Exception {
            CommonData data = data();
            Company company = data.o1().company();
            Asset x8 = archivedAsset(company);
            WorkOrder w6 = records.workOrder(company, x8, Status.COMPLETE, false);
            User admin = data.o1().admin();

            Response detail = call(get("/assets/" + x8.getId()), admin);
            List<Long> history = listed(get("/work-orders/asset/" + x8.getId()), admin);
            String assets = transaction.execute(status -> {
                StringWriter writer = new StringWriter();
                csvFileGenerator.writeAssetsToCsv(assetService.findByCompanyForExport(company.getId(),
                                PageRequest.of(0, 100)).getContent(), writer,
                        Locale.ENGLISH, ",", true);
                return writer.toString();
            });
            byte[] workOrders = transaction.execute(status -> {
                User current = userRepository.findById(admin.getId()).orElseThrow();
                Helper.setCurrentUser(current);
                try {
                    return xlsxExportService.exportWorkOrders(current, workOrderService.getSearchCriteria(current,
                            new SearchCriteria()));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            SecurityContextHolder.clearContext();
            String archivedText = Helper.getStringFromBoolean(true, messageSource,
                    Locale.ENGLISH);
            List<List<String>> workOrderRows = XlsxTestUtils.readText(workOrders, 1);

            assertAll(
                    () -> assertEquals(200, detail.status(), detail.toString()),
                    () -> assertTrue(json(detail).get("archived").asBoolean()),
                    () -> assertTrue(history.contains(w6.getId())),
                    () -> assertTrue(assets.lines().anyMatch(line -> line.startsWith(x8.getId() + ",")
                            && line.contains("," + archivedText + ",")), assets),
                    () -> assertTrue(workOrderRows.stream().anyMatch(row -> row.get(0).equals(
                            String.valueOf(w6.getId()))), workOrderRows.toString()));
        }
    }

    // =====================================================================================================
    // B4 · E-5: the children of an archived asset do not change (CA-PPB-119, 123)
    // =====================================================================================================

    private Deprecation deprecationOf(Asset asset) {
        Deprecation deprecation = new Deprecation();
        deprecation.setPurchasePrice(1000);
        deprecation.setCompany(asset.getCompany());
        deprecation = deprecationRepository.saveAndFlush(deprecation);
        jdbc.update("UPDATE asset SET deprecation_id = ? WHERE id = ?", deprecation.getId(), asset.getId());
        return deprecation;
    }

    private Map<String, Object> rowOf(String table, Long id) {
        return jdbc.queryForMap("SELECT * FROM " + table + " WHERE id = ?", id);
    }

    private int status(Asset asset) {
        return jdbc.queryForObject("SELECT status FROM asset WHERE id = ?", Integer.class, asset.getId());
    }

    private AssetDowntime runningDowntime(Asset asset) {
        jdbc.update("UPDATE asset SET status = ? WHERE id = ?", AssetStatus.DOWN.ordinal(), asset.getId());
        AssetDowntime downtime = records.downtime(asset);
        jdbc.update("UPDATE asset_downtime SET starts_on = now() - interval '1 hour' WHERE id = ?",
                downtime.getId());
        return downtime;
    }

    @Nested
    class Children {

        @Test
        void ca119_downtimeOfArchivedAsset_editOrDelete_409Retired_unchanged() {
            CommonData data = data();
            Asset x16 = archivedAsset(data.o1().company());
            AssetDowntime dt2 = records.downtime(x16);
            Map<String, Object> before = rowOf("asset_downtime", dt2.getId());

            Response edit = send(patch("/asset-downtimes/" + dt2.getId()), "{\"duration\":60,\"startsOn\":\""
                    + Instant.now().minusSeconds(3600) + "\"}", data.o1().admin());
            Response remove = call(delete("/asset-downtimes/" + dt2.getId()), data.o1().admin());

            assertRetired(edit);
            assertRetired(remove);
            assertEquals(before, rowOf("asset_downtime", dt2.getId()));
            assertEquals(List.of(), audit(data));
        }

        @Test
        void downtimeCreatedOnArchivedAsset_409Retired_notCreated() {
            CommonData data = data();
            Asset asset = archivedAsset(data.o1().company());

            Response create = send(post("/asset-downtimes"), "{\"asset\":" + ref(asset) + ",\"startsOn\":\""
                    + Instant.now().plusSeconds(60) + "\",\"duration\":3600}", data.o1().admin());

            assertRetired(create);
            assertEquals(0, count("SELECT count(*) FROM asset_downtime WHERE asset_id = ?", asset.getId()));
        }

        @Test
        void downtimeOfArchivedAsset_unknown_404_otherOrganization_notConflict() {
            CommonData data = data();
            Asset asset = archivedAsset(data.o1().company());
            AssetDowntime downtime = records.downtime(asset);

            Response unknownEdit = send(patch("/asset-downtimes/" + Long.MAX_VALUE), "{\"duration\":60}",
                    data.o1().admin());
            Response unknownDelete = call(delete("/asset-downtimes/" + Long.MAX_VALUE), data.o1().admin());
            Response otherDelete = call(delete("/asset-downtimes/" + downtime.getId()), data.o2().admin());

            assertEquals(404, unknownEdit.status(), unknownEdit.toString());
            assertEquals(404, unknownDelete.status(), unknownDelete.toString());
            assertEquals(403, otherDelete.status(), otherDelete.toString());
            assertEquals(1, count("SELECT count(*) FROM asset_downtime WHERE id = ?", downtime.getId()));
        }

        @Test
        void ca123_deleteDepreciationOfArchivedAsset_409Retired_depreciationExists_editAlso409() {
            CommonData data = data();
            Asset x16 = archivedAsset(data.o1().company());
            Deprecation dp1 = deprecationOf(x16);
            Map<String, Object> before = rowOf("deprecation", dp1.getId());

            Response remove = call(delete("/deprecations/" + dp1.getId()), data.o1().admin());
            Response edit = send(patch("/deprecations/" + dp1.getId()), "{\"purchasePrice\":5}", data.o1().admin());

            assertRetired(remove);
            assertRetired(edit);
            assertEquals(before, rowOf("deprecation", dp1.getId()));
            assertEquals(dp1.getId(), jdbc.queryForObject("SELECT deprecation_id FROM asset WHERE id = ?",
                    Long.class, x16.getId()));
            assertEquals(List.of(), audit(data));
        }

        @Test
        void depreciationOfActiveAsset_editAndDelete_200_unknown_404() {
            CommonData data = data();
            Asset asset = records.asset(data.o1().company());
            Deprecation deprecation = deprecationOf(asset);

            Response unknown = call(delete("/deprecations/" + Long.MAX_VALUE), data.o1().admin());
            Response edit = send(patch("/deprecations/" + deprecation.getId()), "{\"purchasePrice\":5}",
                    data.o1().admin());
            Response remove = call(delete("/deprecations/" + deprecation.getId()), data.o1().admin());

            assertEquals(404, unknown.status(), unknown.toString());
            assertEquals(200, edit.status(), edit.toString());
            assertEquals(200, remove.status(), remove.toString());
        }

        @Test
        void closingTheLastWorkOrderOfArchivedAsset_200_itsDowntimeAndStatusDoNotChange() {
            CommonData data = data();
            Company company = data.o1().company();
            Asset asset = archivedAsset(company);
            AssetDowntime downtime = runningDowntime(asset);
            WorkOrder workOrder = records.workOrder(company, asset, Status.OPEN, false);

            Response response = send(patch("/work-orders/" + workOrder.getId() + "/change-status"),
                    "{\"status\":\"COMPLETE\"}", data.o1().admin());

            assertEquals(200, response.status(), response.toString());
            assertEquals(Status.COMPLETE.ordinal(), jdbc.queryForObject("SELECT status FROM work_order WHERE id = ?",
                    Integer.class, workOrder.getId()));
            assertEquals(0L, count("SELECT duration FROM asset_downtime WHERE id = ?", downtime.getId()));
            assertEquals(AssetStatus.DOWN.ordinal(), status(asset));
        }

        @Test
        void childGoingDown_archivedParentGetsNoDowntime_activeGrandparentDoes() {
            CommonData data = data();
            Company company = data.o1().company();
            Asset grandparent = records.asset(company);
            Asset parent = archivedAsset(company);
            Asset child = records.asset(company);
            jdbc.update("UPDATE asset SET parent_asset_id = ? WHERE id = ?", grandparent.getId(), parent.getId());
            jdbc.update("UPDATE asset SET parent_asset_id = ? WHERE id = ?", parent.getId(), child.getId());

            Response response = send(patch("/assets/" + child.getId()), "{\"name\":\"" + child.getName()
                    + "\",\"status\":\"DOWN\",\"parentAsset\":" + ref(parent) + "}", data.o1().admin());

            assertEquals(200, response.status(), response.toString());
            assertEquals(1, count("SELECT count(*) FROM asset_downtime WHERE asset_id = ?", child.getId()));
            assertEquals(0, count("SELECT count(*) FROM asset_downtime WHERE asset_id = ?", parent.getId()));
            assertEquals(AssetStatus.OPERATIONAL.ordinal(), status(parent));
            assertEquals(1, count("SELECT count(*) FROM asset_downtime WHERE asset_id = ?", grandparent.getId()));
            assertEquals(AssetStatus.DOWN.ordinal(), status(grandparent));
        }

        @Test
        void parentBackUp_itsArchivedChildKeepsItsDowntime_activeGrandchildIsStopped() {
            CommonData data = data();
            Company company = data.o1().company();
            Asset parent = records.asset(company);
            Asset child = archivedAsset(company);
            Asset grandchild = records.asset(company);
            jdbc.update("UPDATE asset SET parent_asset_id = ? WHERE id = ?", parent.getId(), child.getId());
            jdbc.update("UPDATE asset SET parent_asset_id = ? WHERE id = ?", child.getId(), grandchild.getId());
            runningDowntime(parent);
            AssetDowntime childDowntime = runningDowntime(child);
            AssetDowntime grandchildDowntime = runningDowntime(grandchild);

            Response response = send(patch("/assets/" + parent.getId()), "{\"name\":\"" + parent.getName()
                    + "\",\"status\":\"OPERATIONAL\"}", data.o1().admin());

            assertEquals(200, response.status(), response.toString());
            assertEquals(0L, count("SELECT duration FROM asset_downtime WHERE id = ?", childDowntime.getId()));
            assertEquals(AssetStatus.DOWN.ordinal(), status(child));
            assertNotEquals(0L, count("SELECT duration FROM asset_downtime WHERE id = ?",
                    grandchildDowntime.getId()));
            assertEquals(AssetStatus.OPERATIONAL.ordinal(), status(grandchild));
        }
    }

    // =====================================================================================================
    // B5 · E-6: the depreciation goes with its asset; E-7, D-8: planning without archived work orders
    // =====================================================================================================

    private WorkOrder plannedWorkOrder(CommonData data, boolean archived) {
        WorkOrder workOrder = records.workOrder(data.o1().company(), null, Status.OPEN, false);
        jdbc.update("UPDATE work_order SET archived = ?, primary_user_id = ?, estimated_start_date = "
                + "'2026-11-03 10:00:00' WHERE id = ?", archived, data.o1().admin().getId(), workOrder.getId());
        return workOrder;
    }

    @Nested
    class DepreciationAndPlanning {

        @Test
        void e6_deletingAnAssetDeletesItsDepreciation_inTheSummary() {
            CommonData data = data();
            Asset asset = records.asset(data.o1().company(), TestRecords.unique("A-P14b"), false);
            Deprecation deprecation = deprecationOf(asset);

            Response response = call(delete("/assets/" + asset.getId()), data.o1().admin());

            assertEquals(200, response.status(), response.toString());
            assertEquals(0, count("SELECT count(*) FROM deprecation WHERE id = ?", deprecation.getId()));
            assertEquals(List.of("{\"deprecation\": 1}"), jdbc.queryForList("SELECT cascade_rows::text FROM audit_log "
                    + "WHERE company_id = ? AND action = 'DELETE' AND entity_type = 'ASSET'", String.class,
                    data.o1().company().getId()));
        }

        @Test
        void e6_aDepreciationCitedByAnotherAsset_isKept() {
            CommonData data = data();
            Company company = data.o1().company();
            Asset deleted = records.asset(company);
            Asset other = records.asset(company);
            Deprecation deprecation = deprecationOf(deleted);
            jdbc.update("UPDATE asset SET deprecation_id = ? WHERE id = ?", deprecation.getId(), other.getId());

            Response response = call(delete("/assets/" + deleted.getId()), data.o1().admin());

            assertEquals(200, response.status(), response.toString());
            assertEquals(1, count("SELECT count(*) FROM deprecation WHERE id = ?", deprecation.getId()));
            assertEquals(deprecation.getId(), jdbc.queryForObject("SELECT deprecation_id FROM asset WHERE id = ?",
                    Long.class, other.getId()));
        }

        @Test
        void e7_archivedWorkOrder_isNotUnscheduled_activeOneIs() {
            CommonData data = data();
            WorkOrder active = records.workOrder(data.o1().company(), null, Status.OPEN, false);
            WorkOrder archived = records.workOrder(data.o1().company(), null, Status.OPEN, false);
            jdbc.update("UPDATE work_order SET archived = true WHERE id = ?", archived.getId());

            Response response = call(get("/workload/unscheduled"), data.o1().admin());

            assertEquals(200, response.status(), response.toString());
            List<Long> unscheduled = ids(json(response).get("workOrders"));
            assertTrue(unscheduled.contains(active.getId()));
            assertFalse(unscheduled.contains(archived.getId()));
        }

        @Test
        void d8_overview_leavesOutTheArchivedWorkOrders() {
            CommonData data = data();
            WorkOrder active = plannedWorkOrder(data, false);
            WorkOrder archived = plannedWorkOrder(data, true);

            Response response = call(get("/workload/overview").param("startDate", "2026-11-01")
                    .param("endDate", "2026-11-07").param("userIds", data.o1().admin().getId().toString()),
                    data.o1().admin());

            assertEquals(200, response.status(), response.toString());
            List<Long> planned = new ArrayList<>();
            json(response).get("days").forEach(day -> day.get("users").forEach(user ->
                    planned.addAll(ids(user.get("workOrders")))));
            assertTrue(planned.contains(active.getId()), response.toString());
            assertFalse(planned.contains(archived.getId()), response.toString());
        }
    }

    // =====================================================================================================
    // B6 · D-1 (C-1): jobs; D-2: automations
    // =====================================================================================================

    private static JobExecutionContext jobContext(long scheduleId) {
        JobDataMap jobDataMap = new JobDataMap();
        jobDataMap.put("scheduleId", scheduleId);
        JobExecutionContext context = mock(JobExecutionContext.class);
        when(context.getMergedJobDataMap()).thenReturn(jobDataMap);
        return context;
    }

    private long createdPreventiveMaintenance(Asset asset, String recurrenceBasedOn, User user) {
        String name = TestRecords.unique("P-14b PM");
        Response created = send(post("/preventive-maintenances"), "{\"name\":\"" + name + "\",\"title\":\"" + name
                + "\",\"frequency\":1,\"recurrenceType\":\"DAILY\",\"recurrenceBasedOn\":\"" + recurrenceBasedOn
                + "\",\"startsOn\":\"2099-01-01T00:00:00.000+00:00\",\"asset\":" + ref(asset) + "}", user);
        assertEquals(200, created.status(), created.toString());
        return json(created).get("id").asLong();
    }

    private long scheduleOf(long preventiveMaintenance) {
        return jdbc.queryForObject("SELECT schedule_id FROM preventive_maintenance WHERE id = ?", Long.class,
                preventiveMaintenance);
    }

    private long workOrdersOf(long preventiveMaintenance) {
        return count("SELECT count(*) FROM work_order WHERE parent_preventive_maintenance_id = ?",
                preventiveMaintenance);
    }

    private List<JobKey> chainedJobs(long scheduleId) throws SchedulerException {
        return scheduler.getJobKeys(GroupMatcher.jobGroupEquals("wo-group")).stream()
                .filter(key -> key.getName().startsWith("wo-job-chained-" + scheduleId + "-")).toList();
    }

    private void restore(Asset asset) {
        jdbc.update("UPDATE asset SET archived = false WHERE id = ?", asset.getId());
    }

    private Workflow workflowAssigning(Company company, Asset asset, WFMainCondition mainCondition) {
        WorkflowAction action = new WorkflowAction();
        action.setCompany(company);
        if (mainCondition == WFMainCondition.REQUEST_CREATED) action.setRequestAction(RequestAction.ASSIGN_ASSET);
        else action.setWorkOrderAction(WorkOrderAction.ASSIGN_ASSET);
        action.setAsset(asset);
        Workflow workflow = new Workflow();
        workflow.setCompany(company);
        workflow.setTitle(TestRecords.unique("P-14b workflow"));
        workflow.setMainCondition(mainCondition);
        workflow.setAction(action);
        return workflowRepository.saveAndFlush(workflow);
    }

    @Nested
    class JobsAndAutomations {

        @Test
        void c1_scheduledPreventiveMaintenanceOfArchivedAsset_noWorkOrder_scheduleKept_afterRestoring_generated()
                throws Exception {
            CommonData data = data();
            Asset asset = records.asset(data.o1().company());
            long preventiveMaintenance = createdPreventiveMaintenance(asset, "SCHEDULED_DATE", data.o1().admin());
            long schedule = scheduleOf(preventiveMaintenance);
            archive(asset);

            workOrderCreationJob.executeInternal(jobContext(schedule));

            assertEquals(0, workOrdersOf(preventiveMaintenance));
            assertEquals(Boolean.FALSE, jdbc.queryForObject("SELECT disabled FROM schedule WHERE id = ?",
                    Boolean.class, schedule));

            restore(asset);
            workOrderCreationJob.executeInternal(jobContext(schedule));

            assertEquals(1, workOrdersOf(preventiveMaintenance));
            assertEquals(asset.getId(), jdbc.queryForObject("SELECT asset_id FROM work_order "
                    + "WHERE parent_preventive_maintenance_id = ?", Long.class, preventiveMaintenance));
        }

        @Test
        void c1_completionBasedPreventiveMaintenanceOfArchivedAsset_noWorkOrder_nextOccurrenceChained()
                throws Exception {
            CommonData data = data();
            Asset asset = records.asset(data.o1().company());
            long preventiveMaintenance = createdPreventiveMaintenance(asset, "COMPLETED_DATE", data.o1().admin());
            long schedule = scheduleOf(preventiveMaintenance);
            archive(asset);
            try {
                workOrderCreationJob.executeInternal(jobContext(schedule));

                assertEquals(0, workOrdersOf(preventiveMaintenance));
                assertEquals(1, chainedJobs(schedule).size());
            } finally {
                for (JobKey key : chainedJobs(schedule)) scheduler.deleteJob(key);
            }
        }

        @Test
        void c1_readingThatFiresATriggerOfArchivedAsset_readingSaved_noWorkOrder_afterRestoring_generated() {
            CommonData data = data();
            Company company = data.o1().company();
            Asset asset = records.asset(company);
            Meter meter = module.meter(company, records.asset(company));
            var trigger = module.trigger(meter, com.grash.model.enums.WorkOrderMeterTriggerCondition.MORE_THAN, 10);
            jdbc.update("UPDATE work_order_meter_trigger SET asset_id = ? WHERE id = ?", asset.getId(),
                    trigger.getId());
            archive(asset);

            Response first = send(post("/readings"), "{\"value\":98,\"meter\":{\"id\":" + meter.getId() + "}}",
                    data.o1().admin());

            assertEquals(200, first.status(), first.toString());
            assertEquals(1, count("SELECT count(*) FROM reading WHERE meter_id = ?", meter.getId()));
            assertEquals(0, count("SELECT count(*) FROM work_order WHERE company_id = ?", company.getId()));
            assertTrue(webhookDouble.calls().isEmpty());

            restore(asset);
            // the meter takes one reading a day: the first one is moved back
            jdbc.update("UPDATE reading SET created_at = now() - interval '2 days' WHERE meter_id = ?",
                    meter.getId());
            Response second = send(post("/readings"), "{\"value\":99,\"meter\":{\"id\":" + meter.getId() + "}}",
                    data.o1().admin());

            assertEquals(200, second.status(), second.toString());
            assertEquals(1, count("SELECT count(*) FROM work_order WHERE asset_id = ?", asset.getId()));
        }

        @Test
        void d2_automationThatAssignsAnArchivedAsset_skipsTheAction_workOrderCreated() {
            CommonData data = data();
            Company company = data.o1().company();
            workflowAssigning(company, archivedAsset(company), WFMainCondition.WORK_ORDER_CREATED);

            Response response = send(post("/work-orders"), "{\"title\":\"" + TestRecords.unique("P-14b WO")
                    + "\"}", data.o1().admin());

            assertEquals(200, response.status(), response.toString());
            assertEquals(0, count("SELECT count(*) FROM work_order WHERE id = ? AND asset_id IS NOT NULL",
                    json(response).get("id").asLong()));
        }

        @Test
        void d2_automationThatAssignsAnArchivedAsset_skipsTheAction_requestCreated() {
            CommonData data = data();
            Company company = data.o1().company();
            workflowAssigning(company, archivedAsset(company), WFMainCondition.REQUEST_CREATED);

            Response response = send(post("/requests"), "{\"title\":\"" + TestRecords.unique("P-14b request")
                    + "\"}", data.o1().admin());

            assertEquals(200, response.status(), response.toString());
            assertEquals(0, count("SELECT count(*) FROM request WHERE id = ? AND asset_id IS NOT NULL",
                    json(response).get("id").asLong()));
        }

        @Test
        void d2_automationThatSetsTheStatusOfAnArchivedAsset_skipsTheAction_taskUpdated() {
            CommonData data = data();
            Company company = data.o1().company();
            Asset asset = archivedAsset(company);
            WorkOrder workOrder = records.workOrder(company, asset, Status.OPEN, false);
            Response tasks = send(patch("/tasks/work-order/" + workOrder.getId()), "[{\"label\":\"P-14b task\","
                    + "\"taskType\":\"SUBTASK\",\"options\":[]}]", data.o1().admin());
            assertEquals(200, tasks.status(), tasks.toString());
            long task = json(tasks).get(0).get("id").asLong();
            WorkflowAction action = new WorkflowAction();
            action.setCompany(company);
            action.setTaskAction(TaskAction.SET_ASSET_STATUS);
            action.setAssetStatus(AssetStatus.DOWN);
            Workflow workflow = new Workflow();
            workflow.setCompany(company);
            workflow.setTitle(TestRecords.unique("P-14b workflow"));
            workflow.setMainCondition(WFMainCondition.TASK_UPDATED);
            workflow.setAction(action);
            workflowRepository.saveAndFlush(workflow);

            Response response = send(patch("/tasks/" + task), "{\"value\":\"COMPLETE\"}", data.o1().admin());

            assertEquals(200, response.status(), response.toString());
            assertEquals(AssetStatus.OPERATIONAL.ordinal(), status(asset));
        }
    }
}