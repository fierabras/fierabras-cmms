package com.grash.dto.auditLog;

import com.grash.model.AuditLog;
import com.grash.model.enums.AuditAction;
import com.grash.model.enums.AuditSource;
import com.grash.model.enums.EntityType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Map;

@Schema(description = "A record of the audit log")
public record AuditLogShowDTO(
        Long id,
        @Schema(description = "Server time of the operation") Instant occurredAt,
        @Schema(description = "User who acted; null for a job") Long actorId,
        @Schema(description = "Name of the user at the time") String actorName,
        @Schema(description = "Email of the user at the time") String actorEmail,
        AuditAction action,
        EntityType entityType,
        Long entityId,
        @Schema(description = "Folio of the entity at the time, if it has one") String entityFolio,
        @Schema(description = "Name, title or label of the entity at the time, if it has one") String entityName,
        AuditSource source,
        @Schema(description = "Rows deleted in cascade, by table") Map<String, Long> cascadeRows,
        @Schema(description = "Stored objects to delete") int storageObjects) {

    public static AuditLogShowDTO of(AuditLog record) {
        return new AuditLogShowDTO(record.getId(), record.getOccurredAt(), record.getActorId(), record.getActorName(),
                record.getActorEmail(), record.getAction(), record.getEntityType(), record.getEntityId(),
                record.getEntityFolio(), record.getEntityName(), record.getSource(), record.getCascadeRows(),
                record.getStorageObjects());
    }
}
