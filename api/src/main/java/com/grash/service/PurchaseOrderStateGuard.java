package com.grash.service;

import com.grash.exception.ConflictException;
import com.grash.model.PurchaseOrder;
import com.grash.model.enums.ApprovalStatus;

/**
 * P-16 (PPB-05, PPB-14, PPB-15): the single answer to "does this purchase order admit this change?". A purchase
 * order is open in {@code PENDING} and closed once approved or rejected: a closed purchase order is not deleted, not
 * answered again and its lines do not change (409 {@code PURCHASE_ORDER_CLOSED}); its general data stays editable.
 * Every route that deletes or answers a purchase order or changes its lines calls it after its 404 and 403 checks.
 */
public final class PurchaseOrderStateGuard {

    public static final String PURCHASE_ORDER_CLOSED = "PURCHASE_ORDER_CLOSED";

    private static final String CLOSED_MESSAGE = "This purchase order was already approved or rejected: it cannot be "
            + "deleted, answered again or have its lines changed";

    private PurchaseOrderStateGuard() {
    }

    public static void check(PurchaseOrder purchaseOrder) {
        if (!admits(purchaseOrder)) throw new ConflictException(PURCHASE_ORDER_CLOSED, CLOSED_MESSAGE);
    }

    /**
     * Whether the purchase order is open, for the routes that skip a change instead of rejecting the request
     * (automations).
     */
    public static boolean admits(PurchaseOrder purchaseOrder) {
        return purchaseOrder.getStatus() == ApprovalStatus.PENDING;
    }
}
