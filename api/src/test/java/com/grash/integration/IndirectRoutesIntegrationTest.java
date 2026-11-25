package com.grash.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grash.dto.imports.AssetImportDTO;
import com.grash.dto.imports.ImportRejection;
import com.grash.dto.imports.ImportResponse;
import com.grash.dto.imports.LocationImportDTO;
import com.grash.dto.imports.MeterImportDTO;
import com.grash.dto.imports.PartImportDTO;
import com.grash.dto.imports.PreventiveMaintenanceImportDTO;
import com.grash.dto.imports.WorkOrderImportDTO;
import com.grash.integration.support.FaultInjector;
import com.grash.integration.support.FaultInjector.Fault;
import com.grash.integration.support.FaultInjector.Operation;
import com.grash.integration.support.TestApi;
import com.grash.integration.support.TestApi.Response;
import com.grash.integration.support.TestAssetRecords;
import com.grash.integration.support.TestOrganizations;
import com.grash.integration.support.TestOrganizations.CommonData;
import com.grash.integration.support.TestRecords;
import com.grash.model.Asset;
import com.grash.model.Company;
import com.grash.model.Customer;
import com.grash.model.Location;
import com.grash.model.Meter;
import com.grash.model.Part;
import com.grash.model.PartQuantity;
import com.grash.model.PurchaseOrder;
import com.grash.model.Task;
import com.grash.model.TaskBase;
import com.grash.model.Vendor;
import com.grash.model.WorkOrder;
import com.grash.model.Workflow;
import com.grash.model.WorkflowAction;
import com.grash.model.WorkflowCondition;
import com.grash.model.enums.Priority;
import com.grash.model.enums.workflow.WFMainCondition;
import com.grash.model.enums.workflow.WorkOrderAction;
import com.grash.model.enums.workflow.WorkOrderCondition;
import com.grash.model.enums.ApprovalStatus;
import com.grash.model.enums.Status;
import com.grash.model.User;
import com.grash.repository.CustomerRepository;
import com.grash.repository.PartQuantityRepository;
import com.grash.repository.PurchaseOrderRepository;
import com.grash.repository.TaskRepository;
import com.grash.repository.VendorRepository;
import com.grash.repository.WorkflowRepository;
import com.grash.service.ImportService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;

/**
 * P-18 (PPB-08, PPB-20, PPB-23, PPB-26, PPB-27): the indirect routes. The imports neither delete rows nor change a
 * retired record, and reject a row that gives a disabled user or an archived asset as a new reference, with its
 * reason, while the other rows are imported; the demo purge needs the view of settings, deletes only demo records, is
 * rejected whole with {@code DEMO_DATA_IN_USE} when a record that is not demo depends on a demo one, and is recorded;
 * deleting an automation is recorded; the "request rejected" automations run before the request is closed.
 */
@AutoConfigureMockMvc
class IndirectRoutesIntegrationTest extends AbstractIntegrationTest {

    static final String ASSET_RETIRED = "ASSET_RETIRED";
    static final String USER_RETIRED = "USER_RETIRED";

    private final TestOrganizations organizations;
    private final TestRecords records;
    private final TestAssetRecords assetRecords;
    private final TestApi api;
    private final JdbcTemplate jdbc;
    private final ImportService importService;
    private final ObjectMapper objectMapper;
    private final CustomerRepository customerRepository;
    private final TaskRepository taskRepository;
    private final PartQuantityRepository partQuantityRepository;
    private final VendorRepository vendorRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final WorkflowRepository workflowRepository;
    private final FaultInjector faults;
    private final List<CommonData> created = new ArrayList<>();

