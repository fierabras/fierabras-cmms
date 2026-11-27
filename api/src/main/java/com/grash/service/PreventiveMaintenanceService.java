package com.grash.service;

import com.grash.advancedsearch.SearchCriteria;
import com.grash.advancedsearch.SpecificationBuilder;
import com.grash.dto.CalendarEvent;
import com.grash.dto.PreventiveMaintenancePatchDTO;
import com.grash.dto.PreventiveMaintenancePostDTO;
import com.grash.dto.PreventiveMaintenanceShowDTO;
import com.grash.dto.cutomField.CustomFieldValuePostDTO;
import com.grash.dto.imports.PreventiveMaintenanceImportDTO;
import com.grash.dto.workOrder.WorkOrderPostDTO;
import com.grash.dto.license.LicenseEntitlement;
import com.grash.exception.CustomException;
import com.grash.mapper.PreventiveMaintenanceMapper;
import com.grash.model.*;
import com.grash.model.enums.*;

import com.grash.repository.PreventiveMaintenanceRepository;
import com.grash.repository.WorkOrderRepository;
import com.grash.utils.Helper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.TriggerKey;
import org.quartz.spi.OperableTrigger;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;

import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

import static com.grash.utils.Consts.usageBasedLicenseLimits;

@Service
@RequiredArgsConstructor
@Slf4j
public class PreventiveMaintenanceService {
    public static final String PREVENTIVE_MAINTENANCE_IN_USE = "PREVENTIVE_MAINTENANCE_IN_USE";
    private static final String PREVENTIVE_MAINTENANCE_IN_USE_MESSAGE = "This preventive maintenance is used by "
            + "other records. Pause it instead of deleting it";

    private final PreventiveMaintenanceRepository preventiveMaintenanceRepository;
    private final EntityManager em;
    private final CustomSequenceService customSequenceService;
    private final Scheduler scheduler;
    private final PreventiveMaintenanceMapper preventiveMaintenanceMapper;
    private final LocationService locationService;
    private final TeamService teamService;
    private final UserService userService;
    private final AssetService assetService;
    private final WorkOrderCategoryService workOrderCategoryService;
    private final ScheduleService scheduleService;
    private final LicenseService licenseService;
    private final CustomFieldValueService customFieldValueService;
    private final WorkOrderService workOrderService;
    private final TaskService taskService;
    private final FileService fileService;
    private final DeletionService deletionService;
    private final AuditLogService auditLogService;
    private final WorkOrderRepository workOrderRepository;


    @Transactional
    public PreventiveMaintenance create(PreventiveMaintenancePostDTO preventiveMaintenancePost, User user) {
        PreventiveMaintenance preventiveMaintenance = preventiveMaintenanceMapper.toModel(preventiveMaintenancePost);
        if (!user.getCompany().getSubscription().getSubscriptionPlan().getFeatures().contains(PlanFeatures.PREVENTIVE_MAINTENANCE)) {
            throw new CustomException("Preventive maintenance feature is not enabled for this subscription plan.",
                    HttpStatus.FORBIDDEN);
        }
        checkUsageBasedLimit(user.getCompany());
        // P-14b (E-3, PPB-08): an archived asset is not given to a new preventive maintenance
        assetService.rejectRetired(List.of(), Collections.singletonList(preventiveMaintenancePost.getAsset()));
        // P-15 (E-8, PPB-08): a disabled user is not given to a new preventive maintenance
        userService.rejectRetired(List.of(), WorkOrderService.assignedUsers(preventiveMaintenancePost.getPrimaryUser(),
                preventiveMaintenancePost.getAssignedTo()));
        Company company = user.getCompany();
        Long nextSequence = customSequenceService.getNextPreventiveMaintenanceSequence(company);
        preventiveMaintenance.setCustomId("PM" + String.format("%06d", nextSequence));

        if (!preventiveMaintenancePost.getCustomFields().isEmpty()) {
            setPMCustomFields(preventiveMaintenance, preventiveMaintenancePost.getCustomFields(), company);
        }
        PreventiveMaintenance savedPM = preventiveMaintenanceRepository.saveAndFlush(preventiveMaintenance);
        em.refresh(savedPM);
        return savedPM;
    }

