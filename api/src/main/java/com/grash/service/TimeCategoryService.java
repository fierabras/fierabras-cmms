package com.grash.service;

import com.grash.dto.CategoryPatchDTO;
import com.grash.exception.CustomException;
import com.grash.mapper.TimeCategoryMapper;
import com.grash.model.TimeCategory;
import com.grash.model.User;
import com.grash.model.enums.AuditSource;
import com.grash.model.enums.EntityType;
import com.grash.repository.TimeCategoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class TimeCategoryService {
    public static final String TIME_CATEGORY_IN_USE = "TIME_CATEGORY_IN_USE";
    private static final String TIME_CATEGORY_IN_USE_MESSAGE = "This time category is used by other records and "
            + "cannot be deleted";

    private final TimeCategoryRepository timeCategoryRepository;
    private final CompanySettingsService companySettingsService;
    private final TimeCategoryMapper timeCategoryMapper;
    private final DeletionService deletionService;

    public TimeCategory create(TimeCategory timeCategory, User user) {
        Optional<TimeCategory> categoryWithSameName =
                timeCategoryRepository.findByNameIgnoreCaseAndCompanySettings_Id(timeCategory.getName(),
                        user.getCompany().getCompanySettings().getId());
        if (categoryWithSameName.isPresent()) {
            throw new CustomException("TimeCategory with same name already exists", HttpStatus.NOT_ACCEPTABLE);
        }
        return timeCategoryRepository.save(timeCategory);
    }

    public TimeCategory update(Long id, CategoryPatchDTO timeCategory) {
        if (timeCategoryRepository.existsById(id)) {
            TimeCategory savedTimeCategory = timeCategoryRepository.findById(id).get();
            return timeCategoryRepository.save(timeCategoryMapper.updateTimeCategory(savedTimeCategory, timeCategory));
        } else throw new CustomException("Not found", HttpStatus.NOT_FOUND);
    }

    public Collection<TimeCategory> getAll() {
        return timeCategoryRepository.findAll();
    }

    /**
     * P-12b (PPB-02, PPB-23): deletes a time category that no labor cites and records it; otherwise 409
     * TIME_CATEGORY_IN_USE.
     */
    @Transactional
    public void delete(TimeCategory category, User actor) {
        Long id = category.getId();
        deletionService.delete(new AuditTarget(EntityType.TIME_CATEGORY, id, null, category.getName(),
                        category.getCompanySettings().getCompany().getId()), actor, AuditSource.API,
                DeletionRule.inUse(TIME_CATEGORY_IN_USE, TIME_CATEGORY_IN_USE_MESSAGE)
                        .blockedBy(EntityType.LABOR, () -> deletionService.isCited("labor", "time_category_id", id)),
                () -> timeCategoryRepository.deleteById(id));
    }

    public Optional<TimeCategory> findById(Long id) {
        return timeCategoryRepository.findById(id);
    }

    public Collection<TimeCategory> findByCompanySettings(Long id) {
        return timeCategoryRepository.findByCompanySettings_Id(id);

    }

    public boolean isTimeCategoryInCompany(TimeCategory timeCategory, long companyId, boolean optional) {
        if (optional) {
            Optional<TimeCategory> optionalTimeCategory = timeCategory == null ? Optional.empty() :
                    findById(timeCategory.getId());
            return timeCategory == null || (optionalTimeCategory.isPresent() && optionalTimeCategory.get().getCompanySettings().getCompany().getId().equals(companyId));
        } else {
            Optional<TimeCategory> optionalTimeCategory = findById(timeCategory.getId());
            return optionalTimeCategory.isPresent() && optionalTimeCategory.get().getCompanySettings().getCompany().getId().equals(companyId);
        }
    }
}
