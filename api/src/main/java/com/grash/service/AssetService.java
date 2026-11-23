package com.grash.service;

import com.grash.advancedsearch.FilterField;
import com.grash.advancedsearch.SearchCriteria;
import com.grash.advancedsearch.SpecificationBuilder;
import com.grash.dto.AssetPatchDTO;
import com.grash.dto.AssetPostDTO;
import com.grash.dto.AssetShowDTO;
import com.grash.dto.cutomField.CustomFieldValuePostDTO;
import com.grash.dto.imports.AssetImportDTO;
import com.grash.dto.license.LicenseEntitlement;
import com.grash.exception.CustomException;
import com.grash.mapper.AssetMapper;
import com.grash.model.*;
import com.grash.model.enums.AssetStatus;
import com.grash.model.enums.AuditAction;
import com.grash.model.enums.AuditSource;
import com.grash.model.enums.CustomFieldEntityType;
import com.grash.model.enums.EntityType;
import com.grash.model.enums.NotificationType;
import com.grash.model.enums.webhook.WebhookEvent;
import com.grash.repository.AssetRepository;
import com.grash.repository.WorkOrderRepository;
import com.grash.service.CustomFieldValueService;
import com.grash.utils.Helper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.grash.utils.Consts.usageBasedLicenseLimits;

@Service
@RequiredArgsConstructor
public class AssetService {
    public static final String ASSET_IN_USE = "ASSET_IN_USE";
    private static final String ASSET_IN_USE_MESSAGE = "This asset is used by other records. Archive it instead of "
            + "deleting it";

    private final AssetRepository assetRepository;
    private LocationService locationService;
    private final FileService fileService;
    private final AssetCategoryService assetCategoryService;
    private final DeprecationService deprecationService;
    private final UserService userService;
    private final CustomerService customerService;
    private final VendorService vendorService;
    private LaborService laborService;
    private final NotificationService notificationService;
    private final TeamService teamService;
    private final PartService partService;
    private final AssetMapper assetMapper;
    private final EntityManager em;
    private final AssetDowntimeService assetDowntimeService;
    private WorkOrderService workOrderService;
    private final MessageSource messageSource;
    private final CustomSequenceService customSequenceService;
    private final DeletionService deletionService;
    private final WorkOrderRepository workOrderRepository;
    private final LicenseService licenseService;
    private WebhookDispatchService webhookDispatchService;
    private final CustomFieldValueService customFieldValueService;
    private final AuditLogService auditLogService;

    @Autowired
    public void setDeps(@Lazy LocationService locationService, @Lazy LaborService laborService,
                        @Lazy WorkOrderService workOrderService,
                        @Lazy WebhookDispatchService webhookDispatchService
    ) {
        this.locationService = locationService;
        this.laborService = laborService;
        this.workOrderService = workOrderService;
        this.webhookDispatchService = webhookDispatchService;
    }

    @Transactional
    public Asset create(Asset asset, User user) {
        checkUsageBasedLimit(user.getCompany());
        Company company = user.getCompany();
        if (asset instanceof AssetPostDTO assetPostDTO) {
            asset = assetMapper.fromPostDto(assetPostDTO);
            if (assetPostDTO.getCustomFields() != null && !assetPostDTO.getCustomFields().isEmpty()) {
                setAssetCustomFields(asset, assetPostDTO.getCustomFields(), company);
            }
        }
        if (asset.getParentAsset() != null && !licenseService.hasEntitlement(LicenseEntitlement.ASSET_HIERARCHY))
            throw new CustomException("You need a license to add a child asset to another asset.",
                    HttpStatus.FORBIDDEN);
        asset.setCustomId(getAssetNumber(company));
        if ((asset.getBarCode() == null || asset.getBarCode().isBlank()) && Boolean.TRUE.equals(user.getCompany().getCompanySettings().getGeneralPreferences().getAutoGenerateAssetBarcode())) {
            asset.setBarCode(UUID.randomUUID().toString());
        }
        Asset savedAsset = assetRepository.saveAndFlush(asset);
        em.refresh(savedAsset);
        Map<String, Object> webhookPayload = new HashMap<>();
        webhookPayload.put("assetId", savedAsset.getId());
        Object serializedAsset = assetMapper.toShowDto(savedAsset, this);
        webhookDispatchService.dispatchWebhook(company, WebhookEvent.NEW_ASSET, webhookPayload,
                "newAsset", serializedAsset, null, null, null, null, null);
        return savedAsset;
    }

