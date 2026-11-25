package com.grash.model.enums;

/**
 * P-16b (PPB-30): why the stock of a part changed, for each movement of its ledger.
 */
public enum PartTransactionOrigin {
    /** The stock a part is created with. */
    CREATION,
    /** A part consumed on a work order. */
    CONSUMPTION,
    /** A consumption reduced or removed: the stock comes back. */
    REVERSAL,
    /** A purchase order approved. */
    PURCHASE_ORDER,
    /** The stock set by hand on the part. */
    ADJUSTMENT,
    /** The stock set by an import. */
    IMPORT,
    /** A restock of the part. */
    RESTOCK,
    /** The stock that purged demo documents had added or taken. */
    DEMO_PURGE
}
