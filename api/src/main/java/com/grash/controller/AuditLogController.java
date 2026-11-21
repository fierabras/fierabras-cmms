package com.grash.controller;

import com.grash.dto.auditLog.AuditLogFilter;
import com.grash.dto.auditLog.AuditLogSearchResult;
import com.grash.exception.CustomException;
import com.grash.model.User;
import com.grash.model.enums.PermissionEntity;
import com.grash.security.CurrentUser;
import com.grash.service.AuditLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * Read-only query of the audit log (PPB-24, PPB-36). There is no endpoint that changes or deletes a record.
 */
@RestController
@RequestMapping("/audit-logs")
@RequiredArgsConstructor
@Tag(name = "Audit log", description = "Read-only log of the destructive operations of the organization")
public class AuditLogController {

    private final AuditLogService auditLogService;

    @GetMapping("")
    @PreAuthorize("hasRole('ROLE_CLIENT')")
    @Operation(summary = "Search the audit log",
            description = "Records of the organization of the caller, newest first, paginated. Without parameters "
                    + "the filter is the last 30 days until now, every action and every user; the answer includes "
                    + "the filter applied. Needs the view of SETTINGS. An invalid filter answers 400 "
                    + "AUDIT_FILTER_INVALID; a user of another organization answers 403.")
    public AuditLogSearchResult search(
            @Parameter(description = "Start of the period, ISO-8601 with offset; default: 30 days before 'to'")
            @RequestParam(required = false) String from,
            @Parameter(description = "End of the period, ISO-8601 with offset; default: now")
            @RequestParam(required = false) String to,
            @Parameter(description = "DELETE, RETIRE, RESTORE, REOPEN or PURGE; default: every action")
            @RequestParam(required = false) String action,
            @Parameter(description = "Id of the user who acted; default: every user")
            @RequestParam(required = false) String userId,
            @Parameter(description = "Zero-based page; default 0") @RequestParam(required = false) String page,
            @Parameter(description = "Page size, 1 to 100; default 20") @RequestParam(required = false) String size,
            @Parameter(hidden = true) @CurrentUser User user) {
        if (!user.getRole().getViewPermissions().contains(PermissionEntity.SETTINGS))
            throw new CustomException("Access denied", HttpStatus.FORBIDDEN);
        AuditLogFilter filter = AuditLogFilter.parse(from, to, action, userId, Instant.now());
        int pageNumber = AuditLogFilter.page(page);
        int pageSize = AuditLogFilter.size(size);
        Long companyId = user.getCompany().getId();
        if (filter.userId() != null && auditLogService.isUserOfOtherCompany(filter.userId(), companyId))
            throw new CustomException("Access denied", HttpStatus.FORBIDDEN);
        return auditLogService.search(companyId, filter, pageNumber, pageSize);
    }
}