    /**
     * P-20 (E-5): the folio of an asset can be written by hand, so a number already used is skipped.
     */
    private String getAssetNumber(Company company) {
        String folio;
        do {
            folio = "A" + String.format("%06d", customSequenceService.getNextAssetSequence(company));
        } while (assetRepository.existsByCompany_IdAndCustomId(company.getId(), folio));
        return folio;
    }

    public boolean existsByCustomIdAndCompany(String customId, Long companyId) {
        return assetRepository.existsByCompany_IdAndCustomId(companyId, customId);
    }

    private void setAssetCustomFields(Asset asset, List<CustomFieldValuePostDTO> customFieldValuePostDTOS,
                                      Company company) {
        customFieldValueService.setCustomFields(
                asset,
                asset.getCustomFieldValues(),
                customFieldValuePostDTOS,
                company,
                CustomFieldEntityType.ASSET,
                cfv -> cfv.setAsset(asset)
        );
    }

    @Transactional
    public Asset update(Long id, AssetPatchDTO asset, Company company) {
        if (asset.getParentAsset() != null && !licenseService.hasEntitlement(LicenseEntitlement.ASSET_HIERARCHY))
            throw new CustomException("You need a license to add a child asset to another asset.",
                    HttpStatus.FORBIDDEN);
        if (assetRepository.existsById(id)) {
            Asset savedAsset = assetRepository.findById(id).get();
            AssetStatus previousStatus = savedAsset.getStatus();
            if (asset.getCustomFields() != null && !asset.getCustomFields().isEmpty()) {
                setAssetCustomFields(savedAsset, asset.getCustomFields(), company);
            }
            Asset patchedAsset = assetRepository.saveAndFlush(assetMapper.updateAsset(savedAsset, asset));
            em.refresh(patchedAsset);

            if (previousStatus != patchedAsset.getStatus()) {
                dispatchAssetStatusChangeWebhook(patchedAsset, previousStatus, patchedAsset.getStatus());
            }

            return patchedAsset;
        } else throw new CustomException("Not found", HttpStatus.NOT_FOUND);
    }

    /**
     * P-14b (E-1, D-5, PPB-07, PPB-23): archives the asset and records it, in one transaction. Only {@code archived}
     * changes: its work orders, meters, readings, children and its own fields stay as they are.
     */
    @Transactional
    public Asset archive(Long id, User actor) {
        return changeArchived(id, true, AuditAction.RETIRE, actor);
    }

    /**
     * P-14b (E-1, E-2, D-5): the inverse of {@link #archive}; nothing else changes either.
     */
    @Transactional
    public Asset restore(Long id, User actor) {
        return changeArchived(id, false, AuditAction.RESTORE, actor);
    }

    /**
     * P-14b (E-3, PPB-08): an archived asset is not accepted as a new reference. {@code requested} are the assets a
     * request sets; those already in {@code previous} are kept even if archived. Null elements are ignored.
     */
    public void rejectRetired(Collection<Asset> previous, Collection<Asset> requested) {
        if (requested == null) return;
        Set<Long> previousIds = new HashSet<>();
        if (previous != null) previous.stream().filter(Objects::nonNull).map(Asset::getId).forEach(previousIds::add);
        Set<Long> newIds = new HashSet<>();
        requested.stream().filter(Objects::nonNull).map(Asset::getId).filter(Objects::nonNull)
                .filter(assetId -> !previousIds.contains(assetId)).forEach(newIds::add);
        if (!newIds.isEmpty() && assetRepository.existsByIdInAndArchivedTrue(newIds))
            throw AssetStateGuard.notAssignable();
    }

