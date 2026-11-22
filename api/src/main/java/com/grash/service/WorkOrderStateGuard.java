package com.grash.service;

import com.grash.exception.ConflictException;
import com.grash.model.WorkOrder;
import com.grash.model.enums.Status;

/**
 * P-12 (PPB-06, PPB-08, PPB-15, PPB-31 to PPB-34): the single answer to "does this work order admit this change?".
 * Every route that changes a work order, its children or its associations calls it after its 404 and 403 checks.
 * <ul>
 *     <li>An archived work order admits no change, only its restoration: 409 {@code WORK_ORDER_RETIRED}.</li>
 *     <li>A closed ({@code COMPLETE}) work order keeps its own fields and its status editable (reopening is a status
 *     change), admits new comments, and rejects any change to its children and associations: 409
 *     {@code WORK_ORDER_CLOSED}.</li>
 *     <li>An open work order admits every change.</li>
 * </ul>
 */
public final class WorkOrderStateGuard {

    public static final String WORK_ORDER_CLOSED = "WORK_ORDER_CLOSED";
    public static final String WORK_ORDER_RETIRED = "WORK_ORDER_RETIRED";
    public static final String WORK_ORDER_NOT_DELETABLE = "WORK_ORDER_NOT_DELETABLE";

    private static final String CLOSED_MESSAGE = "This work order is complete: its labor, costs, tasks, parts, "
            + "relations, files, assignees and custom field values cannot change. Reopen it first";
    private static final String RETIRED_MESSAGE = "This work order is archived and cannot change. Restore it first";
    private static final String NOT_DELETABLE_MESSAGE = "Work orders cannot be deleted. Archive it instead";

    /**
     * What a request changes.
     */
    public enum Change {
        /**
         * A child (labor and its timer, additional cost, task, part consumption, relation, custom field value), an
         * association (files, assignees, customers), or an existing comment (edit or removal).
         */
        CHILD,
        /**
         * A new comment.
         */
        NEW_COMMENT,
        /**
         * The fields of the work order itself, or its status.
         */
        WORK_ORDER
    }

    private WorkOrderStateGuard() {
    }

    public static void check(WorkOrder workOrder, Change change) {
        ConflictException conflict = conflict(workOrder, change);
        if (conflict != null) throw conflict;
    }

    /**
     * P-12c: whether the work order admits the change, for the routes that skip a write instead of rejecting the
     * request (an automation, a replacement without difference).
     */
    public static boolean admits(WorkOrder workOrder, Change change) {
        return conflict(workOrder, change) == null;
    }

    private static ConflictException conflict(WorkOrder workOrder, Change change) {
        if (workOrder.isArchived()) return new ConflictException(WORK_ORDER_RETIRED, RETIRED_MESSAGE);
        if (workOrder.getStatus() == Status.COMPLETE && change == Change.CHILD)
            return new ConflictException(WORK_ORDER_CLOSED, CLOSED_MESSAGE);
        return null;
    }

    public static ConflictException notDeletable() {
        return new ConflictException(WORK_ORDER_NOT_DELETABLE, NOT_DELETABLE_MESSAGE);
    }
}
