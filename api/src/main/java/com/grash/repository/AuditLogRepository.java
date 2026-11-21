package com.grash.repository;

import com.grash.model.AuditLog;
import org.springframework.data.repository.Repository;

/**
 * Only inserts: the audit log is never updated or deleted by the application (PPB-24). Queries are in
 * {@code AuditLogService}.
 */
public interface AuditLogRepository extends Repository<AuditLog, Long> {

    AuditLog save(AuditLog auditLog);
}
