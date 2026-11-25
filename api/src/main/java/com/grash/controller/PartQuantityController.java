package com.grash.controller;

import com.grash.dto.PartQuantityCompletePatchDTO;
import com.grash.dto.PartQuantityPatchDTO;
import com.grash.dto.PartQuantityShowDTO;
import com.grash.dto.SuccessResponse;
import com.grash.exception.CustomException;
import com.grash.mapper.PartQuantityMapper;
import com.grash.model.*;
import com.grash.model.enums.PermissionEntity;
import com.grash.service.*;
import com.grash.utils.Helper;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@RestController
@RequestMapping("/part-quantities")
@Tag(name = "Part Quantities", description = "Operations on part quantities")
@RequiredArgsConstructor
public class PartQuantityController {

    private final PartQuantityService partQuantityService;
    private final PartQuantityMapper partQuantityMapper;
    private final UserService userService;
    private final WorkOrderService workOrderService;
    private final PartService partService;
    private final PurchaseOrderService purchaseOrderService;

    @GetMapping("/work-order/{id}")
    @PreAuthorize("permitAll()")
    public Collection<PartQuantityShowDTO> getByWorkOrder(HttpServletRequest req,
                                                          @PathVariable("id") Long id) {
        User user = userService.whoami(req);
        Optional<WorkOrder> optionalWorkOrder = workOrderService.findById(id);
        if (optionalWorkOrder.isPresent()) {
            if (!optionalWorkOrder.get().isAccessibleBy(user))
                throw new CustomException("Access denied", HttpStatus.FORBIDDEN);
            return partQuantityService.findByWorkOrder(id).stream().map(partQuantityMapper::toShowDto).collect(Collectors.toList());
        } else throw new CustomException("Not found", HttpStatus.NOT_FOUND);
    }

    @GetMapping("/purchase-order/{id}")
    @PreAuthorize("permitAll()")
    public Collection<PartQuantityShowDTO> getByPurchaseOrder(HttpServletRequest req, @PathVariable(
            "id") Long id) {
        User user = userService.whoami(req);
        if (!user.getRole().getViewPermissions().contains(PermissionEntity.PURCHASE_ORDERS)) {
            throw new CustomException("Access denied", HttpStatus.FORBIDDEN);
        }
        Optional<PurchaseOrder> optionalPurchaseOrder = purchaseOrderService.findById(id);
        if (optionalPurchaseOrder.isPresent()) {
            return partQuantityService.findByPurchaseOrder(id).stream().map(partQuantityMapper::toShowDto).collect(Collectors.toList());
        } else throw new CustomException("Not found", HttpStatus.NOT_FOUND);
    }

