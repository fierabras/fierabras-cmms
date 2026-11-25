package com.grash.controller;

import com.grash.advancedsearch.SearchCriteria;
import com.grash.dto.PurchaseOrderPatchDTO;
import com.grash.dto.PurchaseOrderPostDTO;
import com.grash.dto.PurchaseOrderShowDTO;
import com.grash.dto.SuccessResponse;
import com.grash.exception.CustomException;
import com.grash.mapper.PartQuantityMapper;
import com.grash.mapper.PurchaseOrderMapper;
import com.grash.factory.MailServiceFactory;
import com.grash.model.*;
import com.grash.model.enums.*;
import com.grash.model.enums.workflow.WFMainCondition;
import com.grash.service.*;
import com.grash.utils.Helper;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/purchase-orders")
@Tag(name = "Purchase Orders", description = "Operations on purchase orders")
@RequiredArgsConstructor
public class PurchaseOrderController {

    private final PurchaseOrderService purchaseOrderService;
    private final UserService userService;
    private final PartQuantityService partQuantityService;
    private final MessageSource messageSource;
    private final PartQuantityMapper partQuantityMapper;
    private final PurchaseOrderMapper purchaseOrderMapper;
    private final MailServiceFactory mailServiceFactory;
    private final NotificationService notificationService;
    private final WorkflowService workflowService;

    @Value("${frontend.url}")
    private String frontendUrl;

    @PostMapping("/search")
    @PreAuthorize("permitAll()")
    public ResponseEntity<Page<PurchaseOrderShowDTO>> search(@Parameter(description = "Search criteria for filtering " +
                                                                     "purchase orders") @RequestBody SearchCriteria searchCriteria,
                                                             HttpServletRequest req) {
        User user = userService.whoami(req);
        if (user.getRole().getRoleType().equals(RoleType.ROLE_CLIENT)) {
            if (user.getRole().getViewPermissions().contains(PermissionEntity.PURCHASE_ORDERS)) {
                searchCriteria.filterCompany(user);
                boolean canViewOthers =
                        user.getRole().getViewOtherPermissions().contains(PermissionEntity.PURCHASE_ORDERS);
                if (!canViewOthers) {
                    searchCriteria.filterCreatedBy(user);
                }
            } else throw new CustomException("Access Denied", HttpStatus.FORBIDDEN);
        }
        return ResponseEntity.ok(purchaseOrderService.findBySearchCriteria(searchCriteria).map(this::setPartQuantities));
    }

    @GetMapping("/{id}")
    @PreAuthorize("permitAll()")
    public PurchaseOrderShowDTO getById(@PathVariable("id") Long id, HttpServletRequest req) {
        User user = userService.whoami(req);
        Optional<PurchaseOrder> optionalPurchaseOrder = purchaseOrderService.findById(id);
        if (optionalPurchaseOrder.isPresent()) {
            PurchaseOrder savedPurchaseOrder = optionalPurchaseOrder.get();
            if (user.getRole().getViewPermissions().contains(PermissionEntity.PURCHASE_ORDERS) &&
                    (user.getRole().getViewOtherPermissions().contains(PermissionEntity.PURCHASE_ORDERS) || user.getId().equals(savedPurchaseOrder.getCreatedBy()))) {
                return setPartQuantities(purchaseOrderMapper.toShowDto(savedPurchaseOrder));
            } else throw new CustomException("Access denied", HttpStatus.FORBIDDEN);
        } else throw new CustomException("Not found", HttpStatus.NOT_FOUND);
    }

