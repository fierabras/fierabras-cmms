package com.grash.service;

import com.grash.model.AuditLog;
import com.grash.model.User;
import com.grash.model.enums.AuditAction;
import com.grash.model.enums.AuditSource;
import com.grash.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

/**
 * Writes and reads the audit log (PPB-23, PPB-24, PPB-36).
 */
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;

    /**
     * Writes the record of a confirmed operation in the transaction of that operation, so that both are committed
     * or rolled back together (PPB-25). Copies the name and email of {@code actor} ({@code null} for a job).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public AuditLog record(AuditTarget target, User actor, AuditAction action, AuditSource source,
                           Map<String, Long> cascadeRows, int storageObjects) {
        return auditLogRepository.save(AuditLog.builder()
                .occurredAt(Instant.now())
                .companyId(target.companyId())
                .actorId(actor == null ? null : actor.getId())
                .actorName(actor == null ? null : actor.getFirstName() + " " + actor.getLastName())
                .actorEmail(actor == null ? null : actor.getEmail())
                .action(action)
                .entityType(target.type())
                .entityId(target.id())
                .entityFolio(target.folio())
                .entityName(target.name())
                .source(source)
                .cascadeRows(cascadeRows)
                .storageObjects(storageObjects)
                .build());
    }
}