    private Asset changeArchived(Long id, boolean archived, AuditAction action, User actor) {
        Asset asset = assetRepository.findById(id).orElseThrow(() -> new CustomException("Not found",
                HttpStatus.NOT_FOUND));
        asset.setArchived(archived);
        Asset saved = assetRepository.saveAndFlush(asset);
        auditLogService.record(new AuditTarget(EntityType.ASSET, saved.getId(), saved.getCustomId(), saved.getName(),
                saved.getCompany().getId()), actor, action, AuditSource.API, Map.of(), 0);
        return saved;
    }

    private void checkUsageBasedLimit(Company company) {
        Integer threshold = usageBasedLicenseLimits.get(LicenseEntitlement.UNLIMITED_ASSETS);
        if (!licenseService.hasEntitlement(LicenseEntitlement.UNLIMITED_ASSETS)
                && assetRepository.hasMoreThan(company.getId(), threshold.longValue() - 1
        ))
            throw new CustomException("You need a license to add a new asset. Free Limit reached: " + threshold,
                    HttpStatus.FORBIDDEN);
    }

    public Asset save(Asset asset) {
        return assetRepository.save(asset);
    }

    public List<Asset> saveAll(List<Asset> assets) {
        return assetRepository.saveAll(assets);
    }

    public Collection<Asset> getAll() {
        return assetRepository.findAll();
    }

    /**
     * Deletes the asset and records it in the audit log, in one transaction (P-10). An asset with a dependent is not
     * deleted: 409 ASSET_IN_USE with every blocking type (PPB-02, P-14). Its downtimes, custom field values and
     * associations go with it.
     */
    @Transactional
    public void delete(Asset asset, User actor) {
        Long id = asset.getId();
        deletionService.delete(new AuditTarget(EntityType.ASSET, id, asset.getCustomId(), asset.getName(),
                        asset.getCompany().getId()), actor, AuditSource.API,
                DeletionRule.inUse(ASSET_IN_USE, ASSET_IN_USE_MESSAGE)
                        .blockedBy(EntityType.ASSET, () -> deletionService.isCited("asset", "parent_asset_id", id))
                        .blockedBy(EntityType.METER, () -> deletionService.isCited("meter", "asset_id", id))
                        .blockedBy(EntityType.WORK_ORDER, () -> workOrderRepository.existsByAsset_Id(id))
                        // D-P14-5: work_order_aud.asset_id has no foreign key; only this check protects it
                        .blockedBy(EntityType.WORK_ORDER_HISTORY,
                                () -> deletionService.isCitedByAnEarlierRevision("asset_id", id))
                        .blockedBy(EntityType.REQUEST, () -> deletionService.isCited("request", "asset_id", id))
                        .blockedBy(EntityType.PREVENTIVE_MAINTENANCE,
                                () -> deletionService.isCited("preventive_maintenance", "asset_id", id))
                        .blockedBy(EntityType.TASK_BASE, () -> deletionService.isCited("task_base", "asset_id", id))
                        .blockedBy(EntityType.WORK_ORDER_METER_TRIGGER,
                                () -> deletionService.isCited("work_order_meter_trigger", "asset_id", id))
                        .blockedBy(EntityType.WORKFLOW_ACTION,
                                () -> deletionService.isCited("workflow_action", "asset_id", id))
                        .blockedBy(EntityType.WORKFLOW_CONDITION,
                                () -> deletionService.isCited("workflow_condition", "asset_id", id))
                        .blockedBy(EntityType.REQUEST_PORTAL_FIELD,
                                () -> deletionService.isCited("request_portal_field", "asset_id", id))
                        .blockedBy(EntityType.USER,
                                () -> deletionService.isLinkedToRetiredUser("t_asset_user_associations", "id_asset",
                                        id)),
                () -> assetRepository.deleteById(id));
    }

    public Optional<Asset> findById(Long id) {
        return assetRepository.findById(id);
    }

