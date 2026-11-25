package com.grash.controller;

import com.grash.advancedsearch.FilterField;
import com.grash.advancedsearch.SearchCriteria;
import com.grash.dto.PreventiveMaintenancePatchDTO;
import com.grash.dto.PreventiveMaintenancePostDTO;
import com.grash.dto.PreventiveMaintenanceShowDTO;
import com.grash.dto.SuccessResponse;
import com.grash.dto.workOrder.WorkOrderMiniDTO;
import com.grash.exception.CustomException;
import com.grash.mapper.PreventiveMaintenanceMapper;
import com.grash.mapper.WorkOrderMapper;
import com.grash.model.User;
import com.grash.model.PreventiveMaintenance;
import com.grash.model.Schedule;
import com.grash.model.WorkOrder;
import com.grash.model.enums.PermissionEntity;
import com.grash.model.enums.RoleType;
import com.grash.service.AssetService;
import com.grash.service.PreventiveMaintenanceService;
import com.grash.service.ScheduleService;
import com.grash.service.UserService;
import com.grash.service.WorkOrderService;
import com.grash.utils.TenantAspectUtils;

import jakarta.persistence.criteria.JoinType;


import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import jakarta.persistence.EntityManager;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/preventive-maintenances")
@Tag(name = "Preventive Maintenances", description = "Operations on preventive maintenances")
@RequiredArgsConstructor
public class PreventiveMaintenanceController {

    private final PreventiveMaintenanceService preventiveMaintenanceService;
    private final UserService userService;
    private final AssetService assetService;
    private final ScheduleService scheduleService;
    private final PreventiveMaintenanceMapper preventiveMaintenanceMapper;
    private final WorkOrderService workOrderService;
    private final WorkOrderMapper workOrderMapper;
    private final EntityManager em;

    @PostMapping("/search")
    @PreAuthorize("permitAll()")
    public ResponseEntity<Page<PreventiveMaintenanceShowDTO>> search(@Parameter(description = "Search criteria for " +
                                                                             "filtering preventive maintenances") @RequestBody SearchCriteria searchCriteria,
                                                                     HttpServletRequest req) {
        User user = userService.whoami(req);
        if (user.getRole().getRoleType().equals(RoleType.ROLE_CLIENT)) {
            if (user.getRole().getViewPermissions().contains(PermissionEntity.PREVENTIVE_MAINTENANCES)) {
                if (!user.getSuperAccountRelations().isEmpty()) {
                    List<Long> childCompanyIds = user.getSuperAccountRelations().stream()
                            .map(rel -> rel.getChildUser().getCompany().getId())
                            .distinct()
                            .toList();
                    searchCriteria.getFilterFields().add(FilterField.builder()
                            .field("company")
                            .operation("inm")
                            .joinType(JoinType.LEFT)
                            .value("")
                            .values(new ArrayList<>(childCompanyIds))
                            .build());
                } else {
                    searchCriteria.filterCompany(user);
                }
            } else throw new CustomException("Access Denied", HttpStatus.FORBIDDEN);
        }
        return ResponseEntity.ok(TenantAspectUtils.executeWithDisabledCompanyCheck(() ->
                preventiveMaintenanceService.findBySearchCriteria(searchCriteria)
        ));
    }

    @GetMapping("/{id}")
    @PreAuthorize("permitAll()")
    public PreventiveMaintenanceShowDTO getById(@PathVariable("id") Long id, HttpServletRequest req) {
        User user = userService.whoami(req);
        Optional<PreventiveMaintenance> optionalPreventiveMaintenance = preventiveMaintenanceService.findById(id);
        if (optionalPreventiveMaintenance.isPresent()) {
            PreventiveMaintenance savedPreventiveMaintenance = optionalPreventiveMaintenance.get();
            checkAccessToPreventiveMaintenance(user, savedPreventiveMaintenance);
            return preventiveMaintenanceMapper.toShowDto(savedPreventiveMaintenance);
        } else throw new CustomException("Not found", HttpStatus.NOT_FOUND);
    }

    @GetMapping("/{id}/recent-work-orders")
    @PreAuthorize("permitAll()")
    public List<WorkOrderMiniDTO> getRecentWorkOrders(@PathVariable("id") Long id,
                                                      HttpServletRequest req) {
        User user = userService.whoami(req);
        checkAccessToPreventiveMaintenance(user, preventiveMaintenanceService.findByIdAndCompany(id,
                user.getCompany().getId()).get());
        return workOrderService.findLastByPM(id, 10, user).stream()
                .map(workOrderMapper::toMiniDto)
                .collect(Collectors.toList());
    }

    @PostMapping("/{id}/trigger-work-order")
    @PreAuthorize("hasRole('ROLE_CLIENT')")
    public WorkOrderMiniDTO triggerWorkOrder(@PathVariable("id") Long id, HttpServletRequest req) {
        User user = userService.whoami(req);
        if (!(user.getRole().getCreatePermissions().contains(PermissionEntity.WORK_ORDERS))) {
            throw new CustomException("Access Denied", HttpStatus.FORBIDDEN);
        }
        PreventiveMaintenance preventiveMaintenance = preventiveMaintenanceService.findById(id)
                .orElseThrow(() -> new CustomException("PreventiveMaintenance not found", HttpStatus.NOT_FOUND));
        checkAccessToPreventiveMaintenance(user, preventiveMaintenance);
        // P-14b (E-3): the new work order does not cite an archived asset
        assetService.rejectRetired(List.of(), Collections.singletonList(preventiveMaintenance.getAsset()));
        WorkOrder workOrder =
                preventiveMaintenanceService.createWorkOrderFromPreventiveMaintenance(preventiveMaintenance);
        return workOrderMapper.toMiniDto(workOrder);
    }

