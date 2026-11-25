package com.grash.service;

import com.grash.dto.imports.*;
import com.grash.exception.ConflictException;
import com.grash.exception.CustomException;
import com.grash.model.*;
import com.grash.model.abstracts.WorkOrderBase;
import com.grash.model.enums.PartTransactionOrigin;
import com.grash.model.enums.PermissionEntity;
import com.grash.model.enums.Status;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;

import jakarta.transaction.Transactional;

import java.util.*;
import java.util.stream.IntStream;

@Service
@RequiredArgsConstructor
public class ImportService {

    private final AssetService assetService;
    private final LocationService locationService;
    private final PartService partService;
    private final PartLedgerService partLedgerService;
    private final MeterService meterService;
    private final WorkOrderService workOrderService;
    private final PreventiveMaintenanceService preventiveMaintenanceService;
    private final UserService userService;

    /**
     * Work order import requested by a user. INC-02 (T-11, D-20), approved exception for historical data: a new
     * COMPLETE row keeps its closing user from the file, but it takes the permission to close work orders of others
     * (edit-other on work orders), and updating an existing work order takes the same right as editing it; a row that
     * updates an existing work order to COMPLETE follows the closing rule (D-19). Nothing is imported if a row is
     * rejected.
     */
    @Transactional
    public ImportResponse importWorkOrders(List<WorkOrderImportDTO> toImport, Company company, User importer) {
        boolean closesOthers = importer.getRole().getEditOtherPermissions().contains(PermissionEntity.WORK_ORDERS);
        List<Long> ids = toImport.stream().map(WorkOrderImportDTO::getId).filter(Objects::nonNull).toList();
        Collection<WorkOrder> existing = ids.isEmpty() ? List.of() : workOrderService.findByIdsAndCompany(ids,
                company.getId());
        for (WorkOrderImportDTO dto : toImport) {
            if (Status.getStatusFromString(dto.getStatus()) == Status.COMPLETE && !closesOthers)
                throw new CustomException("You are not allowed to import completed work orders",
                        HttpStatus.FORBIDDEN);
        }
        if (existing.stream().anyMatch(workOrder -> !workOrder.canBeEditedBy(importer)))
            throw new CustomException("You are not allowed to update some of these work orders", HttpStatus.FORBIDDEN);
        return saveWorkOrders(toImport, company, importer);
    }

    /**
     * Loads the demo data shipped with the server, which is trusted and has no requesting user.
     */
    @Transactional
    public ImportResponse importWorkOrders(List<WorkOrderImportDTO> toImport, Company company) {
        return saveWorkOrders(toImport, company, null);
    }

    private ImportResponse saveWorkOrders(List<WorkOrderImportDTO> toImport, Company company, User importer) {
        List<Long> idsToCheck = toImport.stream()
                .map(WorkOrderImportDTO::getId)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toList());

        Map<Long, WorkOrder> existingWorkOrders = new java.util.HashMap<>();
        if (!idsToCheck.isEmpty()) {
            workOrderService.findByIdsAndCompany(idsToCheck, company.getId())
                    .forEach(wo -> existingWorkOrders.put(wo.getId(), wo));
        }

        List<WorkOrder> workOrdersToSave = new java.util.ArrayList<>();
        List<ImportRejection> rejected = new ArrayList<>();
        int created = 0;
        int updated = 0;

        for (int row = 1; row <= toImport.size(); row++) {
            WorkOrderImportDTO dto = toImport.get(row - 1);
            Long id = dto.getId();
            WorkOrder workOrder;

            if (id == null) {
                workOrder = new WorkOrder();
            } else if (existingWorkOrders.containsKey(id)) {
                workOrder = existingWorkOrders.get(id);
                // P-12c (E-4, PPB-20 b): a closed or archived work order is not changed by an import; the row
                // replaces its customers (an association), so it is checked as a change to its children. The row
                // is rejected with its reason and the other rows are imported
                try {
                    WorkOrderStateGuard.check(workOrder, WorkOrderStateGuard.Change.CHILD);
                } catch (ConflictException rejection) {
                    rejected.add(new ImportRejection(row, id, rejection.getCode(), rejection.getMessage()));
                    continue;
                }
            } else {
                workOrder = new WorkOrder();
            }
            // P-18 (D-P18-1, E-2): a disabled user or an archived asset is not given anew
            WorkOrder target = workOrder;
            Optional<ImportRejection> rejection = rejection(row, id, () -> rejectRetiredReferences(
                    target.getId() == null ? null : target, dto, company));
            if (rejection.isPresent()) {
                rejected.add(rejection.get());
                continue;
            }
            if (workOrder.getId() == null) created++;
            else updated++;

            workOrderService.importWorkOrder(workOrder, dto, company, importer);
            workOrdersToSave.add(workOrder);
        }