    public Optional<Asset> findByNfcIdAndCompany(String nfcId, Long companyId) {
        return assetRepository.findByNfcIdAndCompany_Id(nfcId, companyId);
    }

    public List<Asset> findByCompany(Long id) {
        return assetRepository.findByCompany_Id(id);
    }

    public Page<Asset> findByCompanyForExport(Long companyId, Pageable pageable) {
        return assetRepository.findByCompanyForExport(companyId, pageable);
    }

    public List<Asset> findByCompany(Long id, Sort sort) {
        return assetRepository.findByCompany_Id(id, sort);
    }

    public List<Asset> findByCompanyAndParentAssetNull(Long id, Pageable pageable) {
        return assetRepository.findByCompany_IdAndParentAssetIsNull(id, pageable).toList();
    }

    /**
     * P-14b (E-4, D-3, PPB-09): the assets that the selectors and the operational lists offer leave out the archived
     * ones; the detail by id, the history and the exports keep them.
     */
    public List<Asset> findActiveByCompany(Long companyId) {
        return assetRepository.findByCompany_IdAndArchivedFalse(companyId);
    }

    public List<Asset> findActiveByLocation(Long locationId) {
        return assetRepository.findByLocation_IdAndArchivedFalse(locationId);
    }

    public Page<Asset> findActiveRoots(Long companyId, Pageable pageable) {
        return assetRepository.findByCompany_IdAndParentAssetIsNullAndArchivedFalse(companyId, pageable);
    }

    public Page<Asset> findActiveChildren(Long id, Pageable pageable) {
        return assetRepository.findByParentAsset_IdAndArchivedFalse(id, pageable);
    }


    public List<Asset> findByCompanyAndBefore(Long id, Date date) {
        return assetRepository.findByCompany_IdAndCreatedAtBefore(id, date);
    }

    public double getTotalAcquisitionCost(Long companyId, Date end) {
        return assetRepository.getTotalAcquisitionCost(companyId, end);
    }

    public Page<Asset> findAssetChildren(Long id, Pageable pageable) {
        return assetRepository.findByParentAsset_Id(id, pageable);
    }

    public void notify(Asset asset, String title, String message) {
        notificationService.createMultiple(asset.getUsers().stream().map(user -> new Notification(message, user,
                NotificationType.ASSET, asset.getId())).collect(Collectors.toList()), true, title);
    }

    public void patchNotify(Asset oldAsset, Asset newAsset, Locale locale) {
        String title = messageSource.getMessage("new_assignment", null, locale);
        String message = messageSource.getMessage("notification_asset_assigned", new Object[]{newAsset.getName()},
                locale);
        notificationService.createMultiple(oldAsset.getNewUsersToNotify(newAsset.getUsers()).stream().map(user ->
                new Notification(message, user, NotificationType.ASSET, newAsset.getId())).collect(Collectors.toList()), true, title);
    }

    public List<Asset> findByLocation(Long id) {
        return assetRepository.findByLocation_Id(id);
    }

    private void stopAssetDowntime(Asset asset) {
        // P-14b (E-5, PPB-15): an archived asset keeps its downtimes and its status
        if (!AssetStateGuard.admits(asset)) return;
        Collection<AssetDowntime> assetDowntimes = assetDowntimeService.findByAsset(asset.getId());
        Optional<AssetDowntime> optionalRunningDowntime =
                assetDowntimes.stream().filter(downtime -> downtime.getDuration() == 0).findFirst();

        if (optionalRunningDowntime.isPresent()) {
            AssetDowntime runningDowntime = optionalRunningDowntime.get();
            runningDowntime.setDuration(Helper.getDateDiff(runningDowntime.getStartsOn(), new Date(),
                    TimeUnit.SECONDS));
            assetDowntimeService.save(runningDowntime);
        }

        AssetStatus previousStatus = asset.getStatus();
        asset.setStatus(AssetStatus.OPERATIONAL);
        save(asset);

        if (previousStatus != AssetStatus.OPERATIONAL) {
            dispatchAssetStatusChangeWebhook(asset, previousStatus, AssetStatus.OPERATIONAL);
        }
    }