    /**
     * Replaces the parts of a work order: an added part consumes one, a part left out is removed and gives back the
     * quantity of its line (P-16b, PPB-16: an entry with origin REVERSAL). One transaction: everything or nothing.
     * The parts are handled in the order of their identifiers, so two replacements lock them in the same order.
     */
    @PatchMapping("/work-order/{id}")
    @PreAuthorize("hasRole('ROLE_CLIENT')")
    @Transactional
    public Collection<PartQuantityShowDTO> patchWorkOrder(@Parameter(description = "List of part IDs to associate " +
                                                                  "with work order") @Valid @RequestBody List<Long> parts,
                                                          @PathVariable("id") Long id,
                                                          HttpServletRequest req) {
        User user = userService.whoami(req);
        Optional<WorkOrder> optionalWorkOrder = workOrderService.findById(id);

        if (optionalWorkOrder.isPresent()) {
            WorkOrder savedWorkOrder = optionalWorkOrder.get();
            if (savedWorkOrder.canBeEditedBy(user)) {
                Collection<PartQuantity> partQuantities = partQuantityService.findByWorkOrder(id);
                Collection<Long> partQuantityMappedPartIds = partQuantities.stream().map
                        (partQuantity -> partQuantity.getPart().getId()).collect(Collectors.toList());
                // P-12 (E-4, D-P12-2): a closed or archived work order is checked only when a line is added or
                // removed, and before anything is written
                if (!partQuantityMappedPartIds.containsAll(parts) || !parts.containsAll(partQuantityMappedPartIds))
                    WorkOrderStateGuard.check(savedWorkOrder, WorkOrderStateGuard.Change.CHILD);
                // P-12c (E-2, H1-04): without difference, a closed or archived work order is not written either
                if (savedWorkOrder.getFirstTimeToReact() == null
                        && WorkOrderStateGuard.admits(savedWorkOrder, WorkOrderStateGuard.Change.CHILD)) {
                    savedWorkOrder.setFirstTimeToReact(new Date());
                    workOrderService.save(savedWorkOrder);
                }
                Stream.concat(parts.stream(), partQuantityMappedPartIds.stream()).distinct().sorted()
                        .forEach(partId -> {
                            if (!partQuantityMappedPartIds.contains(partId)) {
                                Optional<Part> optionalPart = partService.findById(partId);
                                if (optionalPart.isPresent()) {
                                    partService.consumePart(optionalPart.get().getId(), 1, savedWorkOrder, user,
                                            Helper.getLocale(user));
                                    PartQuantity partQuantity = new PartQuantity(optionalPart.get(), savedWorkOrder,
                                            null, 1);
                                    partQuantityService.create(partQuantity);
                                } else throw new CustomException("Part not found", HttpStatus.NOT_FOUND);
                            } else if (!parts.contains(partId)) {
                                PartQuantity removed = partQuantities.stream().filter(partQuantity ->
                                        partQuantity.getPart().getId().equals(partId)).findFirst().get();
                                partService.consumePart(partId, -removed.getQuantity(), savedWorkOrder, user,
                                        Helper.getLocale(user));
                                partQuantityService.delete(removed, user);
                            }
                        });
                return partQuantityService.findByWorkOrder(id).stream().map(partQuantityMapper::toShowDto).collect(Collectors.toList());
            } else throw new CustomException("Forbidden", HttpStatus.FORBIDDEN);
        } else throw new CustomException("WorkOrder not found", HttpStatus.NOT_FOUND);
    }

    @PatchMapping("/purchase-order/{id}")
    @PreAuthorize("hasRole('ROLE_CLIENT')")
    public Collection<PartQuantityShowDTO> patchPurchaseOrder(@Parameter(description = "List of part quantities to " +
                                                                      "update for purchase order") @Valid @RequestBody List<PartQuantityCompletePatchDTO> partQuantitiesReq, @PathVariable("id") Long id,
                                                              HttpServletRequest req) {
        User user = userService.whoami(req);
        Optional<PurchaseOrder> optionalPurchaseOrder = purchaseOrderService.findById(id);

        if (optionalPurchaseOrder.isPresent()) {
            PurchaseOrder savedPurchaseOrder = optionalPurchaseOrder.get();
            if (!user.getRole().getEditOtherPermissions().contains(PermissionEntity.PURCHASE_ORDERS) && !user.getId().equals(savedPurchaseOrder.getCreatedBy())) {
                throw new CustomException("Access denied", HttpStatus.FORBIDDEN);
            }
            Collection<PartQuantity> savedPartQuantities = partQuantityService.findByPurchaseOrder(id);

            Collection<Long> savedPartQuantitiesMappedPartIds = savedPartQuantities.stream().map
                    (partQuantity -> partQuantity.getPart().getId()).collect(Collectors.toList());

            Collection<Long> partQuantitiesReqMappedPartIds = partQuantitiesReq.stream().map
                    (partQuantity -> partQuantity.getPart().getId()).collect(Collectors.toList());
            // P-16 (E-7, C-2): a closed purchase order is checked only when a line is added, removed or changed, and
            // before anything is written; the same lines are answered without writing
            boolean changesLines = !savedPartQuantitiesMappedPartIds.containsAll(partQuantitiesReqMappedPartIds)
                    || !partQuantitiesReqMappedPartIds.containsAll(savedPartQuantitiesMappedPartIds)
                    || partQuantitiesReq.stream().anyMatch(partQuantityReq -> savedPartQuantities.stream()
                    .anyMatch(partQuantity -> partQuantity.getPart().getId().equals(partQuantityReq.getPart().getId())
                            && partQuantity.getQuantity() != partQuantityReq.getQuantity()));
            if (changesLines) PurchaseOrderStateGuard.check(savedPurchaseOrder);
            partQuantitiesReq.forEach(partQuantityReq -> {
                if (savedPartQuantitiesMappedPartIds.contains(partQuantityReq.getPart().getId())) {
                    //existing parts
                    PartQuantity savedPartQuantity =
                            savedPartQuantities.stream().filter(partQuantity -> partQuantity.getPart().getId().equals(partQuantityReq.getPart().getId())).findFirst().get();
                    if (savedPartQuantity.getQuantity() != partQuantityReq.getQuantity()) {
                        savedPartQuantity.setQuantity(partQuantityReq.getQuantity());
                        partQuantityService.save(savedPartQuantity);
                    }
                } else {
                    //new Parts
                    Optional<Part> optionalPart = partService.findById(partQuantityReq.getPart().getId());
                    if (optionalPart.isPresent()) {
                        PartQuantity partQuantity = new PartQuantity(optionalPart.get(), null, savedPurchaseOrder,
                                partQuantityReq.getQuantity());
                        partQuantityService.create(partQuantity);
                    } else throw new CustomException("Part not found", HttpStatus.NOT_FOUND);
                }
            });
            // removed Parts
            savedPartQuantitiesMappedPartIds.forEach(partId -> {
                if (!partQuantitiesReqMappedPartIds.contains(partId)) {
                    partQuantityService.delete(savedPartQuantities.stream().filter(partQuantity ->
                            partQuantity.getPart().getId().equals(partId)).findFirst().get(), user);
                }
            });
            return partQuantityService.findByPurchaseOrder(id).stream().map(partQuantityMapper::toShowDto).collect(Collectors.toList());
        } else throw new CustomException("WorkOrder not found", HttpStatus.NOT_FOUND);
    }

