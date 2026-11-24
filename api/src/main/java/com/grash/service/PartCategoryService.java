package com.grash.service;

import com.grash.dto.CategoryPatchDTO;
import com.grash.exception.CustomException;
import com.grash.mapper.PartCategoryMapper;
import com.grash.model.CompanySettings;
import com.grash.model.PartCategory;
import com.grash.model.User;
import com.grash.model.enums.AuditSource;
import com.grash.model.enums.EntityType;
import com.grash.repository.PartCategoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class PartCategoryService {
    private final PartCategoryRepository partCategoryRepository;
    private final CompanySettingsService companySettingsService;
    private final PartCategoryMapper partCategoryMapper;
    private final DeletionService deletionService;

    public static final String PART_CATEGORY_IN_USE = "PART_CATEGORY_IN_USE";
    private static final String PART_CATEGORY_IN_USE_MESSAGE = "This part category is used by other records and "
            + "cannot be deleted";

    public PartCategory create(PartCategory partCategory, User user) {
        Optional<PartCategory> categoryWithSameName = partCategoryRepository.findByNameIgnoreCaseAndCompanySettings_Id(
                partCategory.getName(), user.getCompany().getCompanySettings().getId());
        if (categoryWithSameName.isPresent()) {
            throw new CustomException("PartCategory with same name already exists", HttpStatus.NOT_ACCEPTABLE);
        }
        return partCategoryRepository.save(partCategory);
    }

    public PartCategory update(Long id, CategoryPatchDTO partCategory) {
        if (partCategoryRepository.existsById(id)) {
            PartCategory savedPartCategory = partCategoryRepository.findById(id).get();
            return partCategoryRepository.save(partCategoryMapper.updatePartCategory(savedPartCategory, partCategory));
        } else throw new CustomException("Not found", HttpStatus.NOT_FOUND);
    }

    public Collection<PartCategory> getAll() {
        return partCategoryRepository.findAll();
    }

    /**
     * P-16 (PPB-02, PPB-23, C-03): deletes the category and records it in the audit log, in one transaction. A
     * category used by a part is not deleted: 409 PART_CATEGORY_IN_USE; the part and its movements do not change.
     */
    @Transactional
    public void delete(PartCategory category, User actor) {
        Long id = category.getId();
        deletionService.delete(new AuditTarget(EntityType.PART_CATEGORY, id, null, category.getName(),
                        category.getCompanySettings().getCompany().getId()), actor, AuditSource.API,
                DeletionRule.inUse(PART_CATEGORY_IN_USE, PART_CATEGORY_IN_USE_MESSAGE)
                        .blockedBy(EntityType.PART, () -> deletionService.isCited("part", "category_id", id)),
                () -> partCategoryRepository.deleteById(id));
    }

    public Optional<PartCategory> findById(Long id) {
        return partCategoryRepository.findById(id);
    }

    public Collection<PartCategory> findByCompanySettings(Long id) {
        return partCategoryRepository.findByCompanySettings_Id(id);
    }

    public Optional<PartCategory> findByNameIgnoreCaseAndCompanySettings(String category, Long companySettingsId) {
        return partCategoryRepository.findByNameIgnoreCaseAndCompanySettings_Id(category, companySettingsId);
    }

    public PartCategory getOrCreate(String name, CompanySettings companySettings) {
        return partCategoryRepository.findByNameIgnoreCaseAndCompanySettings_Id(name, companySettings.getId())
                .orElseGet(() -> partCategoryRepository.save(new PartCategory(name, companySettings)));
    }
}