    private void recursivelyStopChildrenDowntime(Asset parentAsset) {
        List<Asset> children = findAssetChildren(parentAsset.getId(), Pageable.unpaged()).getContent();
        for (Asset child : children) {
            stopAssetDowntime(child);
            recursivelyStopChildrenDowntime(child);
        }
    }

    public void stopDownTime(Long id, Locale locale) {
        Asset savedAsset = findById(id).orElseThrow(() -> new EntityNotFoundException("Asset not found"));
        stopAssetDowntime(savedAsset);
        recursivelyStopChildrenDowntime(savedAsset);
        if (!AssetStateGuard.admits(savedAsset)) return;
        String message = messageSource.getMessage("notification_asset_operational",
                new Object[]{savedAsset.getName()}, locale);
        notify(savedAsset, message, messageSource.getMessage("asset_status_change", null, locale));
    }

    public void triggerDownTime(Long id, Locale locale, AssetStatus status) {
        Date now = new Date();
        Asset asset = findById(id).get();
        // P-14b (E-5, PPB-15): no downtime is opened on an archived asset of the chain, and its status stays
        if (!AssetStateGuard.admits(asset)) return;
        AssetStatus previousAssetStatus = asset.getStatus();
        createAssetDowntime(asset, now, asset.getCompany());
        Asset parentAsset = asset.getParentAsset();
        while (parentAsset != null) {
            if (!AssetStateGuard.admits(parentAsset)) {
                parentAsset = parentAsset.getParentAsset();
                continue;
            }
            createAssetDowntime(parentAsset, now, asset.getCompany());
            if (!parentAsset.getStatus().isReallyDown()) {
                AssetStatus previousParentStatus = parentAsset.getStatus();
                parentAsset.setStatus(status);
                save(parentAsset);
                dispatchAssetStatusChangeWebhook(parentAsset, previousParentStatus, status);
            }
            parentAsset = parentAsset.getParentAsset();
        }
        asset.setStatus(status);
        save(asset);
        dispatchAssetStatusChangeWebhook(asset, previousAssetStatus, status);
        String message = messageSource.getMessage("notification_asset_down", new Object[]{asset.getName()}, locale);
        notify(asset, message, messageSource.getMessage("asset_status_change", null, locale));

    }

    private void createAssetDowntime(Asset asset, Date startsOn, Company company) {
        AssetDowntime downtime = AssetDowntime.builder()
                .startsOn(startsOn)
                .asset(asset)
                .build();
        downtime.setCompany(company);
        assetDowntimeService.create(downtime, false);
    }

    public boolean isAssetInCompany(Asset asset, long companyId, boolean optional) {
        if (optional) {
            Optional<Asset> optionalAsset = asset == null ? Optional.empty() : findById(asset.getId());
            return asset == null || (optionalAsset.isPresent() && optionalAsset.get().getCompany().getId().equals(companyId));
        } else {
            Optional<Asset> optionalAsset = findById(asset.getId());
            return optionalAsset.isPresent() && optionalAsset.get().getCompany().getId().equals(companyId);
        }
    }

    public Page<AssetShowDTO> findBySearchCriteria(SearchCriteria searchCriteria) {
        // P-14b (E-4, PPB-09): without a filter on archived, the archived assets are left out
        if (searchCriteria.getFilterFields().stream().noneMatch(filter -> "archived".equals(filter.getField())))
            searchCriteria.getFilterFields().add(FilterField.builder().field("archived").operation("eq").value(false)
                    .values(new ArrayList<>()).build());
        SpecificationBuilder<Asset> builder = new SpecificationBuilder<>();
        searchCriteria.getFilterFields().forEach(builder::with);
        Pageable page = PageRequest.of(searchCriteria.getPageNum(), searchCriteria.getPageSize(),
                searchCriteria.getDirection(), searchCriteria.getSortField());
        return assetRepository.findAll(builder.build(), page).map(asset -> assetMapper.toShowDto(asset, this));
    }

