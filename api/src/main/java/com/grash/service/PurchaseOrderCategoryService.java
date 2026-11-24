package com.grash.service;

import com.grash.dto.CategoryPatchDTO;
import com.grash.exception.CustomException;
import com.grash.mapper.PurchaseOrderCategoryMapper;
import com.grash.model.PurchaseOrderCategory;
import com.grash.model.User;
import com.grash.model.enums.AuditSource;
import com.grash.model.enums.EntityType;
import com.grash.repository.PurchaseOrderCategoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class PurchaseOrderCategoryService {
    private final PurchaseOrderCategoryRepository purchaseOrderCategoryRepository;

    private final CompanySettingsService companySettingsService;
    private final PurchaseOrderCategoryMapper purchaseOrderCategoryMapper;
    private final DeletionService deletionService;

    public static final String PURCHASE_ORDER_CATEGORY_IN_USE = "PURCHASE_ORDER_CATEGORY_IN_USE";
    private static final String PURCHASE_ORDER_CATEGORY_IN_USE_MESSAGE = "This purchase order category is used by "
            + "other records and cannot be deleted";

    public PurchaseOrderCategory create(PurchaseOrderCategory purchaseOrderCategory, User user) {
        Optional<PurchaseOrderCategory> categoryWithSameName =
                purchaseOrderCategoryRepository.findByNameIgnoreCaseAndCompanySettings_Id(
                        purchaseOrderCategory.getName(), user.getCompany().getCompanySettings().getId());
        if (categoryWithSameName.isPresent()) {
            throw new CustomException("PurchaseOrderCategory with same name already exists", HttpStatus.NOT_ACCEPTABLE);
        }
        return purchaseOrderCategoryRepository.save(purchaseOrderCategory);
    }

    public PurchaseOrderCategory update(Long id, CategoryPatchDTO purchaseOrderCategory) {
        if (purchaseOrderCategoryRepository.existsById(id)) {
            PurchaseOrderCategory savedPurchaseOrderCategory = purchaseOrderCategoryRepository.findById(id).get();
            return purchaseOrderCategoryRepository.save(purchaseOrderCategoryMapper.updatePurchaseOrderCategory(savedPurchaseOrderCategory, purchaseOrderCategory));
        } else throw new CustomException("Not found", HttpStatus.NOT_FOUND);
    }

    public Collection<PurchaseOrderCategory> getAll() {
        return purchaseOrderCategoryRepository.findAll();
    }

    /**
     * P-16 (PPB-02, PPB-23): deletes the category and records it in the audit log, in one transaction. A category
     * used by a purchase order or a workflow is not deleted: 409 PURCHASE_ORDER_CATEGORY_IN_USE.
     */
    @Transactional
    public void delete(PurchaseOrderCategory category, User actor) {
        Long id = category.getId();
        deletionService.delete(new AuditTarget(EntityType.PURCHASE_ORDER_CATEGORY, id, null, category.getName(),
                        category.getCompanySettings().getCompany().getId()), actor, AuditSource.API,
                DeletionRule.inUse(PURCHASE_ORDER_CATEGORY_IN_USE, PURCHASE_ORDER_CATEGORY_IN_USE_MESSAGE)
                        .blockedBy(EntityType.PURCHASE_ORDER,
                                () -> deletionService.isCited("purchase_order", "category_id", id))
                        .blockedBy(EntityType.WORKFLOW_ACTION,
                                () -> deletionService.isCited("workflow_action", "purchase_order_category_id", id))
                        .blockedBy(EntityType.WORKFLOW_CONDITION,
                                () -> deletionService.isCited("workflow_condition", "purchase_order_category_id", id)),
                () -> purchaseOrderCategoryRepository.deleteById(id));
    }

    public Optional<PurchaseOrderCategory> findById(Long id) {
        return purchaseOrderCategoryRepository.findById(id);
    }

    public Collection<PurchaseOrderCategory> findByCompanySettings(Long id) {
        return purchaseOrderCategoryRepository.findByCompanySettings_Id(id);
    }

}
