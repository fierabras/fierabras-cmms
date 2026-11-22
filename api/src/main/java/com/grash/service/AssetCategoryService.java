package com.grash.service;

import com.grash.dto.CategoryPatchDTO;
import com.grash.exception.CustomException;
import com.grash.mapper.AssetCategoryMapper;
import com.grash.model.AssetCategory;
import com.grash.model.CompanySettings;
import com.grash.model.User;
import com.grash.model.enums.AuditSource;
import com.grash.model.enums.EntityType;
import com.grash.repository.AssetCategoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AssetCategoryService {
    private final AssetCategoryRepository assetCategoryRepository;
    private final CompanySettingsService companySettingsService;
    private final AssetCategoryMapper assetCategoryMapper;
    private final DeletionService deletionService;

    public static final String ASSET_CATEGORY_IN_USE = "ASSET_CATEGORY_IN_USE";
    private static final String ASSET_CATEGORY_IN_USE_MESSAGE = "This asset category is used by other records and "
            + "cannot be deleted";

    public AssetCategory create(AssetCategory assetCategory, User user) {
        Optional<AssetCategory> categoryWithSameName =
                assetCategoryRepository.findByNameIgnoreCaseAndCompanySettings_Id(assetCategory.getName(),
                        user.getCompany().getCompanySettings().getId());
        if (categoryWithSameName.isPresent()) {
            throw new CustomException("AssetCategory with same name already exists", HttpStatus.NOT_ACCEPTABLE);
        }
        return assetCategoryRepository.save(assetCategory);
    }

    public AssetCategory update(Long id, CategoryPatchDTO assetCategory) {
        if (assetCategoryRepository.existsById(id)) {
            AssetCategory savedAssetCategory = assetCategoryRepository.findById(id).get();
            return assetCategoryRepository.save(assetCategoryMapper.updateAssetCategory(savedAssetCategory,
                    assetCategory));
        } else throw new CustomException("Not found", HttpStatus.NOT_FOUND);
    }

    public Collection<AssetCategory> getAll() {
        return assetCategoryRepository.findAll();
    }

    /**
     * P-14 (PPB-02, PPB-23): deletes the category and records it in the audit log, in one transaction. A category
     * used by an asset is not deleted: 409 ASSET_CATEGORY_IN_USE.
     */
    @Transactional
    public void delete(AssetCategory category, User actor) {
        Long id = category.getId();
        deletionService.delete(new AuditTarget(EntityType.ASSET_CATEGORY, id, null, category.getName(),
                        category.getCompanySettings().getCompany().getId()), actor, AuditSource.API,
                DeletionRule.inUse(ASSET_CATEGORY_IN_USE, ASSET_CATEGORY_IN_USE_MESSAGE)
                        .blockedBy(EntityType.ASSET, () -> deletionService.isCited("asset", "category_id", id)),
                () -> assetCategoryRepository.deleteById(id));
    }

    public Optional<AssetCategory> findById(Long id) {
        return assetCategoryRepository.findById(id);
    }

    public Collection<AssetCategory> findByCompanySettings(Long id) {
        return assetCategoryRepository.findByCompanySettings_Id(id);
    }

    public boolean isAssetCategoryInCompany(AssetCategory assetCategory, long companyId, boolean optional) {
        if (optional) {
            Optional<AssetCategory> optionalAssetCategory = assetCategory == null ? Optional.empty() :
                    findById(assetCategory.getId());
            return assetCategory == null || (optionalAssetCategory.isPresent() && optionalAssetCategory.get().getCompanySettings().getCompany().getId().equals(companyId));
        } else {
            Optional<AssetCategory> optionalAssetCategory = findById(assetCategory.getId());
            return optionalAssetCategory.isPresent() && optionalAssetCategory.get().getCompanySettings().getCompany().getId().equals(companyId);
        }
    }

    public Optional<AssetCategory> findByNameIgnoreCaseAndCompanySettings(String category, Long companySettingsId) {
        return assetCategoryRepository.findByNameIgnoreCaseAndCompanySettings_Id(category, companySettingsId);
    }

    public AssetCategory getOrCreate(String name, CompanySettings companySettings) {
        return assetCategoryRepository.findByNameIgnoreCaseAndCompanySettings_Id(name, companySettings.getId())
                .orElseGet(() -> assetCategoryRepository.save(new AssetCategory(name, companySettings)));
    }
}
