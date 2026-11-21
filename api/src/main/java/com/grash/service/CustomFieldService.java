package com.grash.service;

import com.grash.dto.cutomField.CustomFieldPatchDTO;
import com.grash.dto.cutomField.CustomFieldPostDTO;
import com.grash.exception.CustomException;
import com.grash.mapper.CustomFieldMapper;
import com.grash.model.CustomField;
import com.grash.model.CompanySettings;
import com.grash.model.User;
import com.grash.model.enums.AuditSource;
import com.grash.model.enums.CustomFieldType;
import com.grash.model.enums.EntityType;
import com.grash.repository.CustomFieldRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class CustomFieldService {
    public static final String CUSTOM_FIELD_IN_USE = "CUSTOM_FIELD_IN_USE";
    private static final String CUSTOM_FIELD_IN_USE_MESSAGE = "This custom field has values in other records and "
            + "cannot be deleted";

    private final CustomFieldRepository customFieldRepository;
    private final CustomFieldMapper customFieldMapper;
    private final DeletionService deletionService;

    public CustomField create(CustomField customField) {
        return customFieldRepository.save(customField);
    }

    public CustomField create(CustomFieldPostDTO dto, CompanySettings companySettings) {
        CustomField field = customFieldMapper.toModel(dto);
        field.setCompanySettings(companySettings);
        field.setOrder(customFieldRepository.countByCompanySettings_IdAndEntityType(companySettings.getId(),
                field.getEntityType()));
        return customFieldRepository.save(field);
    }

    public CustomField update(Long id, CustomFieldPatchDTO customFieldPatchDTO) {
        if (customFieldRepository.existsById(id)) {
            CustomField savedField = customFieldRepository.findById(id).get();
            return customFieldRepository.save(customFieldMapper.updateCustomField(savedField, customFieldPatchDTO));
        } else throw new CustomException("Custom field not found", HttpStatus.NOT_FOUND);
    }

    public Page<CustomField> getAllByCompanySettings(CompanySettings companySettings, Pageable pageable) {
        return customFieldRepository.findByCompanySettings(companySettings, pageable);
    }

    public List<CustomField> getAllByCompanySettings(CompanySettings companySettings) {
        return customFieldRepository.findByCompanySettings(companySettings);
    }


    /**
     * P-11 (PPB-02, PPB-23): deletes a custom field without captured values and records it; otherwise 409
     * CUSTOM_FIELD_IN_USE.
     */
    @Transactional
    public void delete(CustomField field, User actor) {
        Long id = field.getId();
        deletionService.delete(new AuditTarget(EntityType.CUSTOM_FIELD, id, null, field.getLabel(),
                        field.getCompanySettings().getCompany().getId()), actor, AuditSource.API,
                DeletionRule.inUse(CUSTOM_FIELD_IN_USE, CUSTOM_FIELD_IN_USE_MESSAGE)
                        .blockedBy(EntityType.CUSTOM_FIELD_VALUE,
                                () -> deletionService.isCited("custom_field_value", "custom_field_id", id)),
                () -> customFieldRepository.deleteById(id));
    }

    public Optional<CustomField> findById(Long id) {
        return customFieldRepository.findById(id);
    }

    @Transactional
    public void reorder(List<Long> orderedIds, CompanySettings companySettings) {
        List<CustomField> fieldsToSave = new ArrayList<>();

        for (int i = 0; i < orderedIds.size(); i++) {
            Long id = orderedIds.get(i);
            CustomField field = customFieldRepository.findById(id)
                    .orElseThrow(() -> new CustomException("Custom field not found", HttpStatus.NOT_FOUND));
            if (!field.getCompanySettings().getId().equals(companySettings.getId())) {
                throw new CustomException("Access denied", HttpStatus.FORBIDDEN);
            }
            field.setOrder(i);
            fieldsToSave.add(field);
        }

        customFieldRepository.saveAll(fieldsToSave);
    }
}

