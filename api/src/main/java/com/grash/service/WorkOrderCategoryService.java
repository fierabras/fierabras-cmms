package com.grash.service;

import com.grash.dto.CategoryPatchDTO;
import com.grash.exception.CustomException;
import com.grash.mapper.WorkOrderCategoryMapper;
import com.grash.model.CompanySettings;
import com.grash.model.User;
import com.grash.model.WorkOrderCategory;
import com.grash.model.enums.AuditSource;
import com.grash.model.enums.EntityType;
import com.grash.repository.WorkOrderCategoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class WorkOrderCategoryService {
    public static final String WORK_ORDER_CATEGORY_IN_USE = "WORK_ORDER_CATEGORY_IN_USE";
    private static final String WORK_ORDER_CATEGORY_IN_USE_MESSAGE = "This work order category is used by other "
            + "records and cannot be deleted";

    private final WorkOrderCategoryRepository workOrderCategoryRepository;

    private final CompanySettingsService companySettingsService;
    private final WorkOrderCategoryMapper workOrderCategoryMapper;
    private final DeletionService deletionService;

    public WorkOrderCategory create(WorkOrderCategory workOrderCategory, User user) {
        Optional<WorkOrderCategory> categoryWithSameName =
                workOrderCategoryRepository.findByNameIgnoreCaseAndCompanySettings_Id(workOrderCategory.getName(),
                        user.getCompany().getCompanySettings().getId());
        if (categoryWithSameName.isPresent()) {
            throw new CustomException("WorkOrderCategory with same name already exists", HttpStatus.NOT_ACCEPTABLE);
        }
        return workOrderCategoryRepository.save(workOrderCategory);
    }

    public WorkOrderCategory update(Long id, CategoryPatchDTO workOrderCategory) {
        if (workOrderCategoryRepository.existsById(id)) {
            WorkOrderCategory saveWorkOrderCategory = workOrderCategoryRepository.findById(id).get();
            return workOrderCategoryRepository.save(workOrderCategoryMapper.updateWorkOrderCategory(saveWorkOrderCategory, workOrderCategory));
        } else throw new CustomException("Not found", HttpStatus.NOT_FOUND);
    }

    public Collection<WorkOrderCategory> getAll() {
        return workOrderCategoryRepository.findAll();
    }

    /**
     * P-12b (PPB-02, PPB-23): deletes a work order category that no other record cites, nor any work order revision,
     * and records it; otherwise 409 WORK_ORDER_CATEGORY_IN_USE with the blocking types. The webhook filters that
     * contain it are associations: they lose it.
     */
    @Transactional
    public void delete(WorkOrderCategory category, User actor) {
        Long id = category.getId();
        deletionService.delete(new AuditTarget(EntityType.WORK_ORDER_CATEGORY, id, null, category.getName(),
                        category.getCompanySettings().getCompany().getId()), actor, AuditSource.API,
                DeletionRule.inUse(WORK_ORDER_CATEGORY_IN_USE, WORK_ORDER_CATEGORY_IN_USE_MESSAGE)
                        .blockedBy(EntityType.PREVENTIVE_MAINTENANCE,
                                () -> deletionService.isCited("preventive_maintenance", "category_id", id))
                        .blockedBy(EntityType.REQUEST, () -> deletionService.isCited("request", "category_id", id))
                        .blockedBy(EntityType.WORK_ORDER,
                                () -> deletionService.isCited("work_order", "category_id", id))
                        .blockedBy(EntityType.WORK_ORDER_HISTORY,
                                () -> deletionService.isCited("work_order_aud", "category_id", id))
                        .blockedBy(EntityType.WORK_ORDER_METER_TRIGGER,
                                () -> deletionService.isCited("work_order_meter_trigger", "category_id", id))
                        .blockedBy(EntityType.WORKFLOW_ACTION,
                                () -> deletionService.isCited("workflow_action", "work_order_category_id", id))
                        .blockedBy(EntityType.WORKFLOW_CONDITION,
                                () -> deletionService.isCited("workflow_condition", "work_order_category_id", id)),
                () -> workOrderCategoryRepository.deleteById(id));
    }

    public Optional<WorkOrderCategory> findById(Long id) {
        return workOrderCategoryRepository.findById(id);
    }

    public Collection<WorkOrderCategory> findByCompanySettings(Long id) {
        return workOrderCategoryRepository.findByCompanySettings_Id(id);
    }

    public Optional<WorkOrderCategory> findByNameIgnoreCaseAndCompanySettings(String name, Long id) {
        return workOrderCategoryRepository.findByNameIgnoreCaseAndCompanySettings_Id(name, id);
    }

    public WorkOrderCategory getOrCreate(String name, CompanySettings companySettings) {
        return workOrderCategoryRepository.findByNameIgnoreCaseAndCompanySettings_Id(name, companySettings.getId())
                .orElseGet(() -> workOrderCategoryRepository.save(new WorkOrderCategory(name, companySettings)));
    }
}
