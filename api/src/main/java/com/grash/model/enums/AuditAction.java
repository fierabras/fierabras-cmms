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
    PURGE,
    /**
     * P-24 (D-2): an occurrence of a preventive maintenance generated no work order because its asset is archived.
     */
    SKIP_ARCHIVED_ASSET,
    /**
     * P-24 (D-2): creating the work order of an occurrence failed; nothing of it stays.
     */
    GENERATION_FAILED,
    /**
     * P-24 (E-5): the next occurrence of a preventive maintenance could not be scheduled.
     */
    SCHEDULING_FAILED,
    /**
     * P-23 (D-1): a mail about the record could not be sent; the operation stays committed.
     */
    MAIL_FAILED,
    /**
     * P-23 (D-1): an in-app notification about the record could not be created.
     */
    NOTIFICATION_FAILED,
    /**
     * P-23 (D-1): a webhook about the record could not be delivered to one of its endpoints.
     */
    WEBHOOK_FAILED,
    /**
     * P-23 (D-2): a mail about the record was not sent because the sends were full.
     */
    MAIL_DISCARDED,
    /**
     * P-23 (D-2): a notification about the record was not created because the sends were full.
     */
    NOTIFICATION_DISCARDED,
    /**
     * P-23 (D-2): a webhook about the record was not dispatched because the sends were full.
     */
    WEBHOOK_DISCARDED
}
