package com.grash.service;

import com.grash.advancedsearch.SearchCriteria;
import com.grash.advancedsearch.SpecificationBuilder;
import com.grash.dto.MeterPatchDTO;
import com.grash.dto.MeterPostDTO;
import com.grash.dto.MeterShowDTO;
import com.grash.dto.cutomField.CustomFieldValuePostDTO;
import com.grash.dto.imports.MeterImportDTO;
import com.grash.dto.license.LicenseEntitlement;
import com.grash.exception.CustomException;
import com.grash.mapper.MeterMapper;
import com.grash.model.*;
import com.grash.model.enums.AuditSource;
import com.grash.model.enums.CustomFieldEntityType;
import com.grash.model.enums.EntityType;
import com.grash.model.enums.NotificationType;
import com.grash.model.enums.PermissionEntity;
import com.grash.repository.MeterRepository;
import com.grash.service.CustomFieldValueService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;

import java.util.*;
import java.util.stream.Collectors;

import static com.grash.utils.Consts.usageBasedLicenseLimits;

@Service
@RequiredArgsConstructor
public class MeterService {
    private final MeterRepository meterRepository;
    private final MeterCategoryService meterCategoryService;
    private final FileService fileService;
    private final AssetService assetService;
    private final CompanyService companyService;
    private final MessageSource messageSource;
    private final LocationService locationService;
    private final UserService userService;
    private final EntityManager em;
    private final MeterMapper meterMapper;
    private final NotificationService notificationService;
    private final ReadingService readingService;
    private final LicenseService licenseService;
    private final CustomFieldValueService customFieldValueService;
    private final DeletionService deletionService;

    public static final String METER_IN_USE = "METER_IN_USE";
    private static final String METER_IN_USE_MESSAGE = "This meter is used by other records and cannot be deleted";

    @Transactional
    public Meter create(Meter meter, User user) {
        checkUsageBasedLimit(user.getCompany());
        Company company = user.getCompany();
        if (meter instanceof MeterPostDTO meterPostDTO) {
            meter = meterMapper.fromPostDto(meterPostDTO);
            if (meterPostDTO.getCustomFields() != null && !meterPostDTO.getCustomFields().isEmpty()) {
                setMeterCustomFields(meter, meterPostDTO.getCustomFields(), company);
            }
        }
        Meter savedMeter = meterRepository.saveAndFlush(meter);
        em.refresh(savedMeter);
        return savedMeter;
    }

    private void checkUsageBasedLimit(Company company) {
        Integer threshold = usageBasedLicenseLimits.get(LicenseEntitlement.UNLIMITED_METERS);
        if (!licenseService.hasEntitlement(LicenseEntitlement.UNLIMITED_METERS)
                && meterRepository.hasMoreThan(company.getId(), threshold.longValue() - 1
        ))
            throw new CustomException("You need a license to add a new meter. Free Limit reached: " + threshold,
                    HttpStatus.FORBIDDEN);
    }

    private void setMeterCustomFields(Meter meter, List<CustomFieldValuePostDTO> customFieldValuePostDTOS,
                                      Company company) {
        customFieldValueService.setCustomFields(
                meter,
                meter.getCustomFieldValues(),
                customFieldValuePostDTOS,
                company,
                CustomFieldEntityType.METER,
                cfv -> cfv.setMeter(meter)
        );
    }

    @Transactional
    public Meter update(Long id, MeterPatchDTO meter, Company company) {
        if (meterRepository.existsById(id)) {
            Meter savedMeter = meterRepository.findById(id).get();
            if (meter.getCustomFields() != null && !meter.getCustomFields().isEmpty()) {
                setMeterCustomFields(savedMeter, meter.getCustomFields(), company);
            }
            Meter patchedMeter = meterRepository.saveAndFlush(meterMapper.updateMeter(savedMeter, meter));
            em.refresh(patchedMeter);
            return patchedMeter;
        } else throw new CustomException("Not found", HttpStatus.NOT_FOUND);
    }

    public Collection<Meter> getAll() {
        return meterRepository.findAll();
    }

    /**
     * P-14 (PPB-02, PPB-23): deletes the meter and records it in the audit log, in one transaction. A meter with
     * readings, tasks or meter triggers (D-P14-3), or assigned to a disabled user, is not deleted: 409 METER_IN_USE
     * with every blocking type. Its custom field values and associations go with it.
     */
    @Transactional
    public void delete(Meter meter, User actor) {
        Long id = meter.getId();
        deletionService.delete(new AuditTarget(EntityType.METER, id, null, meter.getName(),
                        meter.getCompany().getId()), actor, AuditSource.API,
                DeletionRule.inUse(METER_IN_USE, METER_IN_USE_MESSAGE)
                        .blockedBy(EntityType.READING, () -> deletionService.isCited("reading", "meter_id", id))
                        .blockedBy(EntityType.TASK_BASE, () -> deletionService.isCited("task_base", "meter_id", id))
                        .blockedBy(EntityType.WORK_ORDER_METER_TRIGGER,
                                () -> deletionService.isCited("work_order_meter_trigger", "meter_id", id))
                        .blockedBy(EntityType.USER,
                                () -> deletionService.isLinkedToRetiredUser("t_meter_user_associations", "id_meter",
                                        id)),
                () -> meterRepository.deleteById(id));
    }

