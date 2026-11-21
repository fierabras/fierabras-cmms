package com.grash.service;

import com.grash.dto.auditLog.AuditLogFilter;
import com.grash.dto.auditLog.AuditLogSearchResult;
import com.grash.dto.auditLog.AuditLogShowDTO;
import com.grash.model.AuditLog;
import com.grash.model.User;
import com.grash.model.enums.AuditAction;
import com.grash.model.enums.AuditSource;
import com.grash.repository.AuditLogRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Writes and reads the audit log (PPB-23, PPB-24, PPB-36).
 */
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;
    private final EntityManager entityManager;

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

    /**
     * The records of the organization that match the filter, newest first. Never returns records of another
     * organization or without organization (D-14).
     */
    @Transactional(readOnly = true)
    public AuditLogSearchResult search(Long companyId, AuditLogFilter filter, int page, int size) {
        StringBuilder where = new StringBuilder(" FROM AuditLog a WHERE a.companyId = :companyId "
                + "AND a.occurredAt >= :from AND a.occurredAt <= :to");
        if (filter.action() != null) where.append(" AND a.action = :action");
        if (filter.userId() != null) where.append(" AND a.actorId = :userId");
        TypedQuery<AuditLog> records = bind(entityManager.createQuery("SELECT a" + where
                + " ORDER BY a.occurredAt DESC, a.id DESC", AuditLog.class), companyId, filter);
        TypedQuery<Long> total = bind(entityManager.createQuery("SELECT count(a)" + where, Long.class), companyId,
                filter);
        List<AuditLogShowDTO> content = records.setFirstResult(page * size).setMaxResults(size).getResultList()
                .stream().map(AuditLogShowDTO::of).toList();
        long totalElements = total.getSingleResult();
        return new AuditLogSearchResult(filter, content, page, size, totalElements,
                (int) ((totalElements + size - 1) / size));
    }

    /**
     * Whether the user exists and belongs to another organization than {@code companyId}.
     */
    @Transactional(readOnly = true)
    public boolean isUserOfOtherCompany(Long userId, Long companyId) {
        return entityManager.createQuery("SELECT u.company.id FROM User u WHERE u.id = :userId", Long.class)
                .setParameter("userId", userId).getResultList().stream()
                .anyMatch(userCompanyId -> !userCompanyId.equals(companyId));
    }

    private static <T> TypedQuery<T> bind(TypedQuery<T> query, Long companyId, AuditLogFilter filter) {
        query.setParameter("companyId", companyId)
                .setParameter("from", filter.from())
                .setParameter("to", filter.to());
        if (filter.action() != null) query.setParameter("action", filter.action());
        if (filter.userId() != null) query.setParameter("userId", filter.userId());
        return query;
    }
}