    @Transactional
    public PreventiveMaintenance update(Long id, PreventiveMaintenancePatchDTO preventiveMaintenance, User user) {
        if (!user.getCompany().getSubscription().getSubscriptionPlan().getFeatures().contains(PlanFeatures.PREVENTIVE_MAINTENANCE)) {
            throw new CustomException("Preventive maintenance feature is not enabled for this subscription plan.",
                    HttpStatus.FORBIDDEN);
        }
        if (preventiveMaintenanceRepository.existsById(id)) {
            PreventiveMaintenance savedPreventiveMaintenance = preventiveMaintenanceRepository.findById(id).get();
            // P-14b (E-3, PPB-08): an archived asset is not given anew; the one already cited is kept
            assetService.rejectRetired(Collections.singletonList(savedPreventiveMaintenance.getAsset()),
                    Collections.singletonList(preventiveMaintenance.getAsset()));
            // P-15 (E-8, PPB-08): a disabled user is not given anew; the ones already assigned are kept
            userService.rejectRetired(WorkOrderService.assignedUsers(savedPreventiveMaintenance.getPrimaryUser(),
                            savedPreventiveMaintenance.getAssignedTo()),
                    WorkOrderService.assignedUsers(preventiveMaintenance.getPrimaryUser(),
                            preventiveMaintenance.getAssignedTo()));
            if (!preventiveMaintenance.getCustomFields().isEmpty()) {
                setPMCustomFields(savedPreventiveMaintenance, preventiveMaintenance.getCustomFields(),
                        user.getCompany());
            }
            PreventiveMaintenance pmToSave =
                    preventiveMaintenanceMapper.updatePreventiveMaintenance(savedPreventiveMaintenance,
                            preventiveMaintenance);
            PreventiveMaintenance updatedPM =
                    preventiveMaintenanceRepository.saveAndFlush(pmToSave);
            em.refresh(updatedPM);
            return updatedPM;
        } else throw new CustomException("Not found", HttpStatus.NOT_FOUND);
    }

    /**
     * P-15 (E-2, PPB-07, PPB-23): pauses ({@code disabled}) or resumes the preventive maintenance and records it, in
     * one transaction. Only {@code Schedule.disabled} changes. Paused, its jobs stay and generate nothing; resumed,
     * its jobs are rebuilt without the rule of the 5 work orders without reaction (D-P15-2), so the next occurrence
     * generates the work order. Asking for the state it already has changes and records nothing.
     */
    @Transactional
    public PreventiveMaintenance changeDisabled(Long id, boolean disabled, User actor) {
        PreventiveMaintenance preventiveMaintenance = preventiveMaintenanceRepository.findById(id)
                .orElseThrow(() -> new CustomException("Not found", HttpStatus.NOT_FOUND));
        Schedule schedule = preventiveMaintenance.getSchedule();
        if (schedule == null || schedule.isDisabled() == disabled) return preventiveMaintenance;
        schedule.setDisabled(disabled);
        scheduleService.save(schedule);
        auditLogService.record(new AuditTarget(EntityType.PREVENTIVE_MAINTENANCE, id,
                        preventiveMaintenance.getCustomId(), preventiveMaintenance.getName(),
                        preventiveMaintenance.getCompany().getId()), actor,
                disabled ? AuditAction.RETIRE : AuditAction.RESTORE, AuditSource.API, Map.of(), 0);
        if (!disabled) scheduleService.resumeWorkOrder(schedule);
        return preventiveMaintenance;
    }