    /**
     * P-16b (D-P16b-2): the lines, when sent, are created with the purchase order, before its workflows run, so that
     * a workflow that approves it on creation adds their stock as the direct route does.
     */
    @PostMapping("")
    @PreAuthorize("hasRole('ROLE_CLIENT')")
    PurchaseOrderShowDTO create(@Parameter(description = "Purchase order data to create, optionally with its lines")
                                @Valid @RequestBody PurchaseOrderPostDTO purchaseOrderReq,
                                HttpServletRequest req) {
        User user = userService.whoami(req);
        if (user.getRole().getCreatePermissions().contains(PermissionEntity.PURCHASE_ORDERS)
                && user.getCompany().getSubscription().getSubscriptionPlan().getFeatures().contains(PlanFeatures.PURCHASE_ORDER)) {
            // P-16 (D-P16-5): a purchase order is born pending; it is closed only by answering it
            purchaseOrderReq.setStatus(ApprovalStatus.PENDING);
            PurchaseOrder savedPurchaseOrder = purchaseOrderService.create(
                    purchaseOrderMapper.fromPostDto(purchaseOrderReq), purchaseOrderReq.getPartQuantities());
            Collection<Workflow> workflows =
                    workflowService.findByMainConditionAndCompany(WFMainCondition.PURCHASE_ORDER_CREATED,
                            user.getCompany().getId());
            workflows.forEach(workflow -> workflowService.runPurchaseOrder(workflow, savedPurchaseOrder, user));
            PurchaseOrderShowDTO result = setPartQuantities(purchaseOrderMapper.toShowDto(savedPurchaseOrder));
            double cost =
                    result.getPartQuantities().stream().mapToDouble(partQuantityShowDTO -> partQuantityShowDTO.getQuantity() * partQuantityShowDTO.getPart().getCost()).sum();
            String title = messageSource.getMessage("new_po", null, Helper.getLocale(user));
            String message = messageSource.getMessage("notification_new_po_request", new Object[]{result.getName(),
                            cost,
                            user.getCompany().getCompanySettings().getGeneralPreferences().getCurrency().getCode()},
                    Helper.getLocale(user));
            Map<String, Object> mailVariables = new HashMap<String, Object>() {{
                put("purchaseOrderLink", frontendUrl + "/app/purchase-orders/" + result.getId());
                put("message", message);
            }};
            Collection<User> usersToNotify = userService.findByCompany(user.getCompany().getId()).stream()
                    .filter(user1 -> user1.isEnabled() && user1.getRole().getViewPermissions().contains(PermissionEntity.SETTINGS) ||
                            user1.getRole().getCode().equals(RoleCode.LIMITED_ADMIN)).collect(Collectors.toList());
            notificationService.createMultiple(usersToNotify.stream().map(user1 -> new Notification(message, user1,
                    NotificationType.PURCHASE_ORDER, result.getId())).collect(Collectors.toList()), true, title);
            Collection<User> usersToMail =
                    usersToNotify.stream().filter(user1 -> user1.getUserSettings().shouldEmailUpdatesForPurchaseOrders()).collect(Collectors.toList());
            mailServiceFactory.getMailService().sendMessageUsingThymeleafTemplate(usersToMail.stream().map(User::getEmail).toArray(String[]::new), messageSource.getMessage("new_po", null, Helper.getLocale(user)), mailVariables, "new-purchase-order.html", Helper.getLocale(user), null);
            return result;
        } else throw new

                CustomException("Access denied", HttpStatus.FORBIDDEN);

    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('ROLE_CLIENT')")
    public PurchaseOrderShowDTO patch(@Parameter(description = "Purchase order fields to update") @Valid @RequestBody PurchaseOrderPatchDTO purchaseOrder,
                                      @PathVariable("id") Long id,
                                      HttpServletRequest req) {
        User user = userService.whoami(req);
        Optional<PurchaseOrder> optionalPurchaseOrder = purchaseOrderService.findById(id);

        if (optionalPurchaseOrder.isPresent()) {
            PurchaseOrder savedPurchaseOrder = optionalPurchaseOrder.get();
            if (user.getRole().getEditOtherPermissions().contains(PermissionEntity.PURCHASE_ORDERS) || user.getId().equals(savedPurchaseOrder.getCreatedBy())) {
                PurchaseOrder patchedPurchaseOrder = purchaseOrderService.update(id, purchaseOrder);
                Collection<Workflow> workflows =
                        workflowService.findByMainConditionAndCompany(WFMainCondition.PURCHASE_ORDER_UPDATED,
                                user.getCompany().getId());
                workflows.forEach(workflow -> workflowService.runPurchaseOrder(workflow, patchedPurchaseOrder, user));
                return setPartQuantities(purchaseOrderMapper.toShowDto(patchedPurchaseOrder));
            } else throw new CustomException("Forbidden", HttpStatus.FORBIDDEN);
        } else throw new CustomException("PurchaseOrder not found", HttpStatus.NOT_FOUND);
    }

    /**
     * P-16 (PPB-05, D-1): answers a pending purchase order in one transaction, with the purchase order locked. An
     * approved or rejected purchase order is not answered again: 409 PURCHASE_ORDER_CLOSED. P-16b (PPB-30): the
     * approval is the same as that of a workflow ({@link PurchaseOrderService#approve}).
     */
    @PatchMapping("/{id}/respond")
    @PreAuthorize("hasRole('ROLE_CLIENT')")
    @Transactional
    public PurchaseOrderShowDTO respond(@Parameter(description = "Whether the purchase order is approved") @RequestParam("approved") boolean approved, @PathVariable("id") Long id,
                                        HttpServletRequest req) {
        User user = userService.whoami(req);
        Optional<PurchaseOrder> optionalPurchaseOrder = purchaseOrderService.findByIdForUpdate(id);

        if (optionalPurchaseOrder.isPresent()) {
            PurchaseOrder savedPurchaseOrder = optionalPurchaseOrder.get();
            if (user.getRole().getEditOtherPermissions().contains(PermissionEntity.PURCHASE_ORDERS)) {
                PurchaseOrderStateGuard.check(savedPurchaseOrder);
                if (approved)
                    return setPartQuantities(purchaseOrderMapper.toShowDto(
                            purchaseOrderService.approve(savedPurchaseOrder, user)));
                savedPurchaseOrder.setStatus(ApprovalStatus.REJECTED);
                return setPartQuantities(purchaseOrderMapper.toShowDto(purchaseOrderService.save(savedPurchaseOrder)));
            } else throw new CustomException("Forbidden", HttpStatus.FORBIDDEN);
        } else throw new CustomException("PurchaseOrder not found", HttpStatus.NOT_FOUND);
    }

    /**
     * P-16 (PPB-05): deletes a pending purchase order, with the purchase order locked (see {@link #respond}).
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ROLE_CLIENT')")
    @Transactional
    public ResponseEntity delete(@PathVariable("id") Long id, HttpServletRequest req) {
        User user = userService.whoami(req);

        Optional<PurchaseOrder> optionalPurchaseOrder = purchaseOrderService.findByIdForUpdate(id);
        if (optionalPurchaseOrder.isPresent()) {
            PurchaseOrder savedPurchaseOrder = optionalPurchaseOrder.get();
            if (user.getId().equals(savedPurchaseOrder.getCreatedBy()) ||
                    user.getRole().getDeleteOtherPermissions().contains(PermissionEntity.PURCHASE_ORDERS)) {
                purchaseOrderService.delete(savedPurchaseOrder, user);
                return new ResponseEntity(new SuccessResponse(true, "Deleted successfully"),
                        HttpStatus.OK);
            } else throw new CustomException("Forbidden", HttpStatus.FORBIDDEN);
        } else throw new CustomException("PurchaseOrder not found", HttpStatus.NOT_FOUND);
    }

    private PurchaseOrderShowDTO setPartQuantities(PurchaseOrderShowDTO purchaseOrderShowDTO) {
        Collection<PartQuantity> partQuantities = partQuantityService.findByPurchaseOrder(purchaseOrderShowDTO.getId());
        purchaseOrderShowDTO.setPartQuantities(partQuantities.stream().map(partQuantityMapper::toShowDto).collect(Collectors.toList()));
        return purchaseOrderShowDTO;
    }
}


