package com.grash.dto.auditLog;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * One page of the audit log, newest first, with the filter that produced it (PPB-36).
 */
@Schema(description = "A page of the audit log and the filter applied")
public record AuditLogSearchResult(
        AuditLogFilter filter,
        List<AuditLogShowDTO> content,
        @Schema(description = "Zero-based page number") int page,
        int size,
        long totalElements,
        int totalPages) {
}