    /**
     * P-24 (E-1): what the work order job does for one occurrence of a schedule, in one transaction. The occurrence
     * is the scheduled fire time. The row of the schedule is locked, so a second run of the same occurrence waits
     * and then finds the work order the job already generated (no creator, created at or after the occurrence) and
     * creates none; a manual one does not count. Nothing is generated for a missing or paused schedule, after its
     * end date, or out of its weeks. An archived asset generates no work order, is recorded without actor (CA-P24-06)
     * and the schedule goes on (P-14b, D-1).
     *
     * @return the generated work order, or empty
     */
    @Transactional
    public Optional<WorkOrder> generateScheduledWorkOrder(Long scheduleId, Date occurrence) {
        Schedule schedule = scheduleService.findByIdForUpdate(scheduleId).orElse(null);
        if (schedule == null || schedule.isDisabled()) return Optional.empty();
        // P-24 (H-3): a schedule based on the completed date chains occurrences without an end of their own
        if (schedule.getEndsOn() != null && occurrence.after(schedule.getEndsOn())) return Optional.empty();
        if (!scheduleService.checkIfWeeklyShouldRun(schedule)) return Optional.empty();

        PreventiveMaintenance preventiveMaintenance = schedule.getPreventiveMaintenance();
        // P-14b (D-1): no work order cites an archived asset; the schedule goes on (a schedule based on the completed
        // date chains its next occurrence from now, as a completion would)
        if (!AssetStateGuard.admits(preventiveMaintenance.getAsset())) {
            log.info("P-14b: preventive maintenance {} generated no work order: asset {} is archived",
                    preventiveMaintenance.getId(), preventiveMaintenance.getAsset().getId());
            auditLogService.record(auditTarget(preventiveMaintenance), null, AuditAction.SKIP_ARCHIVED_ASSET,
                    AuditSource.JOB, Map.of(), 0);
            scheduleService.scheduleNextWorkOrderJobAfterCompletion(schedule.getId(), new Date());
            return Optional.empty();
        }
        if (workOrderRepository.existsByParentPreventiveMaintenance_IdAndCreatedByIsNullAndCreatedAtGreaterThanEqual(
                preventiveMaintenance.getId(), occurrence)) {
            log.info("P-24: the occurrence {} of schedule {} already generated its work order", occurrence,
                    scheduleId);
            return Optional.empty();
        }
        return Optional.of(createWorkOrderFromPreventiveMaintenance(preventiveMaintenance));
    }