    public Optional<Meter> findById(Long id) {
        return meterRepository.findById(id);
    }

    public Collection<Meter> findByCompany(Long id) {
        return meterRepository.findByCompany_Id(id);
    }

    public Page<Meter> findByCompanyForExport(Long companyId, Pageable pageable) {
        return meterRepository.findByCompanyForExport(companyId, pageable);
    }

    public void notify(Meter meter, Locale locale) {
        String title = messageSource.getMessage("new_assignment", null, locale);
        String message = messageSource.getMessage("notification_meter_assigned", new Object[]{meter.getName()}, locale);
        if (meter.getUsers() != null) {
            notificationService.createMultiple(meter.getUsers().stream().map(assignedUser ->
                    new Notification(message, assignedUser, NotificationType.METER, meter.getId())).collect(Collectors.toList()), true, title);
        }
    }

    public void patchNotify(Meter oldMeter, Meter newMeter, Locale locale) {
        String title = messageSource.getMessage("new_assignment", null, locale);
        String message = messageSource.getMessage("notification_meter_assigned", new Object[]{newMeter.getName()},
                locale);
        if (newMeter.getUsers() != null) {
            List<User> newUsers = newMeter.getUsers().stream().filter(
                    user -> oldMeter.getUsers().stream().noneMatch(user1 -> user1.getId().equals(user.getId()))).collect(Collectors.toList());
            notificationService.createMultiple(newUsers.stream().map(newUser ->
                    new Notification(message, newUser, NotificationType.ASSET, newMeter.getId())).collect(Collectors.toList()), true, title);
        }
    }

    public Collection<Meter> findByAsset(Long id) {
        return meterRepository.findByAsset_Id(id);
    }


    public Page<MeterShowDTO> findBySearchCriteria(SearchCriteria searchCriteria) {
        SpecificationBuilder<Meter> builder = new SpecificationBuilder<>();
        searchCriteria.getFilterFields().forEach(builder::with);
        Pageable page = PageRequest.of(searchCriteria.getPageNum(), searchCriteria.getPageSize(),
                searchCriteria.getDirection(), searchCriteria.getSortField());
        return meterRepository.findAll(builder.build(), page).map(meter -> meterMapper.toShowDto(meter,
                readingService));
    }

    public void importMeter(Meter meter, MeterImportDTO dto, Company company) {
        checkUsageBasedLimit(company);
        Long companyId = company.getId();
        meter.setCompany(company);
        meter.setName(dto.getName());
        meter.setUnit(dto.getUnit());
        meter.setUpdateFrequency(dto.getUpdateFrequency());
        Optional<Location> optionalLocation = locationService.findByNameIgnoreCaseAndCompany(dto.getLocationName(),
                companyId).stream().findFirst();
        optionalLocation.ifPresent(meter::setLocation);
        Optional<Asset> optionalAsset = assetService.findByNameIgnoreCaseAndCompany(dto.getAssetName(),
                companyId).stream().findFirst();
        optionalAsset.ifPresent(meter::setAsset);
        if (dto.getMeterCategory() != null && !dto.getMeterCategory().isBlank()) {
            MeterCategory category = meterCategoryService.getOrCreate(dto.getMeterCategory(), company.getCompanySettings());
            meter.setMeterCategory(category);
        }
        List<User> users = new ArrayList<>();
        dto.getUsersEmails().forEach(email -> {
            Optional<User> optionalUser1 = userService.findByEmailAndCompany(email, companyId);
            optionalUser1.ifPresent(users::add);
        });
        meter.setUsers(users);
    }

    public Optional<Meter> findByIdAndCompany(Long id, Long companyId) {
        return meterRepository.findByIdAndCompany_Id(id, companyId);
    }

    public List<Meter> saveAll(List<Meter> meters) {
        return meterRepository.saveAll(meters);
    }

    public List<Meter> findByIdsAndCompany(List<Long> ids, Long companyId) {
        return meterRepository.findByIdInAndCompany_Id(ids, companyId);
    }

    public boolean isAccessibleBy(User user, Meter meter) {
        return (user.getRole().getViewPermissions().contains(PermissionEntity.METERS) &&
                (user.getRole().getViewOtherPermissions().contains(PermissionEntity.METERS) || (meter.getCreatedBy() != null && meter.getCreatedBy().equals(user.getId())) || meter.isAssignedTo(user)));
    }
}

