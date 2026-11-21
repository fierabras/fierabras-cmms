package com.grash.model.enums;

/**
 * Operation recorded in the audit log (PPB-23).
 */
public enum AuditAction {
    /**
     * Physical deletion of a record.
     */
    DELETE,
    /**
     * Taking a record out of operation without deleting it (archive or disable).
     */
    RETIRE,
    /**
     * Inverse of {@link #RETIRE}.
     */
    RESTORE,
    /**
     * A completed work order moved back to another status.
     */
    REOPEN,
    /**
     * Deletion outside the normal operation: demo data or a whole organization.
     */
    PURGE
}