    /**
     * P-24 (E-4, CA-P24-07): records, in a transaction of its own, that an occurrence of the schedule generated no
     * work order because creating it failed; that transaction was rolled back whole and is not retried (D-P24-6).
     * The record does not keep the text of the exception. Nothing if the schedule no longer exists.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordGenerationFailure(Long scheduleId) {
        scheduleService.findById(scheduleId).map(Schedule::getPreventiveMaintenance).ifPresent(preventiveMaintenance ->
                auditLogService.record(auditTarget(preventiveMaintenance), null, AuditAction.GENERATION_FAILED,
                        AuditSource.JOB, Map.of(), 0));
    }

    private static AuditTarget auditTarget(PreventiveMaintenance preventiveMaintenance) {
        return new AuditTarget(EntityType.PREVENTIVE_MAINTENANCE, preventiveMaintenance.getId(),
                preventiveMaintenance.getCustomId(), preventiveMaintenance.getName(),
                preventiveMaintenance.getCompany().getId());
    }

    @Transactional
    public WorkOrder createWorkOrderFromPreventiveMaintenance(PreventiveMaintenance preventiveMaintenance) {
        WorkOrderPostDTO workOrder = workOrderService.getWorkOrderFromWorkOrderBase(preventiveMaintenance);
        workOrder.getCustomFields().removeIf(customFieldValue -> !workOrder.getCustomFieldValues()
                .stream().filter(customFieldValue1 -> customFieldValue1.getCustomField().getId().equals(customFieldValue.getId()))
                .findFirst().get().getCustomField().isCopyOnRepeat());

        Collection<Task> tasks = taskService.findByPreventiveMaintenance(preventiveMaintenance.getId());
        workOrder.setParentPreventiveMaintenance(preventiveMaintenance);

        Schedule schedule = preventiveMaintenance.getSchedule();
        if (schedule.getDueDateDelay() != null) {
            workOrder.setDueDate(Helper.incrementDays(new Date(), schedule.getDueDateDelay()));
        }

        WorkOrder savedWorkOrder = workOrderService.create(workOrder, preventiveMaintenance.getCompany());

        tasks.forEach(task -> {
            Task copiedTask = new Task(task.getTaskBase(), savedWorkOrder, null, task.getValue());
            copiedTask.setCompany(preventiveMaintenance.getCompany());
            taskService.create(copiedTask);
        });

        return savedWorkOrder;
    }

    private void setPMCustomFields(PreventiveMaintenance preventiveMaintenance,
                                   List<CustomFieldValuePostDTO> customFieldValuePostDTOS,
                                   Company company) {
        customFieldValueService.setCustomFields(
                preventiveMaintenance,
                preventiveMaintenance.getCustomFieldValues(),
                customFieldValuePostDTOS,
                company,
                CustomFieldEntityType.WORK_ORDER,
                cfv -> cfv.setPreventiveMaintenance(preventiveMaintenance)
        );
    }

    public Collection<PreventiveMaintenance> getAll() {
        return preventiveMaintenanceRepository.findAll();
    }

    /**
     * Deletes the preventive maintenance and records it, in one transaction (P-15, E-1). One that generated work
     * orders, or is assigned to a disabled user, is not deleted: 409 PREVENTIVE_MAINTENANCE_IN_USE with every blocking
     * type (PPB-02, D-P15-4); it is paused instead. Its schedule, tasks, custom field values and associations go with
     * it; the images of its tasks too, and their stored objects after the commit (P-17, C-4). The caller stops its
     * jobs only after the commit (TD-P17-06).
     */
    @Transactional
    public void delete(PreventiveMaintenance preventiveMaintenance, User actor) {
        Long id = preventiveMaintenance.getId();
        List<String> images = fileService.objectsOfPreventiveMaintenance(id);
        deletionService.delete(new AuditTarget(EntityType.PREVENTIVE_MAINTENANCE, id,
                        preventiveMaintenance.getCustomId(), preventiveMaintenance.getName(),
                        preventiveMaintenance.getCompany().getId()), actor, AuditSource.API,
                DeletionRule.inUse(PREVENTIVE_MAINTENANCE_IN_USE, PREVENTIVE_MAINTENANCE_IN_USE_MESSAGE)
                        .blockedBy(EntityType.WORK_ORDER,
                                () -> deletionService.isCited("work_order", "parent_preventive_maintenance_id", id))
                        .blockedBy(EntityType.WORK_ORDER_HISTORY,
                                () -> deletionService.isCitedByAnEarlierRevision("parent_preventive_maintenance_id",
                                        id))
                        .blockedBy(EntityType.USER,
                                () -> deletionService.isLinkedToRetiredUser("preventive_maintenance_assigned_to",
                                        "preventive_maintenance_id", "assigned_to_id", id)),
                () -> preventiveMaintenanceRepository.deleteById(id));
        fileService.deleteObjectsAfterCommit(images);
    }

    public Optional<PreventiveMaintenance> findById(Long id) {
        return preventiveMaintenanceRepository.findById(id);
    }

    public Collection<PreventiveMaintenance> findByCompany(Long id) {
        return preventiveMaintenanceRepository.findByCompany_Id(id);
    }

    public Page<PreventiveMaintenance> findByCompanyForExport(Long companyId, Pageable pageable) {
        return preventiveMaintenanceRepository.findByCompanyForExport(companyId, pageable);
    }

    private void checkUsageBasedLimit(Company company) {
        Integer threshold = usageBasedLicenseLimits.get(LicenseEntitlement.UNLIMITED_PM_SCHEDULES);
        if (!licenseService.hasEntitlement(LicenseEntitlement.UNLIMITED_PM_SCHEDULES)
                && preventiveMaintenanceRepository.hasMoreThan(company.getId(), threshold.longValue() - 1
        ))
            throw new CustomException("You need a license to add a new PM schedule. Free Limit reached: " + threshold,
                    HttpStatus.FORBIDDEN);

    }

