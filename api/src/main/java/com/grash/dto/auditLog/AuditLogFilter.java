package com.grash.dto.auditLog;

import com.grash.exception.CustomException;
import com.grash.model.enums.AuditAction;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

/**
 * Filter of the audit log query (PPB-36). Without parameters it is the preloaded one: the last 30 days until now,
 * every action and every user.
 */
@Schema(description = "Filter applied to the audit log query")
public record AuditLogFilter(
        @Schema(description = "Start of the period, inclusive") Instant from,
        @Schema(description = "End of the period, inclusive") Instant to,
        @Schema(description = "Only this operation; null for every operation") AuditAction action,
        @Schema(description = "Only records of this user; null for every user") Long userId) {

    public static final String AUDIT_FILTER_INVALID = "AUDIT_FILTER_INVALID";
    public static final Duration DEFAULT_PERIOD = Duration.ofDays(30);
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;

    /**
     * Parses the query parameters; any of them may be {@code null}. Without {@code to}: now. Without {@code from}:
     * 30 days before {@code to}.
     *
     * @throws CustomException 400 {@code AUDIT_FILTER_INVALID} if a value is malformed or the period is reversed
     */
    public static AuditLogFilter parse(String from, String to, String action, String userId, Instant now) {
        Instant end = to == null ? now : instant(to);
        Instant start = from == null ? end.minus(DEFAULT_PERIOD) : instant(from);
        if (start.isAfter(end)) throw invalid("the start of the period is after its end");
        return new AuditLogFilter(start, end, action == null ? null : action(action),
                userId == null ? null : number(userId, "userId"));
    }

    /**
     * Zero-based page number; bounded so that the first row of the page fits in an {@code int}.
     */
    public static int page(String page) {
        long value = page == null ? 0 : number(page, "page");
        if (value < 0 || value > Integer.MAX_VALUE / MAX_PAGE_SIZE) throw invalid("page is out of range");
        return (int) value;
    }

    public static int size(String size) {
        long value = size == null ? DEFAULT_PAGE_SIZE : number(size, "size");
        if (value < 1 || value > MAX_PAGE_SIZE) throw invalid("size must be between 1 and " + MAX_PAGE_SIZE);
        return (int) value;
    }

    private static Instant instant(String value) {
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException e) {
            throw invalid("dates must be ISO-8601 with an offset, for example 2026-11-21T00:00:00Z");
        }
    }

    private static AuditAction action(String value) {
        try {
            return AuditAction.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw invalid("unknown action");
        }
    }

    private static long number(String value, String name) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw invalid(name + " must be a number");
        }
    }

    private static CustomException invalid(String reason) {
        return new CustomException("Invalid audit log filter: " + reason, HttpStatus.BAD_REQUEST,
                AUDIT_FILTER_INVALID);
    }
}
