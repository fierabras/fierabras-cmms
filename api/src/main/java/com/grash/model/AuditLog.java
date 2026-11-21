package com.grash.model;

import com.grash.model.enums.AuditAction;
import com.grash.model.enums.AuditSource;
import com.grash.model.enums.EntityType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

/**
 * One confirmed destructive operation (PPB-23). Never updated or deleted by the application (PPB-24); it keeps the
 * organization, the user and the entity as they were, without foreign keys to the entity or the user.
 */
@Entity
@Immutable
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Instant occurredAt;

    /**
     * {@code null} for a global entity without organization (D-14).
     */
    private Long companyId;

    private Long actorId;

    @Column(columnDefinition = "TEXT")
    private String actorName;

    @Column(columnDefinition = "TEXT")
    private String actorEmail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AuditAction action;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 64)
    private EntityType entityType;

    @Column(nullable = false)
    private Long entityId;

    @Column(columnDefinition = "TEXT")
    private String entityFolio;

    @Column(columnDefinition = "TEXT")
    private String entityName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AuditSource source;

    /**
     * Rows deleted in cascade, by table, without the row of the entity itself.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Long> cascadeRows;

    /**
     * Stored objects to delete: the {@code file} rows deleted with the operation.
     */
    @Column(nullable = false)
    private int storageObjects;
}