    public List<Asset> findByNameIgnoreCaseAndCompany(String assetName, Long companyId) {
        return assetRepository.findByNameIgnoreCaseAndCompany_Id(assetName, companyId);
    }

    public void setAssetFieldsFromImportDto(Asset asset, AssetImportDTO dto, Company company,
                                            Map<String, Asset> assetsByName) {
        checkUsageBasedLimit(company);
        if (!licenseService.hasEntitlement(LicenseEntitlement.ASSET_HIERARCHY) && dto.getParentAssetName() != null && !dto.getParentAssetName().isEmpty())
            throw new CustomException("You need a license to import assets with hierarchy", HttpStatus.FORBIDDEN);
        Long companyId = company.getId();
        asset.setCompany(company);
        asset.setArea(dto.getArea());
        if (dto.getBarCode() != null && !dto.getBarCode().trim().isEmpty()) {
            Optional<Asset> optionalAssetWithSameBarCode = findByBarcodeAndCompany(dto.getBarCode(), company.getId());
            if (optionalAssetWithSameBarCode.isPresent()) {
                boolean hasError = false;
                if (dto.getId() == null) {//creation
                    hasError = true;
                } else {//update
                    if (!dto.getId().equals(optionalAssetWithSameBarCode.get().getId())) {
                        hasError = true;
                    }
                }
                if (hasError)
                    throw new CustomException("Asset with same barcode exists: " + dto.getBarCode(),
                            HttpStatus.NOT_ACCEPTABLE);
            }
        }
        asset.setBarCode(dto.getBarCode());
        asset.setArea(dto.getArea());
        asset.setArchived(Helper.getBooleanFromString(dto.getArchived()));
        asset.setDescription(dto.getDescription());
        asset.setModel(dto.getModel());
        asset.setPower(dto.getPower());
        // P-20 (CA-P20-10): an asset updated by the import keeps its folio
        if (asset.getCustomId() == null) asset.setCustomId(getAssetNumber(company));
        asset.setManufacturer(dto.getManufacturer());
        Optional<Location> optionalLocation = locationService.findByNameIgnoreCaseAndCompany(dto.getLocationName(),
                companyId).stream().findFirst();
        optionalLocation.ifPresent(asset::setLocation);
        // Check parent asset in batch first, then in database
        if (dto.getParentAssetName() != null && !dto.getParentAssetName().isEmpty()) {
            Asset parentAsset = assetsByName != null ? assetsByName.get(dto.getParentAssetName()) : null;
            if (parentAsset == null) {
                parentAsset = findByNameIgnoreCaseAndCompany(dto.getParentAssetName(), companyId)
                        .stream().findFirst().orElse(null);
            }
            asset.setParentAsset(parentAsset);
        }
        if (dto.getCategory() != null && !dto.getCategory().isBlank()) {
            AssetCategory category = assetCategoryService.getOrCreate(dto.getCategory(), company.getCompanySettings());
            asset.setCategory(category);
        }
        asset.setName(dto.getName());
        Optional<User> optionalPrimaryUser = userService.findByEmailAndCompany(dto.getPrimaryUserEmail(), companyId);
        optionalPrimaryUser.ifPresent(asset::setPrimaryUser);
        asset.setWarrantyExpirationDate(Helper.getDateFromExcelDate(dto.getWarrantyExpirationDate()));
        asset.setAdditionalInfos(dto.getAdditionalInfos());
        asset.setSerialNumber(dto.getSerialNumber());
        List<User> assignedTo = new ArrayList<>();
        dto.getAssignedToEmails().forEach(email -> {
            Optional<User> optionalUser1 = userService.findByEmailAndCompany(email, companyId);
            optionalUser1.ifPresent(assignedTo::add);
        });
        asset.setAssignedTo(assignedTo);
        List<Team> teams = new ArrayList<>();
        dto.getTeamsNames().forEach(teamName -> {
            Optional<Team> optionalTeam = teamService.findByNameIgnoreCaseAndCompany(teamName, companyId);
            optionalTeam.ifPresent(teams::add);
        });
        asset.setTeams(teams);
        asset.setStatus(AssetStatus.getAssetStatusFromString(dto.getStatus(), Helper.getLocale(company),
                messageSource));
        asset.setAcquisitionCost(dto.getAcquisitionCost());
        List<Customer> customers = new ArrayList<>();
        dto.getCustomersNames().forEach(name -> {
            Optional<Customer> optionalCustomer = customerService.findByNameIgnoreCaseAndCompany(name, companyId);
            optionalCustomer.ifPresent(customers::add);
        });
        asset.setCustomers(customers);
        List<Vendor> vendors = new ArrayList<>();
        dto.getVendorsNames().forEach(name -> {
            Optional<Vendor> optionalVendor = vendorService.findByNameIgnoreCaseAndCompany(name, companyId);
            optionalVendor.ifPresent(vendors::add);
        });
        asset.setVendors(vendors);
        List<Part> parts = new ArrayList<>();
        dto.getPartsNames().forEach(name -> {
            Optional<Part> optionalPart = partService.findByNameIgnoreCaseAndCompany(name, companyId);
            optionalPart.ifPresent(parts::add);
        });
        asset.setParts(parts);

//        assetRepository.save(asset);
    }

