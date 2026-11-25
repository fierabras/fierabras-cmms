package com.grash.service;

import com.grash.advancedsearch.SearchCriteria;
import com.grash.advancedsearch.SpecificationBuilder;
import com.grash.dto.PartQuantityCompletePatchDTO;
import com.grash.dto.PurchaseOrderPatchDTO;
import com.grash.dto.PurchaseOrderShowDTO;
import com.grash.exception.CustomException;
import com.grash.mapper.PartMapper;
import com.grash.mapper.PurchaseOrderMapper;
import com.grash.model.Company;
import com.grash.model.Part;
import com.grash.model.PartQuantity;
import com.grash.model.PurchaseOrder;
import com.grash.model.User;
import com.grash.model.enums.ApprovalStatus;
import com.grash.model.enums.AuditSource;
import com.grash.model.enums.EntityType;
import com.grash.model.enums.PartTransactionOrigin;
import com.grash.model.enums.webhook.PartField;
import com.grash.model.enums.webhook.WebhookEvent;
import com.grash.repository.PartQuantityRepository;
import com.grash.repository.PartRepository;
import com.grash.repository.PurchaseOrderRepository;
import com.grash.utils.AfterCommit;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class PurchaseOrderService {
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderMapper purchaseOrderMapper;
    private final CompanyService companyService;
    private final EntityManager em;
    private final DeletionService deletionService;
    private final PartRepository partRepository;
    private final PartQuantityRepository partQuantityRepository;
    private final PartLedgerService partLedgerService;
    private final WebhookDispatchService webhookDispatchService;
    private final PartMapper partMapper;

    @Transactional
    public PurchaseOrder create(PurchaseOrder purchaseOrder) {
        PurchaseOrder savedPurchaseOrder = purchaseOrderRepository.saveAndFlush(purchaseOrder);
        em.refresh(savedPurchaseOrder);
        return savedPurchaseOrder;
    }

    /**
     * P-16b (D-P16b-2): creates a pending purchase order with its lines, when there are, in one transaction. A part
     * of another organization is rejected with 403 when it is loaded, and nothing is created.
     */
    @Transactional
    public PurchaseOrder create(PurchaseOrder purchaseOrder, List<PartQuantityCompletePatchDTO> lines) {
        PurchaseOrder savedPurchaseOrder = create(purchaseOrder);
        if (lines != null) lines.forEach(line -> {
            Part part = partRepository.findById(line.getPart().getId())
                    .orElseThrow(() -> new CustomException("Part not found", HttpStatus.NOT_FOUND));
            PartQuantity partQuantity = new PartQuantity(part, null, savedPurchaseOrder, line.getQuantity());
            partQuantity.setCompany(savedPurchaseOrder.getCompany());
            partQuantityRepository.save(partQuantity);
        });
        return savedPurchaseOrder;
    }

    /**
     * P-16b (PPB-30, D-13): approves the locked pending purchase order, the same way by every route: each line adds
     * its quantity to its part with an entry with origin PURCHASE_ORDER that cites it (a part without stock control
     * does not change), and the webhook of each change is sent after the commit. The parts are locked in the order of
     * their identifiers. An approved or rejected purchase order: 409 PURCHASE_ORDER_CLOSED.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public PurchaseOrder approve(PurchaseOrder purchaseOrder, User actor) {
        PurchaseOrderStateGuard.check(purchaseOrder);
        partQuantityRepository.findByPurchaseOrder_Id(purchaseOrder.getId()).stream()
                .sorted(Comparator.comparing(line -> line.getPart().getId()))
                .forEach(line -> {
                    Part part = partLedgerService.lock(line.getPart().getId());
                    double previousQuantity = part.getQuantity();
                    partLedgerService.record(part, line.getQuantity(), PartTransactionOrigin.PURCHASE_ORDER,
                            PartLedgerService.Movement.onPurchaseOrder(actor, purchaseOrder));
                    if (part.getQuantity() != previousQuantity)
                        dispatchPartQuantityChangeWebhook(part, previousQuantity, line, purchaseOrder);
                });
        purchaseOrder.setStatus(ApprovalStatus.APPROVED);
        return purchaseOrderRepository.save(purchaseOrder);
    }

    /**
     * P-16b (D-13): the approval of a workflow: the purchase order is locked and approved as by the direct route, or
     * left as it is when it is no longer pending.
     *
     * @return whether it was approved
     */
    @Transactional
    public boolean approveById(Long id, User actor) {
        Optional<PurchaseOrder> purchaseOrder = purchaseOrderRepository.findByIdForUpdate(id);
        if (purchaseOrder.isEmpty() || !PurchaseOrderStateGuard.admits(purchaseOrder.get())) {
            log.info("P-16b: purchase order {} is not pending; it is not approved again", id);
            return false;
        }
        approve(purchaseOrder.get(), actor);
        return true;
    }

    /**
     * P-16b (E-16): the purchase order, read again and locked until the transaction ends, as the approval locks it:
     * a change of its lines and its approval happen one after the other.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public PurchaseOrder lock(Long id) {
        PurchaseOrder purchaseOrder = em.find(PurchaseOrder.class, id);
        if (purchaseOrder == null) throw new CustomException("PurchaseOrder not found", HttpStatus.NOT_FOUND);
        em.refresh(purchaseOrder, LockModeType.PESSIMISTIC_WRITE);
        return purchaseOrder;
    }

    private void dispatchPartQuantityChangeWebhook(Part part, double previousQuantity, PartQuantity partQuantity,
                                                   PurchaseOrder purchaseOrder) {
        Map<String, Object> webhookPayload = new HashMap<>();
        webhookPayload.put("partId", part.getId());
        webhookPayload.put("partName", part.getName());
        webhookPayload.put("previousQuantity", previousQuantity);
        webhookPayload.put("newQuantity", part.getQuantity());
        webhookPayload.put("changedAmount", partQuantity.getQuantity());
        webhookPayload.put("purchaseOrderId", purchaseOrder.getId());
        webhookPayload.put("purchaseOrderName", purchaseOrder.getName());
        webhookPayload.put("partQuantityId", partQuantity.getId());
        Collection<PartField> changedFields = Collections.singletonList(PartField.QUANTITY);
        Object serializedPart = partMapper.toShowDto(part);
        Company company = purchaseOrder.getCompany();
        AfterCommit.run(() -> webhookDispatchService.dispatchWebhook(company, WebhookEvent.PART_QUANTITY_CHANGED,
                webhookPayload, "changedPart", serializedPart, null, null, null, null, changedFields));
    }

    @Transactional
    public PurchaseOrder update(Long id, PurchaseOrderPatchDTO purchaseOrder) {
        if (purchaseOrderRepository.existsById(id)) {
            PurchaseOrder savedPurchaseOrder = purchaseOrderRepository.findById(id).get();
            PurchaseOrder updatedPurchaseOrder =
                    purchaseOrderRepository.saveAndFlush(purchaseOrderMapper.updatePurchaseOrder(savedPurchaseOrder,
                            purchaseOrder));
            em.refresh(updatedPurchaseOrder);
            return updatedPurchaseOrder;
        } else throw new CustomException("Not found", HttpStatus.NOT_FOUND);
    }

    public Collection<PurchaseOrder> getAll() {
        return purchaseOrderRepository.findAll();
    }

    /**
     * P-16 (PPB-05, PPB-23): deletes a pending purchase order with its lines and records it in the audit log, in one
     * transaction. An approved or rejected purchase order is not deleted: 409 PURCHASE_ORDER_CLOSED.
     */
    @Transactional
    public void delete(PurchaseOrder purchaseOrder, User actor) {
        PurchaseOrderStateGuard.check(purchaseOrder);
        Long id = purchaseOrder.getId();
        deletionService.delete(new AuditTarget(EntityType.PURCHASE_ORDER, id, null, purchaseOrder.getName(),
                        purchaseOrder.getCompany().getId()), actor, AuditSource.API, null,
                () -> purchaseOrderRepository.deleteById(id));
    }

    public Optional<PurchaseOrder> findById(Long id) {
        return purchaseOrderRepository.findById(id);
    }

    /**
     * P-16 (R-1, R-2): the purchase order to answer or delete, locked until the transaction ends. A concurrent answer
     * or deletion of the same purchase order waits, then reads it answered or finds it gone.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<PurchaseOrder> findByIdForUpdate(Long id) {
        return purchaseOrderRepository.findByIdForUpdate(id);
    }

    public Collection<PurchaseOrder> findByCompany(Long id) {
        return purchaseOrderRepository.findByCompany_Id(id);
    }

    public PurchaseOrder save(PurchaseOrder purchaseOrder) {
        return purchaseOrderRepository.save(purchaseOrder);
    }

    public boolean isPurchaseOrderInCompany(PurchaseOrder purchaseOrder, long companyId, boolean optional) {
        if (optional) {
            Optional<PurchaseOrder> optionalPurchaseOrder = purchaseOrder == null ? Optional.empty() :
                    findById(purchaseOrder.getId());
            return purchaseOrder == null || (optionalPurchaseOrder.isPresent() && optionalPurchaseOrder.get().getCompany().getId().equals(companyId));
        } else {
            Optional<PurchaseOrder> optionalPurchaseOrder = findById(purchaseOrder.getId());
            return optionalPurchaseOrder.isPresent() && optionalPurchaseOrder.get().getCompany().getId().equals(companyId);
        }
    }

    public Page<PurchaseOrderShowDTO> findBySearchCriteria(SearchCriteria searchCriteria) {
        SpecificationBuilder<PurchaseOrder> builder = new SpecificationBuilder<>();
        searchCriteria.getFilterFields().forEach(builder::with);
        Pageable page = PageRequest.of(searchCriteria.getPageNum(), searchCriteria.getPageSize(),
                searchCriteria.getDirection(), searchCriteria.getSortField());
        return purchaseOrderRepository.findAll(builder.build(), page).map(purchaseOrderMapper::toShowDto);
    }
}