    @Autowired
    IndirectRoutesIntegrationTest(TestOrganizations organizations, TestRecords records, TestAssetRecords assetRecords,
                                  MockMvc mockMvc, ObjectMapper objectMapper, JdbcTemplate jdbc,
                                  ImportService importService, CustomerRepository customerRepository,
                                  TaskRepository taskRepository, PartQuantityRepository partQuantityRepository,
                                  VendorRepository vendorRepository,
                                  PurchaseOrderRepository purchaseOrderRepository,
                                  WorkflowRepository workflowRepository) {
        this.organizations = organizations;
        this.records = records;
        this.assetRecords = assetRecords;
        this.api = new TestApi(mockMvc, objectMapper);
        this.jdbc = jdbc;
        this.importService = importService;
        this.objectMapper = objectMapper;
        this.customerRepository = customerRepository;
        this.taskRepository = taskRepository;
        this.partQuantityRepository = partQuantityRepository;
        this.vendorRepository = vendorRepository;
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.workflowRepository = workflowRepository;
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

    // --- records and queries ---

    private User disabled(User user) {
        jdbc.update("UPDATE own_user SET enabled = false WHERE id = ?", user.getId());
        return user;
    }

    private Asset archived(Asset asset) {
        jdbc.update("UPDATE asset SET archived = true WHERE id = ?", asset.getId());
        return asset;
    }

    private void associate(String table, String ownerColumn, Long ownerId, String otherColumn, Long otherId) {
        jdbc.update("INSERT INTO " + table + " (" + ownerColumn + ", " + otherColumn + ") VALUES (?, ?)", ownerId,
                otherId);
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private List<Long> linked(String table, String ownerColumn, Long ownerId, String otherColumn) {
        return jdbc.queryForList("SELECT " + otherColumn + " FROM " + table + " WHERE " + ownerColumn + " = ? ORDER BY "
                + otherColumn, Long.class, ownerId);
    }

    private Long reference(String table, String column, Long id) {
        return jdbc.queryForObject("SELECT " + column + " FROM " + table + " WHERE id = ?", Long.class, id);
    }

    private Map<String, Object> row(String table, Long id) {
        return jdbc.queryForMap("SELECT * FROM " + table + " WHERE id = ?", id);
    }

    private Long idNamed(String table, Company company, String name) {
        List<Long> ids = jdbc.queryForList("SELECT id FROM " + table + " WHERE company_id = ? AND name = ?",
                Long.class, company.getId(), name);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private long auditRecords(Company company) {
        return count("SELECT count(*) FROM audit_log WHERE company_id = ?", company.getId());
    }

    private Response call(MockHttpServletRequestBuilder request, User user) {
        return api.call(request, api.login(user));
    }

    private static void assertRejected(ImportRejection rejection, int row, Long id, String code) {
        assertEquals(List.of(row, String.valueOf(id), code), List.of(rejection.getRow(),
                String.valueOf(rejection.getId()), rejection.getCode()), rejection.toString());
        assertFalse(rejection.getMessage() == null || rejection.getMessage().isBlank(), rejection.toString());
    }

    // =================================================================================================================
    // B1 · Import of assets (E-1, D-1, E-2)
    // =================================================================================================================

    @Nested
    class AssetImport {

        @Test
        void ca055_existingAndNewRow_oneUpdatedOneCreated_noRowDeleted() {
            // passed before P-18
            CommonData data = data();
            Company company = data.o1().company();
            Asset existing = records.asset(company);
            String newName = TestRecords.unique("P-18 new asset");
            long assets = count("SELECT count(*) FROM asset WHERE company_id = ?", company.getId());

            ImportResponse response = importService.importAssets(List.of(
                    AssetImportDTO.builder().id(existing.getId()).name("P-18 updated").build(),
                    AssetImportDTO.builder().name(newName).build()), company);

            assertEquals(List.of(1, 1), List.of(response.getCreated(), response.getUpdated()));
            assertEquals(List.of(), response.getRejected());
            assertEquals("P-18 updated", row("asset", existing.getId()).get("name"));
            assertNotNull(idNamed("asset", company, newName));
            assertEquals(assets + 1, count("SELECT count(*) FROM asset WHERE company_id = ?", company.getId()),
                    "no asset deleted");
        }

        @Test
        void ca133_rowUpdatesAnArchivedAsset_rejectedWithItsReason_assetUnchanged_otherRowsImported() {
            // before P-18: the archived asset changed and the file restored it
            CommonData data = data();
            Company company = data.o1().company();
            Asset archivedAsset = archived(records.asset(company));
            Asset active = records.asset(company);
            String newName = TestRecords.unique("P-18 new asset");
            Map<String, Object> before = row("asset", archivedAsset.getId());
            long audit = auditRecords(company);

            ImportResponse response = importService.importAssets(List.of(
                    AssetImportDTO.builder().id(archivedAsset.getId()).name("P-18 changed").build(),
                    AssetImportDTO.builder().id(active.getId()).name("P-18 active").build(),
                    AssetImportDTO.builder().name(newName).build()), company);

            assertEquals(1, response.getRejected().size(), response.toString());
            assertRejected(response.getRejected().get(0), 1, archivedAsset.getId(), ASSET_RETIRED);
            assertTrue(response.getRejected().get(0).getMessage().startsWith("This asset is archived"));
            assertEquals(before, row("asset", archivedAsset.getId()), "the archived asset did not change");
            assertEquals(List.of(1, 1), List.of(response.getCreated(), response.getUpdated()));
            assertEquals("P-18 active", row("asset", active.getId()).get("name"));
            assertNotNull(idNamed("asset", company, newName));
            assertEquals(audit, auditRecords(company));
        }

        @Test
        void d1_archivedColumn_ignored_neitherArchivesNorCreatesArchived() {
            // before P-18: the column archived the asset without audit record
            CommonData data = data();
            Company company = data.o1().company();
            Asset active = records.asset(company);
            String newName = TestRecords.unique("P-18 new asset");
            long audit = auditRecords(company);

            ImportResponse response = importService.importAssets(List.of(
                    AssetImportDTO.builder().id(active.getId()).name(active.getName()).archived("Yes").build(),
                    AssetImportDTO.builder().name(newName).archived("true").build()), company);

            assertEquals(List.of(), response.getRejected());
            assertEquals(false, row("asset", active.getId()).get("archived"));
            assertEquals(false, row("asset", idNamed("asset", company, newName)).get("archived"));
            assertEquals(audit, auditRecords(company));
        }

        @Test
        void disabledUserAsANewReference_rowRejected_otherRowsImported() {
            // before P-18: accepted
            CommonData data = data();
            Company company = data.o1().company();
            User technician = disabled(data.o1().technician());
            Asset existing = records.asset(company);
            String asPrimary = TestRecords.unique("P-18 primary");
            String fine = TestRecords.unique("P-18 fine");

            ImportResponse response = importService.importAssets(List.of(
                    AssetImportDTO.builder().name(asPrimary).primaryUserEmail(technician.getEmail()).build(),
                    AssetImportDTO.builder().id(existing.getId()).name("P-18 changed")
                            .assignedToEmails(List.of(technician.getEmail())).build(),
                    AssetImportDTO.builder().name(fine).primaryUserEmail(data.o1().admin().getEmail()).build()),
                    company);

            assertEquals(2, response.getRejected().size(), response.toString());
            assertRejected(response.getRejected().get(0), 1, null, USER_RETIRED);
            assertRejected(response.getRejected().get(1), 2, existing.getId(), USER_RETIRED);
            assertNull(idNamed("asset", company, asPrimary));
            assertEquals(existing.getName(), row("asset", existing.getId()).get("name"));
            assertEquals(List.of(), linked("t_asset_user_associations", "id_asset", existing.getId(), "id_user"));
            assertEquals(data.o1().admin().getId(), reference("asset", "primary_user_id",
                    idNamed("asset", company, fine)));
            assertEquals(List.of(1, 0), List.of(response.getCreated(), response.getUpdated()));
        }

        @Test
        void archivedParentAsANewReference_rowRejected() {
            // before P-18: accepted
            CommonData data = data();
            Company company = data.o1().company();
            Asset parent = archived(records.asset(company));
            String name = TestRecords.unique("P-18 child");

            ImportResponse response = importService.importAssets(List.of(
                    AssetImportDTO.builder().name(name).parentAssetName(parent.getName()).build()), company);

            assertEquals(1, response.getRejected().size(), response.toString());
            assertRejected(response.getRejected().get(0), 1, null, ASSET_RETIRED);
            assertNull(idNamed("asset", company, name));
        }

        @Test
        void d2_referencesTheAssetAlreadyHas_kept_rowImported() {
            CommonData data = data();
            Company company = data.o1().company();
            User technician = data.o1().technician();
            Asset parent = records.asset(company);
            Asset asset = records.asset(company);
            jdbc.update("UPDATE asset SET primary_user_id = ?, parent_asset_id = ? WHERE id = ?", technician.getId(),
                    parent.getId(), asset.getId());
            associate("t_asset_user_associations", "id_asset", asset.getId(), "id_user", technician.getId());
            disabled(technician);
            archived(parent);

            ImportResponse response = importService.importAssets(List.of(AssetImportDTO.builder().id(asset.getId())
                    .name("P-18 kept").primaryUserEmail(technician.getEmail())
                    .assignedToEmails(List.of(technician.getEmail())).parentAssetName(parent.getName()).build()),
                    company);

            assertEquals(List.of(), response.getRejected());
            assertEquals("P-18 kept", row("asset", asset.getId()).get("name"));
            assertEquals(technician.getId(), reference("asset", "primary_user_id", asset.getId()));
            assertEquals(parent.getId(), reference("asset", "parent_asset_id", asset.getId()));
        }

        @Test
        void rejectedRow_numberedAsInTheFile_evenWhenTheHierarchyReordersIt() {
            CommonData data = data();
            Company company = data.o1().company();
            User technician = disabled(data.o1().technician());
            String child = TestRecords.unique("P-18 child");
            String parent = TestRecords.unique("P-18 parent");

            ImportResponse response = importService.importAssets(List.of(
                    AssetImportDTO.builder().name(child).parentAssetName(parent).build(),
                    AssetImportDTO.builder().name(parent).primaryUserEmail(technician.getEmail()).build()), company);

            assertEquals(1, response.getRejected().size(), response.toString());
            assertRejected(response.getRejected().get(0), 2, null, USER_RETIRED);
            assertNull(idNamed("asset", company, parent));
            assertNull(reference("asset", "parent_asset_id", idNamed("asset", company, child)));
        }

        @Test
        void otherOrganization_disabledUserAndArchivedAsset_ignoredAsToday_never409() {
            CommonData data = data();
            Company company = data.o1().company();
            User foreignUser = disabled(data.o2().admin());
            Asset foreignAsset = archived(records.asset(data.o2().company()));
            Map<String, Object> foreignBefore = row("asset", foreignAsset.getId());
            String name = TestRecords.unique("P-18 asset");

            ImportResponse response = importService.importAssets(List.of(
                    AssetImportDTO.builder().name(name).primaryUserEmail(foreignUser.getEmail())
                            .parentAssetName(foreignAsset.getName()).build(),
                    AssetImportDTO.builder().id(foreignAsset.getId()).name("P-18 foreign").build()), company);

            assertEquals(List.of(), response.getRejected(), "nothing of O2 is disclosed");
            Long id = idNamed("asset", company, name);
            assertNull(reference("asset", "primary_user_id", id));
            assertNull(reference("asset", "parent_asset_id", id));
            assertEquals(foreignBefore, row("asset", foreignAsset.getId()), "the asset of O2 did not change");
        }
    }

    // =================================================================================================================
    // B2 · References given by the other imports (E-2, D-P18-1)
    // =================================================================================================================

    private PreventiveMaintenanceImportDTO.PreventiveMaintenanceImportDTOBuilder<?, ?> pmRow(Long id, String name) {
        return PreventiveMaintenanceImportDTO.builder().id(id).name(name).title(name).frequency(1)
                .recurrenceType("DAILY").recurrenceBasedOn("SCHEDULED_DATE")
                // 2099-12-31 as an Excel date: the scheduler must not fire it during the test
                .startsOn(73050.0);
    }

    private Long preventiveMaintenance(Company company, String name) {
        importService.importPreventiveMaintenances(List.of(pmRow(null, name).build()), company);
        return idNamed("preventive_maintenance", company, name);
    }

    private WorkOrderImportDTO.WorkOrderImportDTOBuilder<?, ?> woRow(Long id, String title) {
        return WorkOrderImportDTO.builder().id(id).title(title).status("Open");
    }

    private Long workOrderTitled(Company company, String title) {
        List<Long> ids = jdbc.queryForList("SELECT id FROM work_order WHERE company_id = ? AND title = ?", Long.class,
                company.getId(), title);
        return ids.isEmpty() ? null : ids.get(0);
    }

    @Nested
    class LocationImport {

        @Test
        void disabledWorkerAsANewReference_rowRejected_otherRowsImported() {
            // before P-18: accepted
            CommonData data = data();
            Company company = data.o1().company();
            User technician = disabled(data.o1().technician());
            Location location = records.location(company);
            String fine = TestRecords.unique("P-18 location");

            ImportResponse response = importService.importLocations(List.of(
                    LocationImportDTO.builder().name(fine).build(),
                    LocationImportDTO.builder().id(location.getId()).name("P-18 changed")
                            .workersEmails(List.of(technician.getEmail())).build()), company);

            assertEquals(1, response.getRejected().size(), response.toString());
            assertRejected(response.getRejected().get(0), 2, location.getId(), USER_RETIRED);
            assertEquals(location.getName(), row("location", location.getId()).get("name"));
            assertEquals(List.of(), linked("t_location_user_associations", "id_location", location.getId(),
                    "id_user"));
            assertNotNull(idNamed("location", company, fine));
        }

        @Test
        void d2_workerItAlreadyHas_kept() {
            CommonData data = data();
            Location location = records.location(data.o1().company());
            User technician = data.o1().technician();
            associate("t_location_user_associations", "id_location", location.getId(), "id_user", technician.getId());
            disabled(technician);

            ImportResponse response = importService.importLocations(List.of(LocationImportDTO.builder()
                    .id(location.getId()).name("P-18 kept").workersEmails(List.of(technician.getEmail())).build()),
                    data.o1().company());

            assertEquals(List.of(), response.getRejected());
            assertEquals("P-18 kept", row("location", location.getId()).get("name"));
        }

        @Test
        void otherOrganization_disabledUser_ignored_never409() {
            CommonData data = data();
            User foreign = disabled(data.o2().admin());
            String name = TestRecords.unique("P-18 location");

            ImportResponse response = importService.importLocations(List.of(LocationImportDTO.builder().name(name)
                    .workersEmails(List.of(foreign.getEmail())).build()), data.o1().company());

            assertEquals(List.of(), response.getRejected());
            assertEquals(List.of(), linked("t_location_user_associations", "id_location",
                    idNamed("location", data.o1().company(), name), "id_user"));
        }
    }

    @Nested
    class MeterImport {

        @Test
        void disabledUserAndArchivedAsset_rowsRejected_otherRowsImported() {
            // before P-18: accepted
            CommonData data = data();
            Company company = data.o1().company();
            Asset asset = records.asset(company);
            Meter meter = assetRecords.meter(company, asset);
            User technician = disabled(data.o1().technician());
            Asset archivedAsset = archived(records.asset(company));
            String byAsset = TestRecords.unique("P-18 meter");
            String fine = TestRecords.unique("P-18 meter");

            ImportResponse response = importService.importMeters(List.of(
                    MeterImportDTO.builder().id(meter.getId()).name("P-18 changed").updateFrequency(1)
                            .assetName(asset.getName()).usersEmails(List.of(technician.getEmail())).build(),
                    MeterImportDTO.builder().name(byAsset).updateFrequency(1).assetName(archivedAsset.getName())
                            .build(),
                    MeterImportDTO.builder().name(fine).updateFrequency(1).assetName(asset.getName()).build()),
                    company);

            assertEquals(2, response.getRejected().size(), response.toString());
            assertRejected(response.getRejected().get(0), 1, meter.getId(), USER_RETIRED);
            assertRejected(response.getRejected().get(1), 2, null, ASSET_RETIRED);
            assertEquals(meter.getName(), row("meter", meter.getId()).get("name"));
            assertNull(idNamed("meter", company, byAsset));
            assertNotNull(idNamed("meter", company, fine));
            assertEquals(List.of(1, 0), List.of(response.getCreated(), response.getUpdated()));
        }

        @Test
        void d2_userAndAssetItAlreadyHas_kept() {
            CommonData data = data();
            Company company = data.o1().company();
            Asset asset = records.asset(company);
            Meter meter = assetRecords.meter(company, asset);
            User technician = data.o1().technician();
            associate("t_meter_user_associations", "id_meter", meter.getId(), "id_user", technician.getId());
            disabled(technician);
            archived(asset);

            ImportResponse response = importService.importMeters(List.of(MeterImportDTO.builder().id(meter.getId())
                    .name("P-18 kept").updateFrequency(1).assetName(asset.getName())
                    .usersEmails(List.of(technician.getEmail())).build()), company);

            assertEquals(List.of(), response.getRejected());
            assertEquals("P-18 kept", row("meter", meter.getId()).get("name"));
        }

        @Test
        void otherOrganization_disabledUserAndArchivedAsset_ignored_never409() {
            CommonData data = data();
            Company company = data.o1().company();
            Meter meter = assetRecords.meter(company, records.asset(company));
            User foreign = disabled(data.o2().admin());
            Asset foreignAsset = archived(records.asset(data.o2().company()));

            ImportResponse response = importService.importMeters(List.of(MeterImportDTO.builder().id(meter.getId())
                    .name(meter.getName()).updateFrequency(1).assetName(foreignAsset.getName())
                    .usersEmails(List.of(foreign.getEmail())).build()), company);

            assertEquals(List.of(), response.getRejected());
            assertNotEquals(foreignAsset.getId(), reference("meter", "asset_id", meter.getId()));
        }
    }

    @Nested
    class PartImport {

        @Test
        void disabledUserAsANewReference_rowRejected_otherRowsImported() {
            // before P-18: accepted (TD-P16-05)
            CommonData data = data();
            Company company = data.o1().company();
            Part part = records.part(company, null);
            User technician = disabled(data.o1().technician());
            String fine = TestRecords.unique("P-18 part");

            ImportResponse response = importService.importParts(List.of(
                    PartImportDTO.builder().id(part.getId()).name("P-18 changed")
                            .assignedToEmails(List.of(technician.getEmail())).build(),
                    PartImportDTO.builder().name(fine).build()), company, data.o1().admin());

            assertEquals(1, response.getRejected().size(), response.toString());
            assertRejected(response.getRejected().get(0), 1, part.getId(), USER_RETIRED);
            assertEquals(part.getName(), row("part", part.getId()).get("name"));
            assertEquals(List.of(), linked("t_part_user_associations", "id_part", part.getId(), "id_user"));
            assertNotNull(idNamed("part", company, fine));
        }

        @Test
        void d2_userItAlreadyHas_kept() {
            CommonData data = data();
            Part part = records.part(data.o1().company(), null);
            User technician = data.o1().technician();
            associate("t_part_user_associations", "id_part", part.getId(), "id_user", technician.getId());
            disabled(technician);

            ImportResponse response = importService.importParts(List.of(PartImportDTO.builder().id(part.getId())
                    .name("P-18 kept").assignedToEmails(List.of(technician.getEmail())).build()),
                    data.o1().company(), data.o1().admin());

            assertEquals(List.of(), response.getRejected());
            assertEquals("P-18 kept", row("part", part.getId()).get("name"));
        }

        @Test
        void otherOrganization_disabledUser_ignored_never409() {
            CommonData data = data();
            Part part = records.part(data.o1().company(), null);
            User foreign = disabled(data.o2().admin());

            ImportResponse response = importService.importParts(List.of(PartImportDTO.builder().id(part.getId())
                    .name(part.getName()).assignedToEmails(List.of(foreign.getEmail())).build()),
                    data.o1().company(), data.o1().admin());

            assertEquals(List.of(), response.getRejected());
            assertEquals(List.of(), linked("t_part_user_associations", "id_part", part.getId(), "id_user"));
        }
    }

    @Nested
    class PreventiveMaintenanceImport {

        @Test
        void disabledUserAndArchivedAsset_rowsRejected_otherRowsImported() {
            // before P-18: accepted
            CommonData data = data();
            Company company = data.o1().company();
            String name = TestRecords.unique("P-18 PM");
            Long id = preventiveMaintenance(company, name);
            User technician = disabled(data.o1().technician());
            Asset asset = archived(records.asset(company));
            String byAsset = TestRecords.unique("P-18 PM");
            String fine = TestRecords.unique("P-18 PM");

            ImportResponse response = importService.importPreventiveMaintenances(List.of(
                    pmRow(id, name).title("P-18 changed").assignedToEmails(List.of(technician.getEmail())).build(),
                    pmRow(null, byAsset).assetName(asset.getName()).build(),
                    pmRow(null, fine).primaryUserEmail(data.o1().admin().getEmail()).build()), company);

            assertEquals(2, response.getRejected().size(), response.toString());
            assertRejected(response.getRejected().get(0), 1, id, USER_RETIRED);
            assertRejected(response.getRejected().get(1), 2, null, ASSET_RETIRED);
            assertEquals(name, row("preventive_maintenance", id).get("title"));
            assertNull(idNamed("preventive_maintenance", company, byAsset));
            assertNotNull(idNamed("preventive_maintenance", company, fine));
            assertEquals(List.of(1, 0), List.of(response.getCreated(), response.getUpdated()));
        }

        @Test
        void d2_usersAndAssetItAlreadyHas_kept() {
            CommonData data = data();
            Company company = data.o1().company();
            String name = TestRecords.unique("P-18 PM");
            Long id = preventiveMaintenance(company, name);
            User technician = data.o1().technician();
            Asset asset = records.asset(company);
            jdbc.update("UPDATE preventive_maintenance SET primary_user_id = ?, asset_id = ? WHERE id = ?",
                    technician.getId(), asset.getId(), id);
            disabled(technician);
            archived(asset);

            ImportResponse response = importService.importPreventiveMaintenances(List.of(pmRow(id, name)
                    .title("P-18 kept").primaryUserEmail(technician.getEmail()).assetName(asset.getName()).build()),
                    company);

            assertEquals(List.of(), response.getRejected());
            assertEquals("P-18 kept", row("preventive_maintenance", id).get("title"));
        }

        @Test
        void otherOrganization_disabledUserAndArchivedAsset_ignored_never409() {
            CommonData data = data();
            Company company = data.o1().company();
            String name = TestRecords.unique("P-18 PM");
            User foreign = disabled(data.o2().admin());
            Asset foreignAsset = archived(records.asset(data.o2().company()));

            ImportResponse response = importService.importPreventiveMaintenances(List.of(pmRow(null, name)
                    .primaryUserEmail(foreign.getEmail()).assetName(foreignAsset.getName()).build()), company);

            assertEquals(List.of(), response.getRejected());
            assertNull(reference("preventive_maintenance", "asset_id", idNamed("preventive_maintenance", company,
                    name)));
        }
    }

    @Nested
    class WorkOrderImport {

        @Test
        void dP18_1_disabledUserAndArchivedAsset_rowsRejected_otherRowsImported() {
            // before P-18: accepted
            CommonData data = data();
            Company company = data.o1().company();
            WorkOrder workOrder = records.workOrder(company, null, Status.OPEN, false);
            User technician = disabled(data.o1().technician());
            Asset asset = archived(records.asset(company));
            String byAsset = TestRecords.unique("P-18 WO");
            String fine = TestRecords.unique("P-18 WO");

            ImportResponse response = importService.importWorkOrders(List.of(
                    woRow(workOrder.getId(), "P-18 changed").primaryUserEmail(technician.getEmail()).build(),
                    woRow(null, byAsset).assetName(asset.getName()).build(),
                    woRow(null, fine).assignedToEmails(List.of(data.o1().admin().getEmail())).build()), company,
                    data.o1().admin());

            assertEquals(2, response.getRejected().size(), response.toString());
            assertRejected(response.getRejected().get(0), 1, workOrder.getId(), USER_RETIRED);
            assertRejected(response.getRejected().get(1), 2, null, ASSET_RETIRED);
            assertEquals(workOrder.getTitle(), row("work_order", workOrder.getId()).get("title"));
            assertNull(workOrderTitled(company, byAsset));
            assertNotNull(workOrderTitled(company, fine));
            assertEquals(List.of(1, 0), List.of(response.getCreated(), response.getUpdated()));
        }

        @Test
        void d2_usersAndAssetItAlreadyHas_kept() {
            CommonData data = data();
            Company company = data.o1().company();
            Asset asset = records.asset(company);
            WorkOrder workOrder = records.workOrder(company, asset, Status.OPEN, false);
            User technician = data.o1().technician();
            associate("work_order_assigned_to", "work_order_id", workOrder.getId(), "assigned_to_id",
                    technician.getId());
            disabled(technician);
            archived(asset);

            ImportResponse response = importService.importWorkOrders(List.of(woRow(workOrder.getId(), "P-18 kept")
                    .assignedToEmails(List.of(technician.getEmail())).assetName(asset.getName()).build()), company,
                    data.o1().admin());

            assertEquals(List.of(), response.getRejected());
            assertEquals("P-18 kept", row("work_order", workOrder.getId()).get("title"));
        }

        @Test
        void otherOrganization_disabledUserAndArchivedAsset_ignored_never409() {
            CommonData data = data();
            Company company = data.o1().company();
            String title = TestRecords.unique("P-18 WO");
            User foreign = disabled(data.o2().admin());
            Asset foreignAsset = archived(records.asset(data.o2().company()));

            ImportResponse response = importService.importWorkOrders(List.of(woRow(null, title)
                    .primaryUserEmail(foreign.getEmail()).assetName(foreignAsset.getName()).build()), company,
                    data.o1().admin());

            assertEquals(List.of(), response.getRejected());
            assertNull(reference("work_order", "asset_id", workOrderTitled(company, title)));
        }
    }

    // =================================================================================================================
    // B3 · Demo purge: permission, record, normal case, all or nothing, isolation (E-4, E-6, E-7)
    // =================================================================================================================

    static final String DEMO_DATA_IN_USE = "DEMO_DATA_IN_USE";

    private void demo(String table, Long id) {
        jdbc.update("UPDATE " + table + " SET is_demo = true WHERE id = ?", id);
    }

    private Response purge(User user) {
        return call(delete("/demo/demo-data"), user);
    }

    /**
     * A part with {@code quantity} in stock and its movement, as the ledger leaves it.
     */
    private Part partWithStock(Company company, double quantity) {
        Part part = records.part(company, null);
        jdbc.update("UPDATE part SET quantity = ? WHERE id = ?", quantity, part.getId());
        jdbc.update("INSERT INTO part_transaction (id, created_at, updated_at, company_id, part_id, quantity, "
                + "origin, is_demo) VALUES (nextval('part_transaction_seq'), now(), now(), ?, ?, ?, "
                + "'ADJUSTMENT', false)", company.getId(), part.getId(), quantity);
        return part;
    }

    /**
     * Demo rows of the company left in the tables with {@code is_demo} and {@code company_id}.
     */
    private long demoRows(Company company) {
        List<String> tables = jdbc.queryForList("SELECT d.table_name FROM information_schema.columns d "
                + "JOIN information_schema.columns c ON c.table_schema = d.table_schema AND c.table_name = d.table_name "
                + "AND c.column_name = 'company_id' WHERE d.table_schema = current_schema() AND d.column_name = 'is_demo' "
                + "AND d.table_name <> 'work_order_aud'", String.class);
        return tables.stream().mapToLong(table -> count("SELECT count(*) FROM " + table
                + " WHERE is_demo AND company_id = ?", company.getId())).sum();
    }

    private JsonNode json(Response response) {
        try {
            return objectMapper.readTree(response.body());
        } catch (Exception failure) {
            throw new IllegalStateException(response.toString(), failure);
        }
    }

    private void assertDemoDataInUse(Response response, String blockingType) {
        assertEquals(409, response.status(), response.toString());
        JsonNode body = json(response);
        assertEquals(DEMO_DATA_IN_USE, body.get("code").asText(), response.toString());
        assertFalse(body.get("success").asBoolean());
        assertFalse(body.get("message").asText().isBlank());
        assertFalse(response.body().contains("constraint") || response.body().contains("SQL")
                || response.body().contains("fk_"), response.toString());
        List<String> types = new ArrayList<>();
        if (body.has("blockingTypes")) body.get("blockingTypes").forEach(type -> types.add(type.asText()));
        assertEquals(List.of(blockingType), types, response.toString());
    }

    @Nested
    class DemoPurge {

        @Test
        void ca074_technicianWithoutSettings_403_nothingChanged() {
            // before P-18: 200, the demo data deleted by any authenticated user
            CommonData data = data();
            Company company = data.o1().company();
            Asset demoAsset = records.asset(company, null, true);
            WorkOrder demoWorkOrder = records.workOrder(company, null, Status.OPEN, true);

            Response response = purge(data.o1().technician());

            assertEquals(403, response.status(), response.toString());
            assertEquals(1, count("SELECT count(*) FROM asset WHERE id = ?", demoAsset.getId()));
            assertEquals(1, count("SELECT count(*) FROM work_order WHERE id = ?", demoWorkOrder.getId()));
            assertEquals(0, auditRecords(company));
        }

        @Test
        void ca057_onlyDemoData_withHierarchiesAndMovements_200_noDemoLeft_restUnchanged_purgeRecordByTable() {
            // before P-18: 200 without audit record. D-P18-3: without readings (D-P14-9)
            CommonData data = data();
            Company company = data.o1().company();
            Location parentLocation = records.location(company);
            Location childLocation = records.location(company);
            demo("location", parentLocation.getId());
            demo("location", childLocation.getId());
            jdbc.update("UPDATE location SET parent_location_id = ? WHERE id = ?", parentLocation.getId(),
                    childLocation.getId());
            Asset parentAsset = records.asset(company, null, true);
            Asset childAsset = records.asset(company, null, true);
            jdbc.update("UPDATE asset SET parent_asset_id = ?, location_id = ? WHERE id = ?", parentAsset.getId(),
                    childLocation.getId(), childAsset.getId());
            WorkOrder demoWorkOrder = records.workOrder(company, childAsset, Status.OPEN, true);
            Part demoPart = partWithStock(company, 4);
            demo("part", demoPart.getId());
            jdbc.update("UPDATE part_transaction SET is_demo = true WHERE part_id = ?", demoPart.getId());
            Asset realAsset = records.asset(company);
            WorkOrder realWorkOrder = records.workOrder(company, realAsset, Status.OPEN, false);
            Part realPart = partWithStock(company, 2);
            Map<String, Object> realAssetBefore = row("asset", realAsset.getId());
            Map<String, Object> realWorkOrderBefore = row("work_order", realWorkOrder.getId());
            Map<String, Object> realPartBefore = row("part", realPart.getId());

            Response response = purge(data.o1().admin());

            assertEquals(200, response.status(), response.toString());
            assertEquals(0, demoRows(company), "no demo record is left");
            assertEquals(realAssetBefore, row("asset", realAsset.getId()));
            assertEquals(realWorkOrderBefore, row("work_order", realWorkOrder.getId()));
            assertEquals(realPartBefore, row("part", realPart.getId()));
            assertEquals(1, count("SELECT count(*) FROM part_transaction WHERE part_id = ?", realPart.getId()));
            Map<String, Object> record = jdbc.queryForMap("SELECT action, source, entity_type, entity_id, actor_id, "
                    + "storage_objects, cascade_rows::text AS cascade FROM audit_log WHERE company_id = ?",
                    company.getId());
            assertEquals(List.of("PURGE", "DEMO_PURGE", "COMPANY", company.getId(), data.o1().admin().getId()),
                    List.of(record.get("action"), record.get("source"), record.get("entity_type"),
                            ((Number) record.get("entity_id")).longValue(),
                            ((Number) record.get("actor_id")).longValue()), record.toString());
            String cascade = (String) record.get("cascade");
            for (String deleted : List.of("\"asset\": 2", "\"location\": 2", "\"work_order\": 1", "\"part\": 1",
                    "\"part_transaction\": 1"))
                assertTrue(cascade.contains(deleted), cascade);
        }

        @Test
        void nothingToPurge_200_noRecord() {
            CommonData data = data();
            records.asset(data.o1().company());

            Response response = purge(data.o1().admin());

            assertEquals(200, response.status(), response.toString());
            assertEquals(0, auditRecords(data.o1().company()));
        }

        @Test
        void ca068_failureHalfway_error_allDemoDataKept_noRecord() {
            // passed before P-18 (one transaction); now also without audit record
            CommonData data = data();
            Company company = data.o1().company();
            WorkOrder demoWorkOrder = records.workOrder(company, null, Status.OPEN, true);
            Asset demoAsset = records.asset(company, null, true);
            Location demoLocation = records.location(company);
            demo("location", demoLocation.getId());
            long demoBefore = demoRows(company);

            Response response;
            try (Fault fault = faults.failOn("asset", Operation.DELETE)) {
                response = purge(data.o1().admin());
                assertEquals(1, fault.hits());
            }

            assertNotEquals(200, response.status(), response.toString());
            assertEquals(demoBefore, demoRows(company), "every demo record still exists");
            assertEquals(1, count("SELECT count(*) FROM work_order WHERE id = ?", demoWorkOrder.getId()));
            assertEquals(1, count("SELECT count(*) FROM asset WHERE id = ?", demoAsset.getId()));
            assertEquals(0, auditRecords(company));
        }

        @Test
        void otherOrganization_itsDemoDataAndDependentsNeitherPurgedNorBlocking() {
            CommonData data = data();
            Company o1 = data.o1().company();
            Company o2 = data.o2().company();
            Asset o1Demo = records.asset(o1, null, true);
            Asset o2Demo = records.asset(o2, null, true);
            records.workOrder(o2, o2Demo, Status.OPEN, false);
            Customer o2Customer = customer(o2);
            demo("customer", o2Customer.getId());
            WorkOrder o2Real = records.workOrder(o2, null, Status.OPEN, false);
            associate("work_order_customers", "work_order_id", o2Real.getId(), "customers_id", o2Customer.getId());

            Response response = purge(data.o1().admin());

            assertEquals(200, response.status(), response.toString());
            assertEquals(0, count("SELECT count(*) FROM asset WHERE id = ?", o1Demo.getId()));
            assertEquals(1, count("SELECT count(*) FROM asset WHERE id = ?", o2Demo.getId()));
            assertEquals(1, count("SELECT count(*) FROM customer WHERE id = ?", o2Customer.getId()));
            assertEquals(0, auditRecords(o2));
        }
    }

    // =================================================================================================================
    // B4 · Demo purge: a record that is not demo depends on a demo one (E-5, rules R1 and R2)
    // =================================================================================================================

    private Customer customer(Company company) {
        Customer customer = new Customer();
        customer.setName(TestRecords.unique("P-18 customer"));
        customer.setCompany(company);
        return customerRepository.saveAndFlush(customer);
    }

    @Nested
    class DemoPurgeDependents {

        @Test
        void ca056_taskBaseOfARealWorkOrderCitesADemoAsset_409DemoDataInUse_nothingDeleted() {
            // before P-18: 409 RECORD_IN_USE
            CommonData data = data();
            Company company = data.o1().company();
            Asset demoAsset = records.asset(company, null, true);
            WorkOrder demoWorkOrder = records.workOrder(company, null, Status.OPEN, true);
            WorkOrder real = records.workOrder(company, null, Status.OPEN, false);
            TaskBase taskBase = assetRecords.taskBase(company, demoAsset, null);
            Task task = new Task(taskBase, real, null, null);
            task.setCompany(company);
            taskRepository.saveAndFlush(task);
            long demoBefore = demoRows(company);

            Response response = purge(data.o1().admin());

            assertDemoDataInUse(response, "TASK_BASE");
            assertEquals(demoBefore, demoRows(company), "no demo record deleted");
            assertEquals(1, count("SELECT count(*) FROM work_order WHERE id = ?", demoWorkOrder.getId()));
            assertEquals(demoAsset.getId(), reference("task_base", "asset_id", taskBase.getId()));
            assertEquals(0, auditRecords(company));
        }

        @Test
        void tdP12b03_demoCustomerOfAClosedRealWorkOrder_409DemoDataInUse_linkKept() {
            // before P-18: 200, the customer unlinked in silence (R2)
            CommonData data = data();
            Company company = data.o1().company();
            Customer customer = customer(company);
            demo("customer", customer.getId());
            WorkOrder closed = records.workOrder(company, null, Status.COMPLETE, false);
            associate("work_order_customers", "work_order_id", closed.getId(), "customers_id", customer.getId());

            Response response = purge(data.o1().admin());

            assertDemoDataInUse(response, "WORK_ORDER");
            assertEquals(1, count("SELECT count(*) FROM customer WHERE id = ?", customer.getId()));
            assertEquals(List.of(customer.getId()), linked("work_order_customers", "work_order_id", closed.getId(),
                    "customers_id"));
        }

        @Test
        void dP18_7_realCustomerOfADemoWorkOrder_409DemoDataInUse() {
            // before P-18: 200, the link deleted with the demo work order (R2, both ways)
            CommonData data = data();
            Company company = data.o1().company();
            Customer customer = customer(company);
            WorkOrder demoWorkOrder = records.workOrder(company, null, Status.OPEN, true);
            associate("work_order_customers", "work_order_id", demoWorkOrder.getId(), "customers_id",
                    customer.getId());

            Response response = purge(data.o1().admin());

            assertDemoDataInUse(response, "CUSTOMER");
            assertEquals(1, count("SELECT count(*) FROM work_order WHERE id = ?", demoWorkOrder.getId()));
        }

        @Test
        void tdP13_03_approvedDemoRequest_409DemoDataInUse() {
            // before P-18: 409 RECORD_IN_USE
            CommonData data = data();
            Company company = data.o1().company();
            com.grash.model.Request request = assetRecords.request(company);
            WorkOrder real = records.workOrder(company, null, Status.OPEN, false);
            demo("request", request.getId());
            jdbc.update("UPDATE work_order SET parent_request_id = ? WHERE id = ?", request.getId(), real.getId());

            assertDemoDataInUse(purge(data.o1().admin()), "WORK_ORDER");
            assertEquals(1, count("SELECT count(*) FROM request WHERE id = ?", request.getId()));
        }

        @Test
        void dP18_3_readingOfADemoMeter_409DemoDataInUse() {
            // before P-18: 409 RECORD_IN_USE (D-P14-9 kept)
            CommonData data = data();
            Company company = data.o1().company();
            Meter meter = assetRecords.meter(company, records.asset(company));
            assetRecords.reading(meter, 3);
            demo("meter", meter.getId());

            assertDemoDataInUse(purge(data.o1().admin()), "READING");
            assertEquals(1, count("SELECT count(*) FROM meter WHERE id = ?", meter.getId()));
        }

        @Test
        void tdP14_07_realChildOfADemoLocation_409DemoDataInUse() {
            // before P-18: 409 RECORD_IN_USE
            CommonData data = data();
            Location parent = records.location(data.o1().company());
            Location child = records.location(data.o1().company());
            demo("location", parent.getId());
            jdbc.update("UPDATE location SET parent_location_id = ? WHERE id = ?", parent.getId(), child.getId());

            assertDemoDataInUse(purge(data.o1().admin()), "LOCATION");
            assertEquals(parent.getId(), reference("location", "parent_location_id", child.getId()));
        }

        @Test
        void dP16_6_demoPartRestocked_409DemoDataInUse() {
            // before P-18: 409 RECORD_IN_USE
            CommonData data = data();
            Part part = partWithStock(data.o1().company(), 2);
            demo("part", part.getId());

            assertDemoDataInUse(purge(data.o1().admin()), "PART_TRANSACTION");
            assertEquals(1, count("SELECT count(*) FROM part WHERE id = ?", part.getId()));
        }

        @Test
        void dP16_6_demoPartOnARealWorkOrder_409DemoDataInUse() {
            // before P-18: 409 RECORD_IN_USE
            CommonData data = data();
            Company company = data.o1().company();
            Part part = records.part(company, null);
            demo("part", part.getId());
            WorkOrder real = records.workOrder(company, null, Status.OPEN, false);
            PartQuantity line = new PartQuantity(part, real, null, 1);
            line.setCompany(company);
            partQuantityRepository.saveAndFlush(line);

            assertDemoDataInUse(purge(data.o1().admin()), "PART_QUANTITY");
            assertEquals(1, count("SELECT count(*) FROM part_quantity WHERE id = ?", line.getId()));
        }

        @Test
        void dP16_6_demoVendorOnARealPurchaseOrder_409DemoDataInUse() {
            // before P-18: 409 RECORD_IN_USE
            CommonData data = data();
            Company company = data.o1().company();
            Vendor vendor = new Vendor();
            vendor.setName(TestRecords.unique("P-18 vendor"));
            vendor.setCompanyName(vendor.getName());
            vendor.setCompany(company);
            vendorRepository.saveAndFlush(vendor);
            demo("vendor", vendor.getId());
            PurchaseOrder purchaseOrder = new PurchaseOrder();
            purchaseOrder.setName(TestRecords.unique("P-18 PO"));
            purchaseOrder.setStatus(ApprovalStatus.PENDING);
            purchaseOrder.setCompany(company);
            purchaseOrderRepository.saveAndFlush(purchaseOrder);
            jdbc.update("UPDATE purchase_order SET vendor_id = ? WHERE id = ?", vendor.getId(), purchaseOrder.getId());

            assertDemoDataInUse(purge(data.o1().admin()), "PURCHASE_ORDER");
            assertEquals(vendor.getId(), reference("purchase_order", "vendor_id", purchaseOrder.getId()));
        }

        @Test
        void p15_demoPreventiveMaintenanceWithARealWorkOrder_409DemoDataInUse() {
            // before P-18: 409 RECORD_IN_USE
            CommonData data = data();
            Long id = preventiveMaintenance(data.o1().company(), TestRecords.unique("P-18 PM"));
            demo("preventive_maintenance", id);
            WorkOrder real = records.workOrder(data.o1().company(), null, Status.OPEN, false);
            jdbc.update("UPDATE work_order SET parent_preventive_maintenance_id = ? WHERE id = ?", id, real.getId());

            assertDemoDataInUse(purge(data.o1().admin()), "WORK_ORDER");
            assertEquals(1, count("SELECT count(*) FROM preventive_maintenance WHERE id = ?", id));
        }
    }

    // =================================================================================================================
    // B5 · Deleting an automation (E-8)
    // =================================================================================================================

    private Workflow workflowWithTwoConditions(Company company) {
        WorkflowAction action = new WorkflowAction();
        action.setCompany(company);
        action.setWorkOrderAction(WorkOrderAction.ASSIGN_PRIORITY);
        action.setPriority(Priority.HIGH);
        List<WorkflowCondition> conditions = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            WorkflowCondition condition = new WorkflowCondition();
            condition.setCompany(company);
            condition.setWorkOrderCondition(WorkOrderCondition.ASSET_IS);
            conditions.add(condition);
        }
        Workflow workflow = new Workflow();
        workflow.setCompany(company);
        workflow.setTitle(TestRecords.unique("P-18 workflow"));
        workflow.setMainCondition(WFMainCondition.WORK_ORDER_CREATED);
        workflow.setAction(action);
        workflow.setSecondaryConditions(conditions);
        return workflowRepository.saveAndFlush(workflow);
    }

    @Nested
    class Automations {

        @Test
        void ca112_deleteWorkflow_200_withActionConditionsAndLinks_recordedWithItsSummary() {
            // before P-18: deleted without audit record
            CommonData data = data();
            Company company = data.o1().company();
            Workflow workflow = workflowWithTwoConditions(company);
            Long actionId = workflow.getAction().getId();

            Response response = call(delete("/workflows/" + workflow.getId()), data.o1().admin());

            assertEquals(200, response.status(), response.toString());
            assertEquals(0, count("SELECT count(*) FROM workflow WHERE id = ?", workflow.getId()));
            assertEquals(0, count("SELECT count(*) FROM workflow_action WHERE id = ?", actionId));
            assertEquals(0, count("SELECT count(*) FROM workflow_secondary_conditions WHERE workflow_id = ?",
                    workflow.getId()));
            Map<String, Object> record = jdbc.queryForMap("SELECT action, source, entity_type, entity_id, entity_name, "
                    + "actor_id, cascade_rows::text AS cascade FROM audit_log WHERE company_id = ?", company.getId());
            assertEquals(List.of("DELETE", "API", "WORKFLOW", workflow.getId(), workflow.getTitle(),
                    data.o1().admin().getId()), List.of(record.get("action"), record.get("source"),
                    record.get("entity_type"), ((Number) record.get("entity_id")).longValue(),
                    record.get("entity_name"), ((Number) record.get("actor_id")).longValue()), record.toString());
            String cascade = (String) record.get("cascade");
            for (String deleted : List.of("\"workflow_action\": 1", "\"workflow_condition\": 2",
                    "\"workflow_secondary_conditions\": 2"))
                assertTrue(cascade.contains(deleted), cascade);
        }

        @Test
        void workflowOfAnotherOrganization_403_notDeleted_noRecord() {
            CommonData data = data();
            Workflow workflow = workflowWithTwoConditions(data.o2().company());

            Response response = call(delete("/workflows/" + workflow.getId()), data.o1().admin());

            assertEquals(403, response.status(), response.toString());
            assertEquals(1, count("SELECT count(*) FROM workflow WHERE id = ?", workflow.getId()));
            assertEquals(0, auditRecords(data.o1().company()));
            assertEquals(0, auditRecords(data.o2().company()));
        }

        @Test
        void technicianWithoutSettings_403_notDeleted() {
            // passed before P-18
            CommonData data = data();
            Workflow workflow = workflowWithTwoConditions(data.o1().company());

            Response response = call(delete("/workflows/" + workflow.getId()), data.o1().technician());

            assertEquals(403, response.status(), response.toString());
            assertEquals(1, count("SELECT count(*) FROM workflow WHERE id = ?", workflow.getId()));
            assertEquals(0, auditRecords(data.o1().company()));
        }
    }
}
