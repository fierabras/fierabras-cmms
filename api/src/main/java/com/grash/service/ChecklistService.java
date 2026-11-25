package com.grash.service;

import com.grash.dto.ChecklistPatchDTO;
import com.grash.dto.ChecklistPostDTO;
import com.grash.dto.TaskBaseDTO;
import com.grash.exception.CustomException;
import com.grash.model.Checklist;
import com.grash.model.Company;
import com.grash.model.TaskBase;
import com.grash.model.User;
import com.grash.model.enums.AuditSource;
import com.grash.model.enums.EntityType;
import com.grash.dto.license.LicenseEntitlement;
import com.grash.repository.CheckListRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.grash.utils.Consts.usageBasedLicenseLimits;

@Service
@RequiredArgsConstructor
public class ChecklistService {
    public static final String CHECKLIST_IN_USE = "CHECKLIST_IN_USE";
    private static final String CHECKLIST_IN_USE_MESSAGE = "This checklist is used by an automation. Change the "
            + "automation before deleting it";

    private final CheckListRepository checklistRepository;
    private final CompanySettingsService companySettingsService;
    private final TaskBaseService taskBaseService;
    private final EntityManager em;
    private final LicenseService licenseService;
    private final AssetService assetService;
    private final DeletionService deletionService;
    private final UserService userService;

    @Transactional
    public Checklist createPost(ChecklistPostDTO checklistReq, Company company) {
        checkUsageBasedLimit(company);
        // P-14b (E-3, PPB-08): a task of the checklist does not cite an archived asset
        assetService.rejectRetired(List.of(), checklistReq.getTaskBases().stream().map(TaskBaseDTO::getAsset).toList());
        // P-15 (E-8, PPB-08): a step of the checklist is not given to a disabled user
        userService.rejectRetired(List.of(), checklistReq.getTaskBases().stream().map(TaskBaseDTO::getUser).toList());
        List<TaskBase> taskBases = checklistReq.getTaskBases().stream()
                .map(taskBaseDto -> taskBaseService.createFromTaskBaseDTO(taskBaseDto, company)).collect(Collectors.toList());
        Checklist checklist = Checklist.builder()
                .name(checklistReq.getName())
                .companySettings(company.getCompanySettings())
                .taskBases(taskBases)
                .category(checklistReq.getCategory())
                .description(checklistReq.getDescription())
                .build();
        Checklist savedChecklist = checklistRepository.saveAndFlush(checklist);
        em.refresh(savedChecklist);
        return savedChecklist;
    }

    @Transactional
    public Checklist update(Long id, ChecklistPatchDTO checklistReq, Company company) {
        if (checklistRepository.existsById(id)) {
            Checklist savedChecklist = checklistRepository.getById(id);
            savedChecklist.setCategory(checklistReq.getCategory());
            savedChecklist.setDescription(checklistReq.getDescription());
            savedChecklist.setName(checklistReq.getName());
            // P-14b (E-3, PPB-08): its tasks do not cite an archived asset anew; those they already cite are kept
            assetService.rejectRetired(savedChecklist.getTaskBases().stream().map(TaskBase::getAsset).toList(),
                    checklistReq.getTaskBases().stream().map(TaskBaseDTO::getAsset).toList());
            // P-15 (E-8, PPB-08): its steps are not given to a disabled user anew; those they already have are kept
            userService.rejectRetired(savedChecklist.getTaskBases().stream().map(TaskBase::getUser).toList(),
                    checklistReq.getTaskBases().stream().map(TaskBaseDTO::getUser).toList());
            savedChecklist.getTaskBases().clear();
            List<TaskBase> taskBases = checklistReq.getTaskBases().stream()
                    .map(taskBaseDto -> taskBaseService.createFromTaskBaseDTO(taskBaseDto, company)).collect(Collectors.toList());
            savedChecklist.getTaskBases().addAll(taskBases);
            Checklist updatedChecklist = checklistRepository.saveAndFlush(savedChecklist);
            em.refresh(updatedChecklist);
            return updatedChecklist;
        } else throw new CustomException("Not found", HttpStatus.NOT_FOUND);
    }

    private void checkUsageBasedLimit(Company company) {
        if (!licenseService.hasEntitlement(LicenseEntitlement.UNLIMITED_CHECKLISTS)
                && checklistRepository.hasMoreThan(company.getId(),
                usageBasedLicenseLimits.get(LicenseEntitlement.UNLIMITED_CHECKLISTS).longValue()))
            throw new CustomException("You need a license to add a checklist", HttpStatus.FORBIDDEN);
    }

    public Collection<Checklist> getAll() {
        return checklistRepository.findAll();
    }

    /**
     * P-15 (E-7, PPB-02, PPB-17, PPB-23): deletes the checklist and records it, in one transaction, with its steps
     * (task definitions no task uses: a task copies the steps into definitions of its own). A checklist cited by an
     * automation is not deleted: 409 CHECKLIST_IN_USE.
     */
    @Transactional
    public void delete(Checklist checklist, User actor) {
        Long id = checklist.getId();
        deletionService.delete(new AuditTarget(EntityType.CHECKLIST, id, null, checklist.getName(),
                        checklist.getCompanySettings().getCompany().getId()), actor, AuditSource.API,
                DeletionRule.inUse(CHECKLIST_IN_USE, CHECKLIST_IN_USE_MESSAGE)
                        .blockedBy(EntityType.WORKFLOW_ACTION,
                                () -> deletionService.isCited("workflow_action", "checklist_id", id)),
                () -> checklistRepository.deleteById(id));
    }

    public Optional<Checklist> findById(Long id) {
        return checklistRepository.findById(id);
    }

    public Collection<Checklist> findByCompanySettings(Long id) {
        return checklistRepository.findByCompanySettings_Id(id);
    }

}

