package com.grash.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.grash.dto.imports.AssetImportDTO;
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
import com.grash.model.Request;
import com.grash.model.Task;
import com.grash.model.TaskBase;
import com.grash.model.User;
import com.grash.model.Vendor;
import com.grash.model.WorkOrder;
import com.grash.model.Workflow;
import com.grash.model.WorkflowAction;
import com.grash.model.WorkflowCondition;
import com.grash.model.enums.ApprovalStatus;
import com.grash.model.enums.Priority;
import com.grash.model.enums.Status;
import com.grash.model.enums.workflow.RequestAction;
import com.grash.model.enums.workflow.WFMainCondition;
import com.grash.model.enums.workflow.WorkOrderAction;
import com.grash.model.enums.workflow.WorkOrderCondition;
import com.grash.repository.CustomerRepository;
import com.grash.repository.PartQuantityRepository;
import com.grash.repository.PurchaseOrderRepository;
import com.grash.repository.TaskRepository;
import com.grash.repository.VendorRepository;
import com.grash.repository.WorkflowRepository;
import com.grash.controller.DemoController;
import com.grash.service.ImportService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

/**
 * P-18 characterization: how the indirect routes behaved before any change. The imports of assets, locations, meters,
 * parts, preventive maintenances and work orders with a row that updates an archived asset, cites a disabled user or
 * an archived asset, removes an association or carries the archived column; the demo purge without permission, in the
 * normal case, with each record that is not demo and depends on a demo one, and with a failure halfway; the deletion
 * of an automation; and the rejection of a request with a "request rejected" automation. The foreign keys that point
 * to the tables with {@code is_demo} are printed for the design. The tests that show the old contract are updated to
 * the contract of P-18, as step 8 of the order allows, each one with its "before P-18".
 */
@AutoConfigureMockMvc
class IndirectRoutesCharacterizationIntegrationTest extends AbstractIntegrationTest {

    private final TestOrganizations organizations;
    private final TestRecords records;
    private final TestAssetRecords assetRecords;
    private final TestApi api;
    private final JdbcTemplate jdbc;
    private final ImportService importService;
    private final CustomerRepository customerRepository;
    private final VendorRepository vendorRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final TaskRepository taskRepository;
    private final PartQuantityRepository partQuantityRepository;
    private final WorkflowRepository workflowRepository;
    private final DemoController demoController;
    private final FaultInjector faults;
    private final List<CommonData> created = new ArrayList<>();