        if (!workOrdersToSave.isEmpty()) {
            workOrderService.saveAll(workOrdersToSave);
        }

        return ImportResponse.builder()
                .created(created)
                .updated(updated)
                .rejected(rejected)
                .build();
    }

    @Transactional
    public ImportResponse importAssets(List<AssetImportDTO> toImport, Company company) {
        // Check for duplicate non-null barcodes
        Set<String> seenBarcodes = new java.util.HashSet<>();
        for (AssetImportDTO dto : toImport) {
            if (dto.getBarCode() != null && !dto.getBarCode().isEmpty()) {
                if (!seenBarcodes.add(dto.getBarCode())) {
                    throw new IllegalArgumentException("Duplicate barcode found: " + dto.getBarCode());
                }
            }
        }

        List<Long> idsToCheck = toImport.stream()
                .map(AssetImportDTO::getId)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toList());

        Map<Long, Asset> existingAssets = new java.util.HashMap<>();
        if (!idsToCheck.isEmpty()) {
            assetService.findByIdsAndCompany(idsToCheck, company.getId())
                    .forEach(asset -> existingAssets.put(asset.getId(), asset));
        }

        Map<String, Asset> assetsByName = new java.util.HashMap<>();
        List<Asset> assetsToSave = new java.util.ArrayList<>();
        List<ImportRejection> rejected = new ArrayList<>();
        Map<AssetImportDTO, Integer> rows = positions(toImport);
        int created = 0;
        int updated = 0;

        for (AssetImportDTO dto : AssetService.orderAssets(toImport)) {
            Long id = dto.getId();
            Asset existing = id == null ? null : existingAssets.get(id);
            // P-18 (E-1, E-2, PPB-08): an archived asset is not changed, and a disabled user or an archived parent is
            // not given anew; the row is rejected with its reason and not added to the batch
            Optional<ImportRejection> rejection = rejection(rows.get(dto), id, () -> {
                if (existing != null) AssetStateGuard.check(existing);
                UserService.rejectRetiredLoaded(existing == null ? List.of()
                                : WorkOrderService.assignedUsers(existing.getPrimaryUser(), existing.getAssignedTo()),
                        users(company, dto.getPrimaryUserEmail(), dto.getAssignedToEmails()));
                assetService.rejectRetired(existing == null ? List.of()
                                : Collections.singletonList(existing.getParentAsset()),
                        Collections.singletonList(parentAsset(dto.getParentAssetName(), company, assetsByName)));
            });
            if (rejection.isPresent()) {
                rejected.add(rejection.get());
                continue;
            }
            Asset asset = existing == null ? new Asset() : existing;
            if (existing == null) created++;
            else updated++;

            assetService.setAssetFieldsFromImportDto(asset, dto, company, assetsByName);
            assetsToSave.add(asset);
            assetsByName.put(asset.getName(), asset);
        }

        if (!assetsToSave.isEmpty()) {
            assetService.saveAll(assetsToSave);
        }

        rejected.sort(Comparator.comparingInt(ImportRejection::getRow));
        return ImportResponse.builder()
                .created(created)
                .updated(updated)
                .rejected(rejected)
                .build();
    }

    /**
     * P-18: the position of each row in the file, starting at 1, for the rows that are imported in another order.
     */
    private static <T> Map<T, Integer> positions(List<T> toImport) {
        Map<T, Integer> rows = new IdentityHashMap<>();
        for (int row = toImport.size(); row >= 1; row--) rows.put(toImport.get(row - 1), row);
        return rows;
    }

    /**
     * P-18 (PPB-20 b): the rejection of the row when {@code check} rejects it with a 409, or nothing.
     */
    private static Optional<ImportRejection> rejection(int row, Long id, Runnable check) {
        try {
            check.run();
            return Optional.empty();
        } catch (ConflictException conflict) {
            return Optional.of(new ImportRejection(row, id, conflict.getCode(), conflict.getMessage()));
        }
    }

    /**
     * P-18 (E-2): the users that a row cites, found as the import finds them (by email, in the company).
     */
    private List<User> users(Company company, String primaryUserEmail, Collection<String> emails) {
        List<User> users = new ArrayList<>();
        userService.findByEmailAndCompany(primaryUserEmail, company.getId()).ifPresent(users::add);
        if (emails != null) emails.forEach(email -> userService.findByEmailAndCompany(email, company.getId())
                .ifPresent(users::add));
        return users;
    }

    /**
     * P-18 (E-2): the asset that a row cites by name, found as the import finds it: in the batch first, then in the
     * company.
     */
    private Asset parentAsset(String name, Company company, Map<String, Asset> batch) {
        if (name == null || name.isEmpty()) return null;
        Asset asset = batch.get(name);
        return asset != null ? asset : assetNamed(name, company);
    }

    /**
     * P-18 (E-2, D-2): the users and the asset that a row of work orders or preventive maintenances gives are not
     * disabled or archived, unless {@code existing} (null for a new record) already has them.
     */
    private void rejectRetiredReferences(WorkOrderBase existing, WorkOrderImportDTO dto, Company company) {
        UserService.rejectRetiredLoaded(existing == null ? List.of()
                        : WorkOrderService.assignedUsers(existing.getPrimaryUser(), existing.getAssignedTo()),
                users(company, dto.getPrimaryUserEmail(), dto.getAssignedToEmails()));
        assetService.rejectRetired(existing == null ? List.of() : Collections.singletonList(existing.getAsset()),
                Collections.singletonList(assetNamed(dto.getAssetName(), company)));
    }

    private Asset assetNamed(String name, Company company) {
        if (name == null || name.isEmpty()) return null;
        return assetService.findByNameIgnoreCaseAndCompany(name, company.getId()).stream().findFirst().orElse(null);
    }

    @Transactional
    public ImportResponse importLocations(List<LocationImportDTO> toImport, Company company) {
        List<Long> idsToCheck = toImport.stream()
                .map(LocationImportDTO::getId)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toList());

        Map<Long, Location> existingLocations = new java.util.HashMap<>();
        if (!idsToCheck.isEmpty()) {
            locationService.findByIdsAndCompany(idsToCheck, company.getId())
                    .forEach(location -> existingLocations.put(location.getId(), location));
        }

        Map<String, Location> locationsByName = new java.util.HashMap<>();
        List<Location> locationsToSave = new java.util.ArrayList<>();
        List<ImportRejection> rejected = new ArrayList<>();
        Map<LocationImportDTO, Integer> rows = positions(toImport);
        int created = 0;
        int updated = 0;

        for (LocationImportDTO dto : LocationService.orderLocations(toImport)) {
            Long id = dto.getId();
            Location existing = id == null ? null : existingLocations.get(id);
            // P-18 (E-2): a disabled worker is not given anew
            Optional<ImportRejection> rejection = rejection(rows.get(dto), id, () -> UserService.rejectRetiredLoaded(
                    existing == null ? List.of() : existing.getWorkers(), users(company, null,
                            dto.getWorkersEmails())));
            if (rejection.isPresent()) {
                rejected.add(rejection.get());
                continue;
            }
            Location location = existing == null ? new Location() : existing;
            if (existing == null) created++;
            else updated++;

            locationService.setLocationFieldsFromImportDto(location, dto, company, locationsByName);
            locationsToSave.add(location);
            locationsByName.put(location.getName(), location);
        }

        if (!locationsToSave.isEmpty()) {
            locationService.saveAll(locationsToSave);
        }

        rejected.sort(Comparator.comparingInt(ImportRejection::getRow));
        return ImportResponse.builder()
                .created(created)
                .updated(updated)
                .rejected(rejected)
                .build();
    }

    @Transactional
    public ImportResponse importMeters(List<MeterImportDTO> toImport, Company company) {
        List<Long> idsToCheck = toImport.stream()
                .map(MeterImportDTO::getId)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toList());

        Map<Long, Meter> existingMeters = new java.util.HashMap<>();
        if (!idsToCheck.isEmpty()) {
            meterService.findByIdsAndCompany(idsToCheck, company.getId())
                    .forEach(meter -> existingMeters.put(meter.getId(), meter));
        }

        List<Meter> metersToSave = new java.util.ArrayList<>();
        List<ImportRejection> rejected = new ArrayList<>();
        int created = 0;
        int updated = 0;

        for (int row = 1; row <= toImport.size(); row++) {
            MeterImportDTO dto = toImport.get(row - 1);
            Long id = dto.getId();
            Meter existing = id == null ? null : existingMeters.get(id);
            // P-18 (E-2): a disabled user or an archived asset is not given anew
            Optional<ImportRejection> rejection = rejection(row, id, () -> {
                UserService.rejectRetiredLoaded(existing == null ? List.of() : existing.getUsers(),
                        users(company, null, dto.getUsersEmails()));
                assetService.rejectRetired(existing == null ? List.of()
                                : Collections.singletonList(existing.getAsset()),
                        Collections.singletonList(assetNamed(dto.getAssetName(), company)));
            });
            if (rejection.isPresent()) {
                rejected.add(rejection.get());
                continue;
            }
            Meter meter = existing == null ? new Meter() : existing;
            if (existing == null) created++;
            else updated++;

            meterService.importMeter(meter, dto, company);
            metersToSave.add(meter);
        }

        if (!metersToSave.isEmpty()) {
            meterService.saveAll(metersToSave);
        }

        return ImportResponse.builder()
                .created(created)
                .updated(updated)
                .rejected(rejected)
                .build();
    }

    /**
     * Creates or updates the parts. P-16b (PPB-30, C-1): the stock of each one is set through its ledger, with a
     * movement with origin IMPORT by {@code actor} (who asked for the import) for the difference; without
     * difference, or for a part without stock control, nothing is recorded.
     */
    @Transactional
    public ImportResponse importParts(List<PartImportDTO> toImport, Company company, User actor) {
        // Check for duplicate non-null barcodes
        Set<String> seenBarcodes = new java.util.HashSet<>();
        for (PartImportDTO dto : toImport) {
            if (dto.getBarcode() != null && !dto.getBarcode().isEmpty()) {
                if (!seenBarcodes.add(dto.getBarcode())) {
                    throw new IllegalArgumentException("Duplicate barcode found: " + dto.getBarcode());
                }
            }
        }

        List<Long> idsToCheck = toImport.stream()
                .map(PartImportDTO::getId)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toList());

        Map<Long, Part> existingParts = new java.util.HashMap<>();
        if (!idsToCheck.isEmpty()) {
            partService.findByIdsAndCompany(idsToCheck, company.getId())
                    .forEach(part -> existingParts.put(part.getId(), part));
        }

        List<Part> partsToSave = new java.util.ArrayList<>();
        List<PartImportDTO> rowsToSave = new java.util.ArrayList<>();
        List<ImportRejection> rejected = new ArrayList<>();
        int created = 0;
        int updated = 0;

        for (int row = 1; row <= toImport.size(); row++) {
            PartImportDTO dto = toImport.get(row - 1);
            Long id = dto.getId();
            Part existing = id == null ? null : existingParts.get(id);
            // P-18 (E-2): a disabled user is not given anew
            Optional<ImportRejection> rejection = rejection(row, id, () -> UserService.rejectRetiredLoaded(
                    existing == null ? List.of() : existing.getAssignedTo(), users(company, null,
                            dto.getAssignedToEmails())));
            if (rejection.isPresent()) {
                rejected.add(rejection.get());
                continue;
            }
            Part part = existing == null ? new Part() : existing;
            if (existing == null) created++;
            else updated++;

            partService.importPart(part, dto, company);
            partsToSave.add(part);
            rowsToSave.add(dto);
        }

        if (!partsToSave.isEmpty()) {
            List<Part> savedParts = partService.saveAll(partsToSave);
            // the parts are locked in the order of their identifiers
            IntStream.range(0, savedParts.size()).boxed()
                    .sorted(Comparator.comparing(i -> savedParts.get(i).getId()))
                    .forEach(i -> {
                        Part part = partLedgerService.lock(savedParts.get(i).getId());
                        partLedgerService.record(part, rowsToSave.get(i).getQuantity() - part.getQuantity(),
                                PartTransactionOrigin.IMPORT, PartLedgerService.Movement.by(actor));
                    });
        }

        return ImportResponse.builder()
                .created(created)
                .updated(updated)
                .rejected(rejected)
                .build();
    }

    public ImportResponse importPreventiveMaintenances(List<PreventiveMaintenanceImportDTO> toImport, Company company) {
        int created = 0;
        int updated = 0;
        List<ImportRejection> rejected = new ArrayList<>();
        for (int row = 1; row <= toImport.size(); row++) {
            PreventiveMaintenanceImportDTO pmImportDTO = toImport.get(row - 1);
            Long id = pmImportDTO.getId();
            PreventiveMaintenance existing = id == null ? null
                    : preventiveMaintenanceService.findByIdAndCompany(id, company.getId()).orElse(null);
            // P-18 (E-2): a disabled user or an archived asset is not given anew; checked before the row is saved
            Optional<ImportRejection> rejection = rejection(row, id, () -> rejectRetiredReferences(existing,
                    pmImportDTO, company));
            if (rejection.isPresent()) {
                rejected.add(rejection.get());
                continue;
            }
            if (existing == null) created++;
            else updated++;
            preventiveMaintenanceService.importPreventiveMaintenance(existing == null ? new PreventiveMaintenance()
                    : existing, pmImportDTO, company);
        }
        return ImportResponse.builder()
                .created(created)
                .updated(updated)
                .rejected(rejected)
                .build();
    }

}