    public Page<PreventiveMaintenanceShowDTO> findBySearchCriteria(SearchCriteria searchCriteria) {
        SpecificationBuilder<PreventiveMaintenance> builder = new SpecificationBuilder<>();
        searchCriteria.getFilterFields().forEach(builder::with);
        Pageable page = PageRequest.of(searchCriteria.getPageNum(), searchCriteria.getPageSize(),
                searchCriteria.getDirection(), searchCriteria.getSortField());
        return preventiveMaintenanceRepository.findAll(builder.build(), page).map(preventiveMaintenanceMapper::toShowDto);
    }

    public boolean isPreventiveMaintenanceInCompany(PreventiveMaintenance preventiveMaintenance, long companyId,
                                                    boolean optional) {
        if (optional) {
            Optional<PreventiveMaintenance> optionalPreventiveMaintenance = preventiveMaintenance == null ?
                    Optional.empty() : findById(preventiveMaintenance.getId());
            return preventiveMaintenance == null || (optionalPreventiveMaintenance.isPresent() && optionalPreventiveMaintenance.get().getCompany().getId().equals(companyId));
        } else {
            Optional<PreventiveMaintenance> optionalPreventiveMaintenance = findById(preventiveMaintenance.getId());
            return optionalPreventiveMaintenance.isPresent() && optionalPreventiveMaintenance.get().getCompany().getId().equals(companyId);
        }
    }

    public List<CalendarEvent<PreventiveMaintenance>> getEvents(Date end, Long companyId) {
        if (!licenseService.hasEntitlement(LicenseEntitlement.PM_CALENDAR))
            return Collections.emptyList();
        List<PreventiveMaintenance> preventiveMaintenances =
                preventiveMaintenanceRepository.findByCreatedAtBeforeAndCompany_Id(end, companyId);
        List<CalendarEvent<PreventiveMaintenance>> result = new ArrayList<>();

        for (PreventiveMaintenance preventiveMaintenance : preventiveMaintenances) {
            Schedule schedule = preventiveMaintenance.getSchedule();
            if (schedule == null || schedule.isDisabled()) continue;

            if (schedule.getRecurrenceBasedOn() != RecurrenceBasedOn.SCHEDULED_DATE) continue;

            try {
                TriggerKey triggerKey = new TriggerKey("wo-trigger-" + schedule.getId(), "wo-group");
                Trigger trigger = scheduler.getTrigger(triggerKey);

                if (trigger == null) {
                    log.warn("No trigger found for schedule {}", schedule.getId());
                    continue;
                }

                // Get all fire times up to the end date
                List<Date> fireTimes = new ArrayList<>();

                // Use TriggerUtils to get computed fire times
                if (trigger instanceof OperableTrigger) {
                    OperableTrigger operableTrigger = (OperableTrigger) trigger;
                    Date currentTime = new Date();

                    // Start from now or startsOn, whichever is earlier
                    Date startTime = schedule.getStartsOn().before(currentTime) ?
                            schedule.getStartsOn() : currentTime;

                    // Compute fire times
                    Date fireTime = operableTrigger.getFireTimeAfter(startTime);
                    while (fireTime != null && (fireTime.before(end) || fireTime.equals(end))) {
                        if (shouldFireOnDate(schedule, fireTime)) {
                            fireTimes.add(fireTime);
                        }
                        fireTime = operableTrigger.getFireTimeAfter(fireTime);

                        // Safety limit to prevent infinite loops
                        if (fireTimes.size() > 1000) {
                            log.warn("Reached safety limit of 1000 events for schedule {}", schedule.getId());
                            break;
                        }
                    }
                }

                // Convert fire times to calendar events
                result.addAll(fireTimes.stream()
                        .map(date -> new CalendarEvent<>("PREVENTIVE_MAINTENANCE", preventiveMaintenance, date))
                        .toList());

            } catch (SchedulerException e) {
                log.error("Error getting trigger fire times for schedule {}", schedule.getId(), e);
            }
        }

        return result;
    }

