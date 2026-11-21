package com.grash.service;

import com.grash.model.WorkOrder;
import com.grash.model.enums.EntityType;

/**
 * The entity an audited operation acts on, as it is at that moment (PPB-23).
 *
 * @param folio     its folio ({@code customId}); for a child without folio, that of its parent ({@link #childOf});
 *                  {@code null} if there is none
 * @param name      its name, title or label, or {@code null} if it has none
 * @param companyId its organization, or {@code null} for a global entity (D-14)
 */
public record AuditTarget(EntityType type, Long id, String folio, String name, Long companyId) {

    /**
     * P-12: a child of a work order (labor, additional cost, task, part consumption, relation, comment). A child has
     * no folio of its own: it is recorded with the folio of its work order, so the record tells where it came from.
     */
    public static AuditTarget childOf(EntityType type, Long id, String name, WorkOrder workOrder) {
        return new AuditTarget(type, id, workOrder.getCustomId(), name, workOrder.getCompany().getId());
    }
}