    @Transactional
    @PostMapping("")
    @PreAuthorize("hasRole('ROLE_CLIENT')")
    PreventiveMaintenanceShowDTO create(@Parameter(description = "Preventive maintenance data to create") @Valid @RequestBody PreventiveMaintenancePostDTO preventiveMaintenancePost,
                                        HttpServletRequest req) {
        User user = userService.whoami(req);
        if (user.getRole().getCreatePermissions().contains(PermissionEntity.PREVENTIVE_MAINTENANCES)) {
            PreventiveMaintenance preventiveMaintenance = preventiveMaintenanceService.create(preventiveMaintenancePost,
                    user);

            Schedule schedule = preventiveMaintenance.getSchedule();
            schedule.setDaysOfWeek(preventiveMaintenancePost.getDaysOfWeek());
            schedule.setRecurrenceBasedOn(preventiveMaintenancePost.getRecurrenceBasedOn());
            schedule.setRecurrenceType(preventiveMaintenancePost.getRecurrenceType());
            schedule.setEndsOn(preventiveMaintenancePost.getEndsOn());
            schedule.setStartsOn(preventiveMaintenancePost.getStartsOn() != null ?
                    preventiveMaintenancePost.getStartsOn() : new Date());
            schedule.setFrequency(preventiveMaintenancePost.getFrequency());
            schedule.setDueDateDelay(preventiveMaintenancePost.getDueDateDelay());
            Schedule savedSchedule = scheduleService.save(schedule);
            em.refresh(savedSchedule);
            em.refresh(preventiveMaintenance);
            scheduleService.scheduleWorkOrder(savedSchedule);
            return preventiveMaintenanceMapper.toShowDto(preventiveMaintenance);
        } else throw new CustomException("Access Denied", HttpStatus.FORBIDDEN);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('ROLE_CLIENT')")
    public PreventiveMaintenanceShowDTO patch(@Parameter(description = "Preventive maintenance fields to update") @Valid @RequestBody PreventiveMaintenancePatchDTO preventiveMaintenance
            , @PathVariable("id") Long id,
                                              HttpServletRequest req) {
        User user = userService.whoami(req);
        Optional<PreventiveMaintenance> optionalPreventiveMaintenance = preventiveMaintenanceService.findById(id);

        if (optionalPreventiveMaintenance.isPresent()) {
            PreventiveMaintenance savedPreventiveMaintenance = optionalPreventiveMaintenance.get();
            if (user.getId().equals(savedPreventiveMaintenance.getCreatedBy())
                    || user.getRole().getEditOtherPermissions().contains(PermissionEntity.PREVENTIVE_MAINTENANCES)) {
                PreventiveMaintenance patchedPreventiveMaintenance = preventiveMaintenanceService.update(id,
                        preventiveMaintenance, user);
                return preventiveMaintenanceMapper.toShowDto(patchedPreventiveMaintenance);
            } else throw new CustomException("Access Denied", HttpStatus.FORBIDDEN);
        } else throw new CustomException("PreventiveMaintenance not found", HttpStatus.NOT_FOUND);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ROLE_CLIENT')")
    public ResponseEntity delete(@PathVariable("id") Long id, HttpServletRequest req) {
        User user = userService.whoami(req);

        Optional<PreventiveMaintenance> optionalPreventiveMaintenance = preventiveMaintenanceService.findById(id);
        if (optionalPreventiveMaintenance.isPresent()) {
            PreventiveMaintenance savedPreventiveMaintenance = optionalPreventiveMaintenance.get();
            if (user.getId().equals(savedPreventiveMaintenance.getCreatedBy())
                    || user.getRole().getDeleteOtherPermissions().contains(PermissionEntity.PREVENTIVE_MAINTENANCES)) {
                // P-15 (E-4, A-2): a preventive maintenance without schedule (earlier data) is deleted too
                Schedule schedule = savedPreventiveMaintenance.getSchedule();
                Long scheduleId = schedule == null ? null : schedule.getId();
                preventiveMaintenanceService.delete(savedPreventiveMaintenance, user);
                // P-15 (E-1, TD-P17-06): the jobs stop only once the deletion is committed; a 409 keeps them
                if (scheduleId != null) scheduleService.stopScheduleJobs(scheduleId);
                return new ResponseEntity(new SuccessResponse(true, "Deleted successfully"),
                        HttpStatus.OK);
            } else throw new CustomException("Access Denied", HttpStatus.FORBIDDEN);
        } else throw new CustomException("PreventiveMaintenance not found", HttpStatus.NOT_FOUND);
    }

    private void checkAccessToPreventiveMaintenance(User user, PreventiveMaintenance preventiveMaintenance) {
        if (!(user.getRole().getViewPermissions().contains(PermissionEntity.PREVENTIVE_MAINTENANCES) && (user.getId().equals(preventiveMaintenance.getCreatedBy())
                || user.getRole().getViewOtherPermissions().contains(PermissionEntity.PREVENTIVE_MAINTENANCES)))) {
            throw new CustomException("Access Denied", HttpStatus.FORBIDDEN);
        }
    }
}



