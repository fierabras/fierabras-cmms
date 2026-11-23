package com.grash.controller;

import com.grash.dto.SuccessResponse;
import com.grash.dto.WorkOrderMeterTriggerPatchDTO;
import com.grash.dto.WorkOrderMeterTriggerPostDTO;
import com.grash.dto.WorkOrderMeterTriggerShowDTO;
import com.grash.exception.CustomException;
import com.grash.mapper.WorkOrderMeterTriggerMapper;
import com.grash.model.Meter;
import com.grash.model.User;
import com.grash.model.WorkOrderMeterTrigger;
import com.grash.service.AssetService;
import com.grash.service.MeterService;
import com.grash.service.UserService;
import com.grash.service.WorkOrderService;
import com.grash.service.WorkOrderMeterTriggerService;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/work-order-meter-triggers")
@Tag(name = "Work Order Meter Triggers", description = "Operations on work order meter triggers")
@RequiredArgsConstructor
public class WorkOrderMeterTriggerController {

    private final WorkOrderMeterTriggerService workOrderMeterTriggerService;
    private final WorkOrderMeterTriggerMapper workOrderMeterTriggerMapper;
    private final UserService userService;
    private final AssetService assetService;
    private final MeterService meterService;


    @GetMapping("/{id}")
    @PreAuthorize("permitAll()")
    public WorkOrderMeterTriggerShowDTO getById(@PathVariable("id") Long id, HttpServletRequest req) {
        User user = userService.whoami(req);
        Optional<WorkOrderMeterTrigger> optionalWorkOrderMeterTrigger = workOrderMeterTriggerService.findById(id);
        if (optionalWorkOrderMeterTrigger.isPresent()) {
            WorkOrderMeterTrigger savedWorkOrderMeterTrigger = optionalWorkOrderMeterTrigger.get();
            checkAccessToMeter(savedWorkOrderMeterTrigger.getMeter(), user, true);
            return workOrderMeterTriggerMapper.toShowDto(savedWorkOrderMeterTrigger);
        } else throw new CustomException("Not found", HttpStatus.NOT_FOUND);
    }

    @PostMapping("")
    @PreAuthorize("hasRole('ROLE_CLIENT')")
    WorkOrderMeterTriggerShowDTO create(@Parameter(description = "Work order meter trigger to create") @Valid @RequestBody WorkOrderMeterTriggerPostDTO workOrderMeterTriggerReq,
                                        HttpServletRequest req) {
        User user = userService.whoami(req);
        Optional<Meter> optionalMeter = meterService.findById(workOrderMeterTriggerReq.getMeter().getId());
        if (optionalMeter.isPresent()) {
            checkAccessToMeter(optionalMeter.get(), user, false);
        } else throw new CustomException("Meter not found", HttpStatus.NOT_FOUND);
        // P-14 (E-8): a disabled user is not accepted as a new reference
        userService.rejectRetired(List.of(), WorkOrderService.assignedUsers(workOrderMeterTriggerReq.getPrimaryUser(),
                workOrderMeterTriggerReq.getAssignedTo()));
        // P-14b (E-3, PPB-08): an archived asset is not accepted as a new reference
        assetService.rejectRetired(List.of(), Collections.singletonList(workOrderMeterTriggerReq.getAsset()));
        return workOrderMeterTriggerMapper.toShowDto(workOrderMeterTriggerService.create(workOrderMeterTriggerReq,
                user.getCompany()));
    }


    @GetMapping("/meter/{id}")
    @PreAuthorize("permitAll()")
    public Collection<WorkOrderMeterTriggerShowDTO> getByMeter(@PathVariable("id") Long id,
                                                               HttpServletRequest req) {
        User user = userService.whoami(req);
        Optional<Meter> optionalMeter = meterService.findById(id);
        if (optionalMeter.isPresent()) {
            checkAccessToMeter(optionalMeter.get(), user, true);
            return workOrderMeterTriggerService.findByMeter(id).stream().map(workOrderMeterTriggerMapper::toShowDto).collect(Collectors.toList());
        } else throw new CustomException("Not found", HttpStatus.NOT_FOUND);
    }


    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('ROLE_CLIENT')")
    public WorkOrderMeterTriggerShowDTO patch(@Parameter(description = "Work order meter trigger fields to update") @Valid @RequestBody WorkOrderMeterTriggerPatchDTO workOrderMeterTrigger
            , @PathVariable("id") Long id,
                                              HttpServletRequest req) {
        User user = userService.whoami(req);
        Optional<WorkOrderMeterTrigger> optionalWorkOrderMeterTrigger = workOrderMeterTriggerService.findById(id);

        if (optionalWorkOrderMeterTrigger.isPresent()) {
            WorkOrderMeterTrigger savedWorkOrderMeterTrigger = optionalWorkOrderMeterTrigger.get();
            checkAccessToMeter(savedWorkOrderMeterTrigger.getMeter(), user, false);
            // P-14 (E-8): a disabled user is not accepted as a new reference
            userService.rejectRetired(WorkOrderService.assignedUsers(savedWorkOrderMeterTrigger.getPrimaryUser(),
                    savedWorkOrderMeterTrigger.getAssignedTo()), WorkOrderService.assignedUsers(
                    workOrderMeterTrigger.getPrimaryUser(), workOrderMeterTrigger.getAssignedTo()));
            // P-14b (E-3, PPB-08): an archived asset is not given anew; the one already cited is kept
            assetService.rejectRetired(Collections.singletonList(savedWorkOrderMeterTrigger.getAsset()),
                    Collections.singletonList(workOrderMeterTrigger.getAsset()));
            return workOrderMeterTriggerMapper.toShowDto(workOrderMeterTriggerService.update(id,
                    workOrderMeterTrigger, user.getCompany()));
        } else throw new CustomException("WorkOrderMeterTrigger not found", HttpStatus.NOT_FOUND);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ROLE_CLIENT')")
    public ResponseEntity delete(@PathVariable("id") Long id, HttpServletRequest req) {
        User user = userService.whoami(req);

        Optional<WorkOrderMeterTrigger> optionalWorkOrderMeterTrigger = workOrderMeterTriggerService.findById(id);
        if (optionalWorkOrderMeterTrigger.isPresent()) {
            WorkOrderMeterTrigger savedWorkOrderMeterTrigger = optionalWorkOrderMeterTrigger.get();
            checkAccessToMeter(savedWorkOrderMeterTrigger.getMeter(), user, false);
            workOrderMeterTriggerService.delete(savedWorkOrderMeterTrigger, user);
            return new ResponseEntity(new SuccessResponse(true, "Deleted successfully"),
                    HttpStatus.OK);
        } else throw new CustomException("WorkOrderMeterTrigger not found", HttpStatus.NOT_FOUND);
    }

    private void checkAccessToMeter(Meter meter, User user, boolean view) {
        boolean hasAccess = view
                ? meter.isAccessibleBy(user)
                : meter.canBeEditedBy(user);

        if (!hasAccess) {
            throw new CustomException("Access denied", HttpStatus.FORBIDDEN);
        }
    }

}


