package com.grash.service;

import com.grash.model.enums.EntityType;

/**
 * The entity an audited operation acts on, as it is at that moment (PPB-23).
 *
 * @param folio     its folio ({@code customId}), or {@code null} if the entity has none
 * @param name      its name, title or label, or {@code null} if it has none
 * @param companyId its organization, or {@code null} for a global entity (D-14)
 */
public record AuditTarget(EntityType type, Long id, String folio, String name, Long companyId) {
}