    public Optional<Asset> findByIdAndCompany(Long id, Long companyId) {
        return assetRepository.findByIdAndCompany_Id(id, companyId);
    }

    public List<Asset> findByIdsAndCompany(List<Long> ids, Long companyId) {
        return assetRepository.findByIdInAndCompany_Id(ids, companyId);
    }

    public Optional<Asset> findByBarcodeAndCompany(String data, Long id) {
        return assetRepository.findByBarCodeAndCompany_Id(data, id);
    }

    public static List<AssetImportDTO> orderAssets(List<AssetImportDTO> assets) {
        Map<String, List<AssetImportDTO>> assetMap = new HashMap<>();
        List<AssetImportDTO> identifiedTopLevelAssets = new ArrayList<>();

        Set<String> allAssetNames = new HashSet<>();
        for (AssetImportDTO asset : assets) {
            if (asset.getName() != null) { // Guard against assets with null names if possible
                allAssetNames.add(asset.getName());
            }
        }

        // Group assets by parent name and identify top-level assets
        // Using a HashSet here to ensure we only consider each unique asset object once
        // for building the map and topLevelAssets, in case the input list has duplicate object references.
        Set<AssetImportDTO> distinctInputAssets = new HashSet<>(assets);

        for (AssetImportDTO asset : distinctInputAssets) { // Iterate over unique asset objects
            String parentName = asset.getParentAssetName();
            assetMap.computeIfAbsent(parentName, k -> new ArrayList<>()).add(asset);

            // An asset is top-level if it has no parent,
            // or its declared parent doesn't exist in the provided list of assets.
            if (parentName == null || !allAssetNames.contains(parentName)) {
                identifiedTopLevelAssets.add(asset);
            }
        }

        List<AssetImportDTO> orderedAssets = new ArrayList<>();
        Set<AssetImportDTO> visited = new HashSet<>(); // Keep track of visited assets

        // Process identified top-level assets.
        // The `visited` set will ensure each asset is added only once,
        // even if it appears multiple times in `identifiedTopLevelAssets`
        // (e.g., multiple distinct orphan objects point to the same non-existent parent)
        // or if children of different top-level assets overlap due to same names.
        orderAssetsRecursive(assetMap, identifiedTopLevelAssets, orderedAssets, visited);

        return orderedAssets;
    }

