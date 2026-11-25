package com.grash.service;

import com.grash.exception.CustomException;
import com.grash.model.Part;
import com.grash.model.PartTransaction;
import com.grash.model.PurchaseOrder;
import com.grash.model.User;
import com.grash.model.WorkOrder;
import com.grash.model.enums.PartTransactionOrigin;
import com.grash.repository.PartTransactionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * P-16b (PPB-30, E-1): the only place that changes the stock of a part, always together with the movement of its
 * ledger that explains the change, in the transaction of the caller. The stock of a part is then the sum of its
 * movements. A part without stock control ({@code nonStock}) never changes.
 */
@Service
@RequiredArgsConstructor
public class PartLedgerService {

    private final EntityManager em;
    private final PartTransactionRepository partTransactionRepository;

    /**
     * Who and what a movement comes from: the user (null only when there is none), the work order or purchase order
     * it cites, a description, and whether it is demo data.
     */
    public record Movement(User actor, WorkOrder workOrder, PurchaseOrder purchaseOrder, String description,
                           boolean demo) {
        public static Movement by(User actor) {
            return new Movement(actor, null, null, null, false);
        }

        public static Movement onWorkOrder(User actor, WorkOrder workOrder) {
            return new Movement(actor, workOrder, null, null, false);
        }

        public static Movement onPurchaseOrder(User actor, PurchaseOrder purchaseOrder) {
            return new Movement(actor, null, purchaseOrder, null, false);
        }

        public Movement describedAs(String description) {
            return new Movement(actor, workOrder, purchaseOrder, description, demo);
        }

        public Movement asDemo() {
            return new Movement(actor, workOrder, purchaseOrder, description, true);
        }
    }

    /**
     * The part, read again and locked until the transaction ends, so that its stock is the committed one and two
     * changes of the same part happen one after the other. Pending changes are written first: reading the part again
     * would discard them. Lock several parts in the order of their identifiers.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Part lock(Long partId) {
        em.flush();
        Part part = em.find(Part.class, partId);
        if (part == null) throw new CustomException("Part not found", HttpStatus.NOT_FOUND);
        em.refresh(part, LockModeType.PESSIMISTIC_WRITE);
        return part;
    }

    /**
     * Adds {@code change} to the stock of the locked {@code part} and records the movement. Nothing happens when the
     * change is 0 or the part has no stock control. A stock below 0 is rejected with 406, as before.
     *
     * @return the movement, when one was recorded
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<PartTransaction> record(Part part, double change, PartTransactionOrigin origin,
                                            Movement movement) {
        if (!em.contains(part) || em.getLockMode(part) != LockModeType.PESSIMISTIC_WRITE)
            throw new IllegalStateException("P-16b: the stock of part " + part.getId() + " changes only once it is "
                    + "locked (PartLedgerService.lock)");
        if (change == 0 || part.isNonStock()) return Optional.empty();
        part.setQuantity(part.getQuantity() + change);
        PartTransaction transaction = new PartTransaction(part, change, origin, movement.workOrder(),
                movement.purchaseOrder(), movement.description());
        transaction.setCompany(part.getCompany());
        transaction.setCreatedBy(movement.actor() == null ? null : movement.actor().getId());
        transaction.setDemo(movement.demo());
        return Optional.of(partTransactionRepository.saveAndFlush(transaction));
    }

    /**
     * P-16b (D-3, PPB-30): before the demo purge deletes the demo work orders and purchase orders, what each of them
     * added to or took from a part that is not demo is given back with a movement with origin DEMO_PURGE by
     * {@code actor}, and the movements stop citing them, keeping their folio in the description. A compensation
     * below 0 is rejected with 406, as any exit (D-P16b-8).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void compensateDemoDocuments(Long companyId, User actor) {
        List<Object[]> nets = partTransactionRepository.findNetOfDemoDocumentsOnPartsNotDemo(companyId);
        for (Object[] net : nets) {
            double quantity = ((Number) net[3]).doubleValue();
            if (quantity == 0) continue;
            String document = net[1] != null
                    ? "Demo work order " + folio(em.find(WorkOrder.class, ((Number) net[1]).longValue()))
                    : purchaseOrder(em.find(PurchaseOrder.class, ((Number) net[2]).longValue()));
            record(lock(((Number) net[0]).longValue()), -quantity, PartTransactionOrigin.DEMO_PURGE,
                    Movement.by(actor).describedAs(document + " purged"));
        }
        partTransactionRepository.detachFromDemoWorkOrders(companyId);
        partTransactionRepository.detachFromDemoPurchaseOrders(companyId);
    }

    /**
     * P-16b (D-3): deletes the movements of the demo parts that belong to the demo data, for the demo purge.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void deleteDemoMovements(Long companyId) {
        partTransactionRepository.deleteDemoMovements(companyId);
    }

    private static String folio(WorkOrder workOrder) {
        return workOrder.getCustomId() != null ? workOrder.getCustomId() : "#" + workOrder.getId();
    }

    private static String purchaseOrder(PurchaseOrder purchaseOrder) {
        return "Demo purchase order " + purchaseOrder.getName() + " (#" + purchaseOrder.getId() + ")";
    }
}