    private boolean shouldFireOnDate(Schedule schedule, Date fireTime) {
        if (schedule.getRecurrenceType() != RecurrenceType.WEEKLY || schedule.getFrequency() <= 1) {
            return true;
        }

        String tzId = schedule.getPreventiveMaintenance()
                .getCompany().getCompanySettings()
                .getGeneralPreferences().getTimeZone();
        ZoneId zoneId = ZoneId.of(tzId);

        long daysSinceStart = ChronoUnit.DAYS.between(
                schedule.getStartsOn().toInstant().atZone(zoneId).toLocalDate(),
                fireTime.toInstant().atZone(zoneId).toLocalDate()
        );
        long weeksSinceStart = daysSinceStart / 7;

        return weeksSinceStart % schedule.getFrequency() == 0;
    }

    public Optional<PreventiveMaintenance> findByIdAndCompany(Long id, Long companyId) {
        return preventiveMaintenanceRepository.findByIdAndCompany_Id(id, companyId);
    }

    public List<PreventiveMaintenance> saveAll(List<PreventiveMaintenance> preventiveMaintenances) {
        return preventiveMaintenanceRepository.saveAll(preventiveMaintenances);
    }

    public List<PreventiveMaintenance> findByIdsAndCompany(List<Long> ids, Long companyId) {
        return preventiveMaintenanceRepository.findByIdInAndCompany_Id(ids, companyId);
    }

    public void importPreventiveMaintenance(PreventiveMaintenance preventiveMaintenance,
                                            PreventiveMaintenanceImportDTO pmImportDTO, Company company) {
        checkUsageBasedLimit(company);
        Helper.populateWorkOrderBaseFromImportDTO(preventiveMaintenance, pmImportDTO, company, locationService,
                teamService, userService, assetService, workOrderCategoryService);

        preventiveMaintenance.setName(pmImportDTO.getName());
        preventiveMaintenance.setCompany(company);
        Schedule schedule = preventiveMaintenance.getSchedule();
        schedule.setStartsOn(Helper.getDateFromExcelDate(pmImportDTO.getStartsOn()));
        schedule.setFrequency((int) pmImportDTO.getFrequency());
        schedule.setDueDateDelay(pmImportDTO.getDueDateDelay() == null ? null :
                pmImportDTO.getDueDateDelay().intValue());
        schedule.setEndsOn(Helper.getDateFromExcelDate(pmImportDTO.getEndsOn()));
        schedule.setRecurrenceType(RecurrenceType.valueOf(pmImportDTO.getRecurrenceType().toUpperCase()));
        schedule.setRecurrenceBasedOn(RecurrenceBasedOn.valueOf(pmImportDTO.getRecurrenceBasedOn().trim().replaceAll(
                "\\s+", "_").toUpperCase()));
        schedule.setDaysOfWeek(pmImportDTO.getDaysOfWeek().stream().map(this::getDayOfWeekNumber).collect(Collectors.toList()));

        // P-20 (CA-P20-10): a preventive maintenance updated by the import keeps its folio
        if (preventiveMaintenance.getCustomId() == null)
            preventiveMaintenance.setCustomId("PM" + String.format("%06d",
                    customSequenceService.getNextPreventiveMaintenanceSequence(company)));

        PreventiveMaintenance savedPM = preventiveMaintenanceRepository.save(preventiveMaintenance);
        scheduleService.reScheduleWorkOrder(savedPM.getSchedule(), AuditSource.IMPORT);
    }

    private int getDayOfWeekNumber(String day) {
        return switch (day.toLowerCase()) {
            case "monday" -> 0;
            case "tuesday" -> 1;
            case "wednesday" -> 2;
            case "thursday" -> 3;
            case "friday" -> 4;
            case "saturday" -> 5;
            case "sunday" -> 6;
            default -> throw new IllegalArgumentException("Invalid day of week: " + day);
        };
    }
}