    private static void orderAssetsRecursive(Map<String, List<AssetImportDTO>> assetMap,
                                             List<AssetImportDTO> currentLevelAssets,
                                             List<AssetImportDTO> orderedAssets,
                                             Set<AssetImportDTO> visited) {
        if (currentLevelAssets == null) {
            return;
        }
        for (AssetImportDTO asset : currentLevelAssets) {
            // Only process and add the asset if it hasn't been visited yet
            if (visited.add(asset)) { // .add() returns true if the element was new to the set
                orderedAssets.add(asset);
                List<AssetImportDTO> children = assetMap.get(asset.getName());
                if (children != null) {
                    orderAssetsRecursive(assetMap, children, orderedAssets, visited);
                }
            }
        }
    }


    /**
     * P-14b (D-3): whether the hierarchy shows children under the asset; archived children are not shown.
     */
    public Boolean hasChildren(Long assetId) {
        return assetRepository.countByParentAsset_IdAndArchivedFalse(assetId) > 0;
    }

    public long getMTBF(Long assetId, Date start, Date end) {
        List<AssetDowntime> downtimes = assetDowntimeService.findByAssetAndStartsOnBetween(assetId, start, end);
        downtimes.sort(Comparator.comparing(AssetDowntime::getStartsOn));
        if (downtimes.size() < 2) {
            return 0L;
        }

        long intervalsSum = 0;
        int numberOfIntervals = downtimes.size() - 1;

        for (int i = 0; i < downtimes.size() - 1; i++) {
            AssetDowntime currentDowntime = downtimes.get(i);
            AssetDowntime nextDowntime = downtimes.get(i + 1);

            long interval = Helper.getDateDiff(currentDowntime.getEndsOn(), nextDowntime.getStartsOn(), TimeUnit.DAYS);
            intervalsSum += interval;
        }

        return intervalsSum / numberOfIntervals;
    }

    public long getMTTR(Long assetId, Date start, Date end) {
        Collection<WorkOrder> workOrders = workOrderService.findByAssetAndCreatedAtBetween(assetId, start, end);
        List<Labor> labors = new ArrayList<>();
        for (WorkOrder workOrder : workOrders) {
            labors.addAll(laborService.findByWorkOrder(workOrder.getId()));
        }
        return workOrders.isEmpty() ? 0 : (Labor.getTotalWorkDuration(labors) / 60) / workOrders.size();
    }

    public long getDowntime(Long assetId, Date start, Date end) {
        Collection<AssetDowntime> downtimes = assetDowntimeService.findByAssetAndStartsOnBetween(assetId, start, end);
        return downtimes.stream().mapToLong(AssetDowntime::getDuration).sum();
    }

    public long getUptime(Long assetId, Date start, Date end) {
        Asset asset = findById(assetId).get();
        Date now = new Date();
        Date effectiveStart = start.after(asset.getRealCreatedAt()) ? start : asset.getRealCreatedAt();
        Date effectiveEnd = end.before(now) ? end : now;
        if (effectiveStart.after(effectiveEnd)) {
            return 0;
        }
        long effectiveDuration = Helper.getDateDiff(effectiveStart, effectiveEnd, TimeUnit.SECONDS);
        return effectiveDuration - getDowntime(assetId, effectiveStart, effectiveEnd);
    }

    public double getTotalCost(Long assetId, Date start, Date end, Boolean includeLaborCost) {
        Collection<WorkOrder> workOrders = workOrderService.findByAssetAndCreatedAtBetween(assetId, start, end);
        return workOrderService.getAllCost(workOrders, includeLaborCost);
    }

    private void dispatchAssetStatusChangeWebhook(Asset asset, AssetStatus previousStatus, AssetStatus newStatus) {
        Map<String, Object> webhookPayload = new HashMap<>();
        webhookPayload.put("assetId", asset.getId());
        webhookPayload.put("assetName", asset.getName());
        webhookPayload.put("previousStatus", previousStatus);
        webhookPayload.put("newStatus", newStatus);
        Object serializedAsset = assetMapper.toShowDto(asset, this);
        webhookDispatchService.dispatchWebhook(asset.getCompany(), WebhookEvent.ASSET_STATUS_CHANGE, webhookPayload,
                "changedAsset", serializedAsset, null, newStatus, null, null, null);
    }
}

