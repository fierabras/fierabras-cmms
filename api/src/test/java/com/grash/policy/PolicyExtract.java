package com.grash.policy;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * P-19 (E-4, PPB-01, PPB-21, PPB-29): the versioned extract of the persistence and deletion policy, read from
 * {@code src/test/resources/policy/} (see its {@code README.md}). A foreign key is identified by its child table and
 * its first child column, as in the policy. Unknown values, repeated rows and unregistered exceptions are reported by
 * {@link #formatErrors()}; a rule other than the one of its case without an exception, by {@link #incoherent()}.
 */
public final class PolicyExtract {

    public enum Rule {
        NO_ACTION("NO ACTION", "a"), RESTRICT("RESTRICT", "r"), CASCADE("CASCADE", "c"), SET_NULL("SET NULL", "n"),
        SET_DEFAULT("SET DEFAULT", "d");

        private final String sql;
        private final String catalogCode;

        Rule(String sql, String catalogCode) {
            this.sql = sql;
            this.catalogCode = catalogCode;
        }

        public String sql() {
            return sql;
        }

        /** The rule of a {@code pg_constraint.confdeltype} code. */
        public static Rule ofCatalog(String code) {
            return Arrays.stream(values()).filter(rule -> rule.catalogCode.equals(code)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("unknown confdeltype " + code));
        }

        static Rule ofSql(String sql) {
            return Arrays.stream(values()).filter(rule -> rule.sql.equals(sql)).findFirst().orElse(null);
        }
    }

    public enum Relation {COMPOSITION, REFERENCE, ASSOCIATION, TECHNICAL}

    public enum TableClass {
        ORGANIZATION, CONFIGURATION, HISTORICAL_RECORD, TECHNICAL_RECORD, TEMPLATE, CATALOG, FILE, ROOT_ENTITY,
        COMPOSITION_CHILD, ASSOCIATION_TABLE
    }

    public record Key(String childTable, String childColumn, String parentTable, Rule targetRule, Relation relation,
                      String exception) {
        public String id() {
            return childTable + "." + childColumn + " -> " + parentTable;
        }
    }

    /** PPB-21, case 3: the RESTRICT that the policy keeps. */
    static final String KEPT_RESTRICT = "own_user.role_id";
    /** PPB-21, case 4: the reverse 1:1 pointers to a child that is removed by itself, named by the policy. */
    static final Set<String> REVERSE_POINTERS = Set.of("asset.deprecation_id");

    private static final String ORGANIZATION_TABLE = "company";
    private static final String FOLDER = "policy/";

    private final List<Key> keys = new ArrayList<>();
    private final Map<String, TableClass> tables = new LinkedHashMap<>();
    private final Set<String> exceptions = new HashSet<>();
    private final List<String> formatErrors = new ArrayList<>();

    private PolicyExtract() {
    }

    /** The extract of the policy kept in the test resources. */
    public static PolicyExtract load() {
        return parse(resource("foreign-keys.csv"), resource("tables.csv"), resource("exceptions.csv"));
    }

    /** An extract from the text of its three files, as they are written in the resources. */
    public static PolicyExtract parse(String foreignKeys, String tables, String exceptions) {
        PolicyExtract extract = new PolicyExtract();
        List<String[]> exceptionRows = rows(exceptions, "exceptions.csv", 6, extract.formatErrors);
        exceptionRows.forEach(row -> {
            if (!row[0].matches("EX-\\d{2}")) extract.formatErrors.add("exceptions.csv: bad id " + row[0]);
            else if (!extract.exceptions.add(row[0])) extract.formatErrors.add("exceptions.csv: repeated " + row[0]);
        });
        for (String[] row : rows(tables, "tables.csv", 2, extract.formatErrors)) {
            TableClass tableClass = value(TableClass::valueOf, row[1]);
            if (tableClass == null) extract.formatErrors.add("tables.csv: " + row[0] + ": unknown class " + row[1]);
            else if (extract.tables.put(row[0], tableClass) != null)
                extract.formatErrors.add("tables.csv: " + row[0] + " appears more than once");
        }
        Set<String> seen = new HashSet<>();
        for (String[] row : rows(foreignKeys, "foreign-keys.csv", 6, extract.formatErrors)) {
            String name = row[0] + "." + row[1];
            Rule rule = Rule.ofSql(row[3]);
            Relation relation = value(Relation::valueOf, row[4]);
            if (rule == null || rule == Rule.SET_DEFAULT)
                extract.formatErrors.add("foreign-keys.csv: " + name + ": unknown rule " + row[3]);
            if (relation == null) extract.formatErrors.add("foreign-keys.csv: " + name + ": unknown relation " + row[4]);
            if (!row[5].isEmpty() && !extract.exceptions.contains(row[5]))
                extract.formatErrors.add("foreign-keys.csv: " + name + ": cites " + row[5] + ", not registered");
            if (!seen.add(name)) extract.formatErrors.add("foreign-keys.csv: " + name + " appears more than once");
            for (String table : List.of(row[0], row[2]))
                if (!extract.tables.containsKey(table))
                    extract.formatErrors.add("foreign-keys.csv: " + name + ": table " + table + " has no class");
            if (rule != null && relation != null)
                extract.keys.add(new Key(row[0], row[1], row[2], rule, relation, row[5]));
        }
        return extract;
    }

    public List<Key> keys() {
        return keys;
    }

    public Map<String, TableClass> tables() {
        return tables;
    }

    public List<String> formatErrors() {
        return formatErrors;
    }

    /**
     * CA-PPB-078, 079: the rows whose target rule is not the one of their case (PPB-21) and cite no exception.
     */
    public List<String> incoherent() {
        List<String> result = new ArrayList<>();
        for (Key key : keys)
            if (key.exception().isEmpty() && !coherent(key))
                result.add(key.id() + ": " + key.targetRule().sql() + " is not the rule of its case");
        return result;
    }

    /**
     * Whether the target rule of {@code key} is the one of its case in PPB-21. A historical record that belongs to
     * the organization is case 1 (§4 of the policy: the membership of the organization keeps CASCADE; D-P19-7).
     */
    boolean coherent(Key key) {
        String name = key.childTable() + "." + key.childColumn();
        Rule rule = key.targetRule();
        return switch (key.relation()) {
            case COMPOSITION -> rule == (historical(key.childTable()) && !ORGANIZATION_TABLE.equals(key.parentTable())
                    ? Rule.NO_ACTION : Rule.CASCADE);
            case REFERENCE -> rule == (KEPT_RESTRICT.equals(name) ? Rule.RESTRICT
                    : REVERSE_POINTERS.contains(name) ? Rule.SET_NULL : Rule.NO_ACTION);
            case ASSOCIATION -> rule == Rule.CASCADE;
            // case 6: the current rule is kept, unless it spreads a deletion to business data
            case TECHNICAL -> rule != Rule.CASCADE || technicalOrAssociation(key.childTable());
        };
    }

    /** Whether {@code table} is a historical record (PPB-03, PPB-04). */
    public boolean historical(String table) {
        return tables.get(table) == TableClass.HISTORICAL_RECORD;
    }

    private boolean technicalOrAssociation(String table) {
        TableClass tableClass = tables.get(table);
        return tableClass == TableClass.TECHNICAL_RECORD || tableClass == TableClass.ASSOCIATION_TABLE;
    }

    private static <T> T value(Function<String, T> parser, String text) {
        try {
            return parser.apply(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The data rows of a file whose values never hold commas or quotes; the first line is the header. */
    private static List<String[]> rows(String text, String file, int columns, List<String> errors) {
        List<String[]> rows = new ArrayList<>();
        String[] lines = text.replace("﻿", "").split("\r?\n");
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isBlank()) continue;
            String[] row = lines[i].split(",", -1);
            if (row.length != columns) errors.add(file + ": line " + (i + 1) + " has " + row.length + " columns");
            else rows.add(Arrays.stream(row).map(String::trim).toArray(String[]::new));
        }
        return rows;
    }

    private static String resource(String name) {
        try (InputStream in = PolicyExtract.class.getClassLoader().getResourceAsStream(FOLDER + name)) {
            if (in == null) throw new IllegalStateException("missing resource " + FOLDER + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The keys by their name {@code table.first column}. */
    public Map<String, Key> byName() {
        Map<String, Key> byName = new HashMap<>();
        keys.forEach(key -> byName.put(key.childTable() + "." + key.childColumn(), key));
        return byName;
    }
}