    @GetMapping("/{id}")
    @PreAuthorize("permitAll()")
    public PartQuantityShowDTO getById(@PathVariable("id") Long id, HttpServletRequest req) {
        User user = userService.whoami(req);
        Optional<PartQuantity> optionalPartQuantity = partQuantityService.findById(id);
        if (optionalPartQuantity.isPresent()) {
            PartQuantity savedPartQuantity = optionalPartQuantity.get();
            return partQuantityMapper.toShowDto(savedPartQuantity);
        } else throw new CustomException("Not found", HttpStatus.NOT_FOUND);
    }

    /**
     * P-16b (D-P16b-6): a line created on a work order consumes its quantity, in the same transaction.
     */
    @PostMapping("")
    @PreAuthorize("hasRole('ROLE_CLIENT')")
    @Transactional
    PartQuantityShowDTO create(@Parameter(description = "Part quantity to create") @Valid @RequestBody PartQuantity partQuantityReq,
                               HttpServletRequest req) {
        User user = userService.whoami(req);
        //TODO check access
        WorkOrder workOrder = partQuantityReq.getWorkOrder() == null ? null
                : workOrderService.findById(partQuantityReq.getWorkOrder().getId())
                .orElseThrow(() -> new CustomException("WorkOrder not found", HttpStatus.NOT_FOUND));
        // P-12 (E-4): a consumption is not added to a closed or archived work order
        if (workOrder != null) WorkOrderStateGuard.check(workOrder, WorkOrderStateGuard.Change.CHILD);
        // P-16 (E-7): a line is not added to an approved or rejected purchase order
        if (partQuantityReq.getPurchaseOrder() != null)
            PurchaseOrderStateGuard.check(purchaseOrderService.findById(partQuantityReq.getPurchaseOrder().getId())
                    .orElseThrow(() -> new CustomException("PurchaseOrder not found", HttpStatus.NOT_FOUND)));
        PartQuantity savedPartQuantity = partQuantityService.create(partQuantityReq);
        if (workOrder != null)
            partService.consumePart(savedPartQuantity.getPart().getId(), savedPartQuantity.getQuantity(), workOrder,
                    user, Helper.getLocale(user));
        return partQuantityMapper.toShowDto(savedPartQuantity);
    }