    @Autowired
    IndirectRoutesCharacterizationIntegrationTest(TestOrganizations organizations, TestRecords records,
                                                  TestAssetRecords assetRecords, MockMvc mockMvc,
                                                  ObjectMapper objectMapper, JdbcTemplate jdbc,
                                                  ImportService importService,
                                                  CustomerRepository customerRepository,
                                                  VendorRepository vendorRepository,
                                                  PurchaseOrderRepository purchaseOrderRepository,
                                                  TaskRepository taskRepository,
                                                  PartQuantityRepository partQuantityRepository,
                                                  WorkflowRepository workflowRepository,
                                                  DemoController demoController) {
        this.organizations = organizations;
        this.records = records;
        this.assetRecords = assetRecords;
        this.api = new TestApi(mockMvc, objectMapper);
        this.jdbc = jdbc;
        this.importService = importService;
        this.customerRepository = customerRepository;
        this.vendorRepository = vendorRepository;
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.taskRepository = taskRepository;
        this.partQuantityRepository = partQuantityRepository;
        this.workflowRepository = workflowRepository;
        this.demoController = demoController;
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

    private void demo(String table, Long id) {
        jdbc.update("UPDATE " + table + " SET is_demo = true WHERE id = ?", id);
    }

    private void associate(String table, String ownerColumn, Long ownerId, String otherColumn, Long otherId) {
        jdbc.update("INSERT INTO " + table + " (" + ownerColumn + ", " + otherColumn + ") VALUES (?, ?)", ownerId,
                otherId);
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private long rows(String table, Long id) {
        return count("SELECT count(*) FROM " + table + " WHERE id = ?", id);
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

    private long auditRecords(Company company) {
        return count("SELECT count(*) FROM audit_log WHERE company_id = ?", company.getId());
    }

    private Response call(MockHttpServletRequestBuilder request, User user) {
        return api.call(request, api.login(user));
    }

    private Response purge(User user) {
        return call(delete("/demo/demo-data"), user);
    }

    private Customer customer(Company company) {
        Customer customer = new Customer();
        customer.setName(TestRecords.unique("P-18 customer"));
        customer.setCompany(company);
        return customerRepository.saveAndFlush(customer);
    }

    private Vendor vendor(Company company) {
        Vendor vendor = new Vendor();
        vendor.setName(TestRecords.unique("P-18 vendor"));
        vendor.setCompanyName(vendor.getName());
        vendor.setCompany(company);
        return vendorRepository.saveAndFlush(vendor);
    }

    private PurchaseOrder purchaseOrder(Company company) {
        PurchaseOrder purchaseOrder = new PurchaseOrder();
        purchaseOrder.setName(TestRecords.unique("P-18 PO"));
        purchaseOrder.setStatus(ApprovalStatus.PENDING);
        purchaseOrder.setCompany(company);
        return purchaseOrderRepository.saveAndFlush(purchaseOrder);
    }

    private Long preventiveMaintenance(CommonData data, String name) {
        importService.importPreventiveMaintenances(List.of(pmRow(null, name).build()), data.o1().company());
        return jdbc.queryForObject("SELECT id FROM preventive_maintenance WHERE company_id = ? AND name = ?",
                Long.class, data.o1().company().getId(), name);
    }

    private PreventiveMaintenanceImportDTO.PreventiveMaintenanceImportDTOBuilder<?, ?> pmRow(Long id, String name) {
        return PreventiveMaintenanceImportDTO.builder().id(id).name(name).title(name).frequency(1)
                .recurrenceType("DAILY").recurrenceBasedOn("SCHEDULED_DATE")
                // 2099-12-31 as an Excel date: the scheduler must not fire it during the test
                .startsOn(73050.0);
    }

    private Workflow workflow(Company company, WFMainCondition mainCondition, WorkflowAction action,
                              List<WorkflowCondition> conditions) {
        action.setCompany(company);
        conditions.forEach(condition -> condition.setCompany(company));
        Workflow workflow = new Workflow();
        workflow.setCompany(company);
        workflow.setTitle(TestRecords.unique("P-18 workflow"));
        workflow.setMainCondition(mainCondition);
        workflow.setAction(action);
        workflow.setSecondaryConditions(new ArrayList<>(conditions));
        return workflowRepository.saveAndFlush(workflow);
    }

    private WorkflowCondition assetCondition(Asset asset) {
        WorkflowCondition condition = new WorkflowCondition();
        condition.setWorkOrderCondition(WorkOrderCondition.ASSET_IS);
        condition.setAsset(asset);
        return condition;
    }

    // =================================================================================================================
    // Imports
    // =================================================================================================================

    @Nested
    class AssetImport {

        @Test
        void ca055_existingAndNewRow_oneUpdatedOneCreated_noRowDeleted() {
            CommonData data = data();
            Company company = data.o1().company();
            Asset existing = records.asset(company);
            String newName = TestRecords.unique("P-18 new asset");
            long assets = count("SELECT count(*) FROM asset WHERE company_id = ?", company.getId());

            ImportResponse response = importService.importAssets(List.of(
                    AssetImportDTO.builder().id(existing.getId()).name("P-18 updated").build(),
                    AssetImportDTO.builder().name(newName).build()), company);

            assertEquals(List.of(1, 1), List.of(response.getCreated(), response.getUpdated()));
            assertEquals("P-18 updated", row("asset", existing.getId()).get("name"));
            assertEquals(assets + 1, count("SELECT count(*) FROM asset WHERE company_id = ?", company.getId()));
        }

        @Test
        void ca133_rowUpdatesAnArchivedAsset_rejected_assetUnchanged() {
            // before P-18: applied, and the file (without "archived") restored the asset. P-18 (E-1): rejected
            CommonData data = data();
            Asset asset = archived(records.asset(data.o1().company()));
            Map<String, Object> before = row("asset", asset.getId());

            ImportResponse response = importService.importAssets(List.of(
                    AssetImportDTO.builder().id(asset.getId()).name("P-18 changed").build()), data.o1().company());

            assertEquals(0, response.getUpdated());
            assertEquals("ASSET_RETIRED", response.getRejected().get(0).getCode());
            assertEquals(before, row("asset", asset.getId()));
        }

        @Test
        void archivedColumn_ignored() {
            // before P-18: the import archived the asset without audit record. P-18 (D-1): the column is ignored
            CommonData data = data();
            Asset asset = records.asset(data.o1().company());
            long audit = auditRecords(data.o1().company());

            importService.importAssets(List.of(AssetImportDTO.builder().id(asset.getId()).name(asset.getName())
                    .archived("Yes").build()), data.o1().company());

            assertEquals(false, row("asset", asset.getId()).get("archived"));
            assertEquals(audit, auditRecords(data.o1().company()));
        }

        @Test
        void disabledUserAndArchivedParent_rejectedAsNewReferences() {
            // before P-18: a disabled user and an archived parent were given by the file. P-18 (E-2): the row is
            // rejected
            CommonData data = data();
            Company company = data.o1().company();
            User technician = disabled(data.o1().technician());
            Asset parent = archived(records.asset(company));
            String byUser = TestRecords.unique("P-18 asset");
            String byParent = TestRecords.unique("P-18 asset");

            ImportResponse response = importService.importAssets(List.of(AssetImportDTO.builder().name(byUser)
                    .primaryUserEmail(technician.getEmail()).assignedToEmails(List.of(technician.getEmail())).build(),
                    AssetImportDTO.builder().name(byParent).parentAssetName(parent.getName()).build()), company);

            assertEquals(0, response.getCreated());
            assertEquals(List.of("USER_RETIRED", "ASSET_RETIRED"), response.getRejected().stream()
                    .map(rejection -> rejection.getCode()).toList());
            assertEquals(0, count("SELECT count(*) FROM asset WHERE company_id = ? AND name IN (?, ?)",
                    company.getId(), byUser, byParent));
        }

        @Test
        void rowWithoutTheDisabledUser_removesItsAssignment() {
            // the same as the direct route (PATCH /assets/{id} lets a disabled user be removed)
            CommonData data = data();
            Asset asset = records.asset(data.o1().company());
            User technician = disabled(data.o1().technician());
            associate("t_asset_user_associations", "id_asset", asset.getId(), "id_user", technician.getId());

            importService.importAssets(List.of(AssetImportDTO.builder().id(asset.getId()).name(asset.getName())
                    .build()), data.o1().company());

            assertEquals(List.of(), linked("t_asset_user_associations", "id_asset", asset.getId(), "id_user"));
        }
    }

    @Nested
    class LocationImport {

        @Test
        void disabledWorker_accepted() {
            // before P-18
            CommonData data = data();
            Location location = records.location(data.o1().company());
            User technician = disabled(data.o1().technician());

            importService.importLocations(List.of(LocationImportDTO.builder().id(location.getId())
                    .name(location.getName()).workersEmails(List.of(technician.getEmail())).build()),
                    data.o1().company());

            assertEquals(List.of(technician.getId()), linked("t_location_user_associations", "id_location",
                    location.getId(), "id_user"));
        }

        @Test
        void rowWithoutTheDisabledWorker_removesIt() {
            CommonData data = data();
            Location location = records.location(data.o1().company());
            User technician = disabled(data.o1().technician());
            associate("t_location_user_associations", "id_location", location.getId(), "id_user",
                    technician.getId());

            importService.importLocations(List.of(LocationImportDTO.builder().id(location.getId())
                    .name(location.getName()).build()), data.o1().company());

            assertEquals(List.of(), linked("t_location_user_associations", "id_location", location.getId(),
                    "id_user"));
        }
    }

    @Nested
    class MeterImport {

        @Test
        void disabledUserAndArchivedAsset_accepted() {
            // before P-18
            CommonData data = data();
            Company company = data.o1().company();
            Meter meter = assetRecords.meter(company, records.asset(company));
            User technician = disabled(data.o1().technician());
            Asset asset = archived(records.asset(company));

            importService.importMeters(List.of(MeterImportDTO.builder().id(meter.getId()).name(meter.getName())
                    .updateFrequency(1).assetName(asset.getName()).usersEmails(List.of(technician.getEmail()))
                    .build()), company);

            assertEquals(asset.getId(), reference("meter", "asset_id", meter.getId()));
            assertEquals(List.of(technician.getId()), linked("t_meter_user_associations", "id_meter", meter.getId(),
                    "id_user"));
        }

        @Test
        void rowWithoutTheDisabledUser_removesIt() {
            CommonData data = data();
            Meter meter = assetRecords.meter(data.o1().company(), records.asset(data.o1().company()));
            User technician = disabled(data.o1().technician());
            associate("t_meter_user_associations", "id_meter", meter.getId(), "id_user", technician.getId());

            importService.importMeters(List.of(MeterImportDTO.builder().id(meter.getId()).name(meter.getName())
                    .updateFrequency(1).build()), data.o1().company());

            assertEquals(List.of(), linked("t_meter_user_associations", "id_meter", meter.getId(), "id_user"));
        }
    }

    @Nested
    class PartImport {

        @Test
        void disabledUser_accepted() {
            // before P-18 (TD-P16-05)
            CommonData data = data();
            Part part = records.part(data.o1().company(), null);
            User technician = disabled(data.o1().technician());

            importService.importParts(List.of(PartImportDTO.builder().id(part.getId()).name(part.getName())
                    .assignedToEmails(List.of(technician.getEmail())).build()), data.o1().company(),
                    data.o1().admin());

            assertEquals(List.of(technician.getId()), linked("t_part_user_associations", "id_part", part.getId(),
                    "id_user"));
        }

        @Test
        void rowWithoutTheDisabledUser_removesIt() {
            CommonData data = data();
            Part part = records.part(data.o1().company(), null);
            User technician = disabled(data.o1().technician());
            associate("t_part_user_associations", "id_part", part.getId(), "id_user", technician.getId());

            importService.importParts(List.of(PartImportDTO.builder().id(part.getId()).name(part.getName()).build()),
                    data.o1().company(), data.o1().admin());

            assertEquals(List.of(), linked("t_part_user_associations", "id_part", part.getId(), "id_user"));
        }
    }

    @Nested
    class PreventiveMaintenanceImport {

        @Test
        void disabledUserAndArchivedAsset_accepted() {
            // before P-18
            CommonData data = data();
            Company company = data.o1().company();
            String name = TestRecords.unique("P-18 PM");
            Long id = preventiveMaintenance(data, name);
            User technician = disabled(data.o1().technician());
            Asset asset = archived(records.asset(company));

            importService.importPreventiveMaintenances(List.of(pmRow(id, name).primaryUserEmail(technician.getEmail())
                    .assignedToEmails(List.of(technician.getEmail())).assetName(asset.getName()).build()), company);

            assertEquals(technician.getId(), reference("preventive_maintenance", "primary_user_id", id));
            assertEquals(asset.getId(), reference("preventive_maintenance", "asset_id", id));
            assertEquals(List.of(technician.getId()), linked("preventive_maintenance_assigned_to",
                    "preventive_maintenance_id", id, "assigned_to_id"));
        }

        @Test
        void pausedPreventiveMaintenance_updated_staysPaused() {
            // the import does not resume it (P-15: editing a paused one keeps it paused)
            CommonData data = data();
            String name = TestRecords.unique("P-18 PM");
            Long id = preventiveMaintenance(data, name);
            jdbc.update("UPDATE schedule SET disabled = true WHERE preventive_maintenance_id = ?", id);

            importService.importPreventiveMaintenances(List.of(pmRow(id, name).title("P-18 changed").build()),
                    data.o1().company());

            assertEquals("P-18 changed", row("preventive_maintenance", id).get("title"));
            assertEquals(true, jdbc.queryForObject("SELECT disabled FROM schedule WHERE preventive_maintenance_id = ?",
                    Boolean.class, id));
        }

        @Test
        void fiveWorkOrdersWithoutReaction_pausedByTheImport_withItsRetireRecord() {
            // the automatic pause of P-15 (origin IMPORT): ScheduleService gives it its own transaction
            CommonData data = data();
            String name = TestRecords.unique("P-18 PM");
            Long id = preventiveMaintenance(data, name);
            for (int i = 0; i < 5; i++) {
                WorkOrder workOrder = records.workOrder(data.o1().company(), null, Status.OPEN, false);
                jdbc.update("UPDATE work_order SET parent_preventive_maintenance_id = ? WHERE id = ?", id,
                        workOrder.getId());
            }

            importService.importPreventiveMaintenances(List.of(pmRow(id, name).title("P-18 changed").build()),
                    data.o1().company());

            assertEquals("P-18 changed", row("preventive_maintenance", id).get("title"));
            assertEquals(true, jdbc.queryForObject("SELECT disabled FROM schedule WHERE preventive_maintenance_id = ?",
                    Boolean.class, id));
            assertEquals(List.of("RETIRE IMPORT"), jdbc.queryForList("SELECT action || ' ' || source FROM audit_log "
                    + "WHERE company_id = ? AND entity_id = ?", String.class, data.o1().company().getId(), id));
        }
    }

    @Nested
    class WorkOrderImport {

        @Test
        void disabledUserAndArchivedAsset_accepted() {
            // not in E-2 of the order: shown for the design (G-1)
            CommonData data = data();
            Company company = data.o1().company();
            WorkOrder workOrder = records.workOrder(company, null, Status.OPEN, false);
            User technician = disabled(data.o1().technician());
            Asset asset = archived(records.asset(company));

            importService.importWorkOrders(List.of(WorkOrderImportDTO.builder().id(workOrder.getId())
                    .title(workOrder.getTitle()).status("Open").primaryUserEmail(technician.getEmail())
                    .assetName(asset.getName()).build()), company, data.o1().admin());

            assertEquals(technician.getId(), reference("work_order", "primary_user_id", workOrder.getId()));
            assertEquals(asset.getId(), reference("work_order", "asset_id", workOrder.getId()));
        }
    }

    @Nested
    class DemoAccountLoad {

        @Test
        void shippedCsvFiles_imported() {
            // the load of /demo/generate-account, called directly: in the tests the signup of a random demo address
            // is rejected by allowed-organization-admins before the load runs
            CommonData data = data();
            Company company = data.o1().company();

            Object target = AopTestUtils.getUltimateTargetObject(demoController);
            ReflectionTestUtils.invokeMethod(target, "importDemoData", data.o1().admin());

            for (String table : List.of("location", "asset", "meter", "part", "work_order")) {
                long imported = count("SELECT count(*) FROM " + table + " WHERE company_id = ?", company.getId());
                System.out.println("P-18 demo account load " + table + " " + imported);
                assertTrue(imported > 0, table);
            }
        }
    }

    // =================================================================================================================
    // Demo purge
    // =================================================================================================================

    @Nested
    class DemoPurge {

        @Test
        void ca074_technicianWithoutSettings_200_demoDataDeleted() {
            // before P-18: any authenticated user purges
            CommonData data = data();
            Asset demoAsset = records.asset(data.o1().company(), null, true);

            Response response = purge(data.o1().technician());

            assertEquals(200, response.status(), response.toString());
            assertEquals(0, rows("asset", demoAsset.getId()));
        }

        @Test
        void ca057_onlyDemoData_200_withoutAuditRecord() {
            // before P-18: no PURGE record
            CommonData data = data();
            Company company = data.o1().company();
            Asset parent = records.asset(company, null, true);
            Asset child = records.asset(company, null, true);
            jdbc.update("UPDATE asset SET parent_asset_id = ? WHERE id = ?", parent.getId(), child.getId());
            WorkOrder demoWorkOrder = records.workOrder(company, child, Status.OPEN, true);
            Asset real = records.asset(company);
            Map<String, Object> realBefore = row("asset", real.getId());

            Response response = purge(data.o1().admin());

            assertEquals(200, response.status(), response.toString());
            assertEquals(0, count("SELECT count(*) FROM asset WHERE id IN (?, ?)", parent.getId(), child.getId()));
            assertEquals(0, rows("work_order", demoWorkOrder.getId()));
            assertEquals(realBefore, row("asset", real.getId()));
            assertEquals(0, auditRecords(company));
        }

        @Test
        void ca068_failureHalfway_error_allDemoDataKept() {
            CommonData data = data();
            Company company = data.o1().company();
            WorkOrder demoWorkOrder = records.workOrder(company, null, Status.OPEN, true);
            Asset demoAsset = records.asset(company, null, true);

            Response response;
            try (Fault fault = faults.failOn("asset", Operation.DELETE)) {
                response = purge(data.o1().admin());
                assertEquals(1, fault.hits());
            }

            assertNotEquals(200, response.status(), response.toString());
            assertEquals(1, rows("work_order", demoWorkOrder.getId()), "the demo work order was deleted before");
            assertEquals(1, rows("asset", demoAsset.getId()));
            System.out.println("P-18 characterization: failure halfway answers " + response);
        }

        @Test
        void ca056_taskBaseOfARealWorkOrderCitesDemoAsset_409RecordInUse() {
            // before P-18: RECORD_IN_USE from the global handler
            CommonData data = data();
            Company company = data.o1().company();
            Asset demoAsset = records.asset(company, null, true);
            WorkOrder real = records.workOrder(company, null, Status.OPEN, false);
            TaskBase taskBase = assetRecords.taskBase(company, demoAsset, null);
            Task task = new Task(taskBase, real, null, null);
            task.setCompany(company);
            taskRepository.saveAndFlush(task);

            Response response = purge(data.o1().admin());

            assertEquals(409, response.status(), response.toString());
            assertTrue(response.body().contains("RECORD_IN_USE"), response.toString());
            assertEquals(1, rows("asset", demoAsset.getId()));
            assertEquals(1, rows("task", task.getId()));
        }

        @Test
        void tdP12b03_demoCustomerOfAClosedRealWorkOrder_200_silentlyUnlinked() {
            // before P-18 (TD-P12b-03)
            CommonData data = data();
            Company company = data.o1().company();
            Customer customer = customer(company);
            demo("customer", customer.getId());
            WorkOrder closed = records.workOrder(company, null, Status.COMPLETE, false);
            associate("work_order_customers", "work_order_id", closed.getId(), "customers_id", customer.getId());

            Response response = purge(data.o1().admin());

            assertEquals(200, response.status(), response.toString());
            assertEquals(0, rows("customer", customer.getId()));
            assertEquals(List.of(), linked("work_order_customers", "work_order_id", closed.getId(), "customers_id"));
        }

        @Test
        void tdP13_03_approvedDemoRequest_409RecordInUse() {
            CommonData data = data();
            Company company = data.o1().company();
            Request request = assetRecords.request(company);
            WorkOrder real = records.workOrder(company, null, Status.OPEN, false);
            demo("request", request.getId());
            jdbc.update("UPDATE work_order SET parent_request_id = ? WHERE id = ?", request.getId(), real.getId());

            Response response = purge(data.o1().admin());

            assertEquals(409, response.status(), response.toString());
            assertTrue(response.body().contains("RECORD_IN_USE"), response.toString());
            assertEquals(1, rows("request", request.getId()));
        }

        @Test
        void tdP14_07_readingOfADemoMeter_409RecordInUse() {
            CommonData data = data();
            Meter meter = assetRecords.meter(data.o1().company(), records.asset(data.o1().company()));
            assetRecords.reading(meter, 3);
            demo("meter", meter.getId());

            Response response = purge(data.o1().admin());

            assertEquals(409, response.status(), response.toString());
            assertTrue(response.body().contains("RECORD_IN_USE"), response.toString());
            assertEquals(1, rows("meter", meter.getId()));
        }

        @Test
        void tdP14_07_realChildOfADemoLocation_409RecordInUse() {
            CommonData data = data();
            Location parent = records.location(data.o1().company());
            Location child = records.location(data.o1().company());
            demo("location", parent.getId());
            jdbc.update("UPDATE location SET parent_location_id = ? WHERE id = ?", parent.getId(), child.getId());

            Response response = purge(data.o1().admin());

            assertEquals(409, response.status(), response.toString());
            assertTrue(response.body().contains("RECORD_IN_USE"), response.toString());
            assertEquals(parent.getId(), reference("location", "parent_location_id", child.getId()));
        }

        @Test
        void dP16_6_demoPartOnARealWorkOrder_409RecordInUse() {
            CommonData data = data();
            Company company = data.o1().company();
            Part part = records.part(company, null);
            demo("part", part.getId());
            WorkOrder real = records.workOrder(company, null, Status.OPEN, false);
            PartQuantity line = new PartQuantity(part, real, null, 1);
            line.setCompany(company);
            partQuantityRepository.saveAndFlush(line);

            Response response = purge(data.o1().admin());

            assertEquals(409, response.status(), response.toString());
            assertTrue(response.body().contains("RECORD_IN_USE"), response.toString());
            assertEquals(1, rows("part", part.getId()));
        }

        @Test
        void dP16_6_demoVendorOnARealPurchaseOrder_409RecordInUse() {
            CommonData data = data();
            Company company = data.o1().company();
            Vendor vendor = vendor(company);
            demo("vendor", vendor.getId());
            PurchaseOrder purchaseOrder = purchaseOrder(company);
            jdbc.update("UPDATE purchase_order SET vendor_id = ? WHERE id = ?", vendor.getId(), purchaseOrder.getId());

            Response response = purge(data.o1().admin());

            assertEquals(409, response.status(), response.toString());
            assertTrue(response.body().contains("RECORD_IN_USE"), response.toString());
            assertEquals(vendor.getId(), reference("purchase_order", "vendor_id", purchaseOrder.getId()));
        }

        @Test
        void p15_demoPreventiveMaintenanceWithARealWorkOrder_409RecordInUse() {
            CommonData data = data();
            Long id = preventiveMaintenance(data, TestRecords.unique("P-18 PM"));
            demo("preventive_maintenance", id);
            WorkOrder real = records.workOrder(data.o1().company(), null, Status.OPEN, false);
            jdbc.update("UPDATE work_order SET parent_preventive_maintenance_id = ? WHERE id = ?", id, real.getId());

            Response response = purge(data.o1().admin());

            assertEquals(409, response.status(), response.toString());
            assertTrue(response.body().contains("RECORD_IN_USE"), response.toString());
            assertEquals(1, rows("preventive_maintenance", id));
        }

        @Test
        void foreignKeysToTheDemoTables_printedForTheDesign() {
            List<Map<String, Object>> keys = jdbc.queryForList("SELECT c.conrelid::regclass::text AS child, "
                    + "a.attname AS child_column, c.confrelid::regclass::text AS parent, c.confdeltype AS on_delete, "
                    + "EXISTS (SELECT 1 FROM information_schema.columns k WHERE k.table_schema = current_schema() "
                    + "AND k.table_name = c.conrelid::regclass::text AND k.column_name = 'is_demo') AS child_is_demo "
                    + "FROM pg_constraint c JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = c.conkey[1] "
                    + "WHERE c.contype = 'f' AND c.connamespace = current_schema()::regnamespace "
                    + "AND c.confrelid::regclass::text IN (SELECT k.table_name FROM information_schema.columns k "
                    + "WHERE k.table_schema = current_schema() AND k.column_name = 'is_demo') "
                    + "ORDER BY parent, child, child_column");
            keys.forEach(key -> System.out.println("P-18 FK " + key.get("parent") + " <- " + key.get("child") + "."
                    + key.get("child_column") + " on_delete=" + key.get("on_delete") + " child_is_demo="
                    + key.get("child_is_demo")));
            List<String> demoTables = jdbc.queryForList("SELECT table_name FROM information_schema.columns "
                    + "WHERE table_schema = current_schema() AND column_name = 'is_demo' ORDER BY table_name",
                    String.class);
            System.out.println("P-18 demo tables " + demoTables);
            assertFalse(keys.isEmpty());
        }
    }

    // =================================================================================================================
    // Automations
    // =================================================================================================================

    @Nested
    class Automations {

        @Test
        void ca112_deleteWorkflow_200_actionConditionsAndLinksDeleted_withoutAuditRecord() {
            // before P-18: no DELETE record
            CommonData data = data();
            Company company = data.o1().company();
            WorkflowAction action = new WorkflowAction();
            action.setWorkOrderAction(WorkOrderAction.ASSIGN_PRIORITY);
            action.setPriority(Priority.HIGH);
            Workflow workflow = workflow(company, WFMainCondition.WORK_ORDER_CREATED, action,
                    List.of(assetCondition(null), assetCondition(null)));
            Long actionId = workflow.getAction().getId();
            List<Long> conditions = linked("workflow_secondary_conditions", "workflow_id", workflow.getId(),
                    "secondary_conditions_id");

            Response response = call(delete("/workflows/" + workflow.getId()), data.o1().admin());

            assertEquals(200, response.status(), response.toString());
            assertEquals(2, conditions.size());
            assertEquals(0, rows("workflow", workflow.getId()));
            assertEquals(0, rows("workflow_action", actionId));
            assertEquals(0, count("SELECT count(*) FROM workflow_condition WHERE id IN (?, ?)", conditions.get(0),
                    conditions.get(1)));
            assertEquals(0, count("SELECT count(*) FROM workflow_secondary_conditions WHERE workflow_id = ?",
                    workflow.getId()));
            assertEquals(0, auditRecords(company));
        }

        @Test
        void tdP13_01_rejectedRequest_changedByTheRequestRejectedAutomation() {
            // before P-18 (TD-P13-01): the automation changes the request already marked as rejected
            CommonData data = data();
            Company company = data.o1().company();
            Request request = assetRecords.request(company);
            WorkflowAction action = new WorkflowAction();
            action.setRequestAction(RequestAction.ASSIGN_PRIORITY);
            action.setPriority(Priority.HIGH);
            workflow(company, WFMainCondition.REQUEST_REJECTED, action, List.of());

            Response response = call(patch("/requests/" + request.getId() + "/cancel").param("reason", "P-18")
                    .contentType(MediaType.APPLICATION_JSON), data.o1().admin());

            assertEquals(200, response.status(), response.toString());
            Map<String, Object> after = row("request", request.getId());
            assertEquals(true, after.get("cancelled"));
            assertEquals(Priority.HIGH.ordinal(), ((Number) after.get("priority")).intValue(), after.toString());
        }
    }
}
