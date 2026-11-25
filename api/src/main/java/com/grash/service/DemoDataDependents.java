package com.grash.service;

import com.grash.model.enums.EntityType;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * P-18 (PPB-20 d, D-3): the associations that tie a demo record to one that is not demo, found from the database
 * catalog, without a list of cases. An association is a table whose rows cite, through two keys with
 * {@code ON DELETE CASCADE}, records of tables with {@code is_demo} and {@code company_id} (a work order and its
 * customers, an asset and its parts...). The purge would delete such a row in silence, so it is a dependent, in both
 * directions (D-P18-7). The other keys to the demo tables are {@code NO ACTION}: the database itself rejects the
 * purge when a record that is not demo cites a demo one ({@link DeletionService#purge}).
 */
@Service
@RequiredArgsConstructor
public class DemoDataDependents {

    private static final String CASCADE_KEYS = "SELECT child.relname, a.attname, parent.relname "
            + "FROM pg_constraint c JOIN pg_namespace n ON n.oid = c.connamespace "
            + "JOIN pg_class child ON child.oid = c.conrelid JOIN pg_class parent ON parent.oid = c.confrelid "
            + "JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = c.conkey[1] "
            + "WHERE c.contype = 'f' AND c.confdeltype = 'c' AND array_length(c.conkey, 1) = 1 "
            + "AND n.nspname = current_schema() "
            + "AND EXISTS (SELECT 1 FROM information_schema.columns k WHERE k.table_schema = current_schema() "
            + "AND k.table_name = parent.relname AND k.column_name = 'is_demo') "
            + "AND EXISTS (SELECT 1 FROM information_schema.columns k WHERE k.table_schema = current_schema() "
            + "AND k.table_name = parent.relname AND k.column_name = 'company_id') "
            + "ORDER BY 1, 2";

    private final EntityManager entityManager;

    /**
     * The types of the records that are not demo and are tied to a demo record of the company by an association;
     * empty if none.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Set<EntityType> associatedWithDemoData(Long companyId) {
        Set<EntityType> types = new LinkedHashSet<>();
        cascadeKeysByTable().forEach((table, keys) -> {
            for (String[] demo : keys)
                for (String[] other : keys)
                    if (demo != other && tiesDemoToNotDemo(table, demo, other, companyId))
                        EntityType.ofTable(other[1]).ifPresent(types::add);
        });
        return types;
    }

    /**
     * Table → its keys {column, referenced table} to the demo tables, for the tables with two or more of them.
     */
    private Map<String, List<String[]>> cascadeKeysByTable() {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery(CASCADE_KEYS).getResultList();
        Map<String, List<String[]>> keys = new LinkedHashMap<>();
        rows.forEach(row -> keys.computeIfAbsent((String) row[0], table -> new ArrayList<>())
                .add(new String[]{(String) row[1], (String) row[2]}));
        keys.values().removeIf(tableKeys -> tableKeys.size() < 2);
        return keys;
    }

    /**
     * The names come from the catalog, never from input.
     */
    private boolean tiesDemoToNotDemo(String table, String[] demo, String[] other, Long companyId) {
        return (Boolean) entityManager.createNativeQuery("SELECT EXISTS (SELECT 1 FROM " + table + " t JOIN "
                        + demo[1] + " d ON d.id = t." + demo[0] + " JOIN " + other[1] + " o ON o.id = t." + other[0]
                        + " WHERE d.is_demo AND d.company_id = ?1 AND NOT o.is_demo)")
                .setParameter(1, companyId).getSingleResult();
    }
}
