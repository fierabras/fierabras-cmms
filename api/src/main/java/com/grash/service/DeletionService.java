package com.grash.service;

import com.grash.exception.ConflictException;
import com.grash.exception.ForeignKeyViolation;
import com.grash.model.User;
import com.grash.model.enums.AuditAction;
import com.grash.model.enums.AuditSource;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Deletes an entity with the common rules of P-10 (PPB-23, PPB-25, PPB-26), inside the caller's transaction:
 * <ol>
 *     <li>rejects with 409 {@code <ENTITY>_IN_USE} and every blocking type when a dependent of the rule exists;</li>
 *     <li>deletes and flushes; a foreign key that rejects the deletion becomes the same 409 (or
 *     {@code RECORD_IN_USE} without a rule), never a 500;</li>
 *     <li>writes the audit record with the rows deleted in cascade, in the same transaction: both are committed
 *     or rolled back together, and a rejection leaves no record.</li>
 * </ol>
 * The cascade is measured with the statistics of the current transaction ({@code pg_stat_xact_user_tables}),
 * which need {@code track_counts} (on by default in PostgreSQL).
 */
@Service
@RequiredArgsConstructor
public class DeletionService {

    private static final String DELETED_ROWS = "SELECT relname, n_tup_del FROM pg_stat_xact_user_tables "
            + "WHERE schemaname = current_schema() AND n_tup_del > 0";
    private static final String FILE_TABLE = "file";

    private final EntityManager entityManager;
    private final AuditLogService auditLogService;

    /**
     * @param rule     the dependents that block the deletion, or {@code null} if the entity has no rule yet
     * @param deletion deletes the entity (and only that)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void delete(AuditTarget target, User actor, AuditSource source, DeletionRule rule, Runnable deletion) {
        if (rule != null) rule.rejectIfInUse();
        entityManager.flush();
        Map<String, Long> before = deletedRows();
        run(rule, deletion);
        Map<String, Long> after = deletedRows();
        Map<String, Long> cascadeRows = cascadeRows(before, after, target.type().getTable());
        // P-17 (D-P17-5): every file row deleted by the operation, the entity's own included
        long storageObjects = after.getOrDefault(FILE_TABLE, 0L) - before.getOrDefault(FILE_TABLE, 0L);
        auditLogService.record(target, actor, AuditAction.DELETE, source, cascadeRows, (int) storageObjects);
    }

    /**
     * P-17 (C-1): deletes without a rule and without an audit record, for the deletions that P-15 has not migrated
     * yet; a foreign key that rejects the deletion becomes 409 {@code RECORD_IN_USE}, never a 500, whatever the
     * HTTP method of the request.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void deleteUnrecorded(Runnable deletion) {
        run(null, deletion);
    }

    private void run(DeletionRule rule, Runnable deletion) {
        try {
            deletion.run();
            entityManager.flush();
        } catch (RuntimeException failure) {
            Optional<ForeignKeyViolation> violation = ForeignKeyViolation.in(failure);
            if (violation.isEmpty()) throw failure;
            throw rule == null ? ConflictException.recordInUse() : rule.conflictFor(violation.get().referencingTable());
        }
    }

    /**
     * Whether a row of {@code table} cites {@code id} in {@code column}: the usual check of a {@link DeletionRule}.
     * {@code table} and {@code column} are constants of the caller, never input.
     */
    public boolean isCited(String table, String column, Long id) {
        return (Boolean) entityManager.createNativeQuery("SELECT EXISTS (SELECT 1 FROM " + table + " WHERE "
                + column + " = ?1)").setParameter(1, id).getSingleResult();
    }

    private Map<String, Long> deletedRows() {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery(DELETED_ROWS).getResultList();
        Map<String, Long> deleted = new HashMap<>();
        rows.forEach(row -> deleted.put((String) row[0], ((Number) row[1]).longValue()));
        return deleted;
    }

    /**
     * Rows deleted between the two snapshots, by table, without the row of the entity itself.
     */
    private static Map<String, Long> cascadeRows(Map<String, Long> before, Map<String, Long> after,
                                                 String entityTable) {
        Map<String, Long> cascade = new TreeMap<>();
        after.forEach((table, count) -> {
            long deleted = count - before.getOrDefault(table, 0L) - (table.equals(entityTable) ? 1 : 0);
            if (deleted > 0) cascade.put(table, deleted);
        });
        return cascade;
    }
}