    /**
     * P-16b (PPB-16): raising a consumption is an exit for the difference, reducing it an entry with origin
     * REVERSAL; the same quantity writes nothing. One transaction with the change of the line.
     */
    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('ROLE_CLIENT')")
    @Transactional
    public PartQuantityShowDTO patch(@Parameter(description = "Part quantity fields to update") @Valid @RequestBody PartQuantityPatchDTO partQuantity,
                                     @PathVariable("id") Long id,
                                     HttpServletRequest req) {
        User user = userService.whoami(req);
        Optional<PartQuantity> optionalPartQuantity = partQuantityService.findById(id);
        if (optionalPartQuantity.isPresent()) {
            PartQuantity savedPartQuantity = optionalPartQuantity.get();
            if (savedPartQuantity.getPart().getUnit() == null) {
                partQuantity.setQuantity((int) partQuantity.getQuantity());
            }
            // P-16 (E-7): the line of an approved or rejected purchase order does not change
            if (savedPartQuantity.getPurchaseOrder() != null)
                PurchaseOrderStateGuard.check(savedPartQuantity.getPurchaseOrder());
            if (savedPartQuantity.getWorkOrder() != null) {
                // P-12 (E-4): the consumption of a closed or archived work order does not change
                WorkOrderStateGuard.check(savedPartQuantity.getWorkOrder(), WorkOrderStateGuard.Change.CHILD);
                partService.consumePart(savedPartQuantity.getPart().getId(),
                        partQuantity.getQuantity() - savedPartQuantity.getQuantity(),
                        savedPartQuantity.getWorkOrder(), user, Helper.getLocale(user));
            }
            PartQuantity patchedPartQuantity = partQuantityService.update(id, partQuantity);
            return partQuantityMapper.toShowDto(patchedPartQuantity);
        } else throw new CustomException("PartQuantity not found", HttpStatus.NOT_FOUND);
    }

    /**
     * P-16b (PPB-16): removing a consumption gives back the quantity of its line with an entry with origin REVERSAL,
     * in one transaction with the removal and its audit record.
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ROLE_CLIENT')")
    @Transactional
    public ResponseEntity<SuccessResponse> delete(@PathVariable("id") Long id, HttpServletRequest req) {
        User user = userService.whoami(req);

        Optional<PartQuantity> optionalPartQuantity = partQuantityService.findById(id);
        if (optionalPartQuantity.isPresent()) {
            PartQuantity savedPartQuantity = optionalPartQuantity.get();
            boolean isWorkOrderPartQuantity = savedPartQuantity.getWorkOrder() != null;
            if
            (user.getId().equals(savedPartQuantity.getCreatedBy())
                    || (isWorkOrderPartQuantity && savedPartQuantity.getWorkOrder().canBeEditedBy(user)) || user.getRole().getEditOtherPermissions().contains(PermissionEntity.PURCHASE_ORDERS)) {
                if (isWorkOrderPartQuantity) {
                    // P-12 (E-4): the consumption of a closed or archived work order is not removed
                    WorkOrderStateGuard.check(savedPartQuantity.getWorkOrder(), WorkOrderStateGuard.Change.CHILD);
                    partService.consumePart(savedPartQuantity.getPart().getId(),
                            -savedPartQuantity.getQuantity(),
                            savedPartQuantity.getWorkOrder(), user, Helper.getLocale(user));
                }
                // P-16 (E-7): the line of an approved or rejected purchase order is not removed
                if (savedPartQuantity.getPurchaseOrder() != null)
                    PurchaseOrderStateGuard.check(savedPartQuantity.getPurchaseOrder());
                partQuantityService.delete(savedPartQuantity, user);
                return new ResponseEntity<>(new SuccessResponse(true, "Deleted successfully"),
                        HttpStatus.OK);
            } else throw new CustomException("Forbidden", HttpStatus.FORBIDDEN);
        } else throw new CustomException("PartQuantity not found", HttpStatus.NOT_FOUND);
    }

}


