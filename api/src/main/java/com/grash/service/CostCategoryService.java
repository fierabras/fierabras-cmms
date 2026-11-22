package com.grash.service;

import com.grash.dto.CategoryPatchDTO;
import com.grash.exception.CustomException;
import com.grash.mapper.CostCategoryMapper;
import com.grash.model.CostCategory;
import com.grash.model.User;
import com.grash.model.enums.AuditSource;
import com.grash.model.enums.EntityType;
import com.grash.repository.CostCategoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class CostCategoryService {
    public static final String COST_CATEGORY_IN_USE = "COST_CATEGORY_IN_USE";
    private static final String COST_CATEGORY_IN_USE_MESSAGE = "This cost category is used by other records and "
            + "cannot be deleted";

    private final CostCategoryRepository costCategoryRepository;

    private final CostCategoryMapper costCategoryMapper;
    private final DeletionService deletionService;

    public CostCategory create(CostCategory costCategory, User user) {
        Optional<CostCategory> categoryWithSameName =
                costCategoryRepository.findByNameIgnoreCaseAndCompanySettings_Id(costCategory.getName(),
                        user.getCompany().getCompanySettings().getId());
        if (categoryWithSameName.isPresent()) {
            throw new CustomException("CostCategory with same name already exists", HttpStatus.NOT_ACCEPTABLE);
        }
        return costCategoryRepository.save(costCategory);
    }

    public CostCategory update(Long id, CategoryPatchDTO costCategory) {
        if (costCategoryRepository.existsById(id)) {
            CostCategory savedCostCategory = costCategoryRepository.findById(id).get();
            return costCategoryRepository.save(costCategoryMapper.updateCostCategory(savedCostCategory, costCategory));
        } else throw new CustomException("Not found", HttpStatus.NOT_FOUND);

    }

    public Collection<CostCategory> getAll() {
        return costCategoryRepository.findAll();
    }

    /**
     * P-12b (PPB-02, PPB-23): deletes a cost category that no additional cost cites and records it; otherwise 409
     * COST_CATEGORY_IN_USE.
     */
    @Transactional
    public void delete(CostCategory category, User actor) {
        Long id = category.getId();
        deletionService.delete(new AuditTarget(EntityType.COST_CATEGORY, id, null, category.getName(),
                        category.getCompanySettings().getCompany().getId()), actor, AuditSource.API,
                DeletionRule.inUse(COST_CATEGORY_IN_USE, COST_CATEGORY_IN_USE_MESSAGE)
                        .blockedBy(EntityType.ADDITIONAL_COST, () -> deletionService.isCited("additional_cost", "category_id", id)),
                () -> costCategoryRepository.deleteById(id));
    }

    public Optional<CostCategory> findById(Long id) {
        return costCategoryRepository.findById(id);
    }

    public Collection<CostCategory> findByCompanySettings(Long id) {
        return costCategoryRepository.findByCompanySettings_Id(id);
    }
}
