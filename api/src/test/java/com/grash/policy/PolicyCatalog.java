package com.grash.policy;

import com.grash.policy.PolicyExtract.Key;
import com.grash.policy.PolicyExtract.Relation;
import com.grash.policy.PolicyExtract.Rule;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * P-19 (E-5, E-6, E-7): compares the catalog of a database ({@code pg_constraint}, {@code pg_index},
 * {@code information_schema}) with the {@link PolicyExtract}. Each check returns one line per violation, naming the
 * table or the foreign key ({@code child.column -> parent}); an empty list means that the check passes.
 */
public final class PolicyCatalog {

    /** One foreign key of the catalog. {@code columns} are the child columns, in order. */
    public record CatalogKey(String childTable, List<String> columns, String parentTable, Rule rule, String name,
                             boolean indexed, boolean validated) {
        public String id() {
            return childTable + "." + columns.get(0) + " -> " + parentTable;
        }
    }

    private static final String TABLES = "SELECT table_name FROM information_schema.tables "
            + "WHERE table_schema = current_schema() AND table_type = 'BASE TABLE' ORDER BY 1";

    private static final String KEYS = """
            SELECT child.relname AS child,
                   (SELECT string_agg(a.attname, ',' ORDER BY k.ord) FROM unnest(c.conkey) WITH ORDINALITY k(att, ord)
                    JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.att) AS columns,
                   parent.relname AS parent, c.confdeltype::text AS rule, c.conname AS name,
                   EXISTS (SELECT 1 FROM pg_index i WHERE i.indrelid = c.conrelid AND i.indkey[0] = c.conkey[1]
                           AND i.indisvalid AND i.indpred IS NULL) AS indexed,
                   c.convalidated AS validated
            FROM pg_constraint c
            JOIN pg_class child ON child.oid = c.conrelid
            JOIN pg_class parent ON parent.oid = c.confrelid
            JOIN pg_namespace n ON n.oid = c.connamespace
            WHERE c.contype = 'f' AND n.nspname = current_schema()
            ORDER BY 1, 2, 3""";

    private final PolicyExtract extract;
    private final List<String> tables;
    private final List<CatalogKey> keys;

    public PolicyCatalog(PolicyExtract extract, JdbcTemplate jdbc) {
        this.extract = extract;
        this.tables = jdbc.queryForList(TABLES, String.class);
        this.keys = jdbc.query(KEYS, (rs, i) -> new CatalogKey(rs.getString("child"),
                List.of(rs.getString("columns").split(",")), rs.getString("parent"),
                Rule.ofCatalog(rs.getString("rule")), rs.getString("name"), rs.getBoolean("indexed"),
                rs.getBoolean("validated")));
    }

    public List<CatalogKey> keys() {
        return keys;
    }

    /**
     * E-5 (CA-PPB-001, 002): each table and each foreign key of the catalog has exactly one row in the extract, and
     * each row has its table or key. Two keys of a table with the same first column cannot be told apart (D-P19-1).
     */
    public List<String> coverage() {
        List<String> result = new ArrayList<>();
        tables.stream().filter(table -> !extract.tables().containsKey(table))
                .forEach(table -> result.add("table " + table + " has no row in the policy"));
        extract.tables().keySet().stream().filter(table -> !tables.contains(table))
                .forEach(table -> result.add("table row " + table + " has no table"));
        Map<String, Key> rows = extract.byName();
        Set<String> found = new HashSet<>();
        for (CatalogKey key : keys) {
            String name = key.childTable() + "." + key.columns().get(0);
            if (!found.add(name)) {
                result.add("foreign key " + key.id() + " shares its table and first column with another key");
                continue;
            }
            Key row = rows.get(name);
            if (row == null) result.add("foreign key " + key.id() + " has no row in the policy");
            else if (!row.parentTable().equals(key.parentTable()))
                result.add("foreign key " + key.id() + ": the policy row points to " + row.parentTable());
        }
        rows.forEach((name, row) -> {
            if (!found.contains(name)) result.add("foreign key row " + row.id() + " has no foreign key");
        });
        return result;
    }

    /**
     * E-6 (CA-PPB-011, 012, 058): the rule of each foreign key is its target rule; no CASCADE is a reference, nor a
     * key of a historical record to a parent other than the organization (PPB-03).
     */
    public List<String> rules() {
        List<String> result = new ArrayList<>();
        Map<String, Key> rows = extract.byName();
        for (CatalogKey key : keys) {
            Key row = rows.get(key.childTable() + "." + key.columns().get(0));
            if (row != null && row.targetRule() != key.rule())
                result.add("foreign key " + key.id() + " is " + key.rule().sql() + ", its target rule is "
                        + row.targetRule().sql());
            if (key.rule() == Rule.CASCADE && row != null && (row.relation() == Relation.REFERENCE
                    || extract.historical(key.childTable()) && !"company".equals(key.parentTable())))
                result.add("foreign key " + key.id() + " is CASCADE and is a "
                        + (row.relation() == Relation.REFERENCE ? "reference" : "historical record") + " (PPB-03)");
        }
        return result;
    }

    /**
     * E-7 (CA-PPB-060, 061): the first child column of each foreign key leads a valid, non-partial index.
     */
    public List<String> indexes() {
        List<String> result = new ArrayList<>();
        keys.stream().filter(key -> !key.indexed())
                .forEach(key -> result.add("foreign key " + key.id() + " has no index on " + key.columns().get(0)));
        return result;
    }

    /** The keys by their name {@code table.first column}. */
    public Map<String, CatalogKey> byName() {
        Map<String, CatalogKey> byName = new HashMap<>();
        keys.forEach(key -> byName.put(key.childTable() + "." + key.columns().get(0), key));
        return byName;
    }
}
