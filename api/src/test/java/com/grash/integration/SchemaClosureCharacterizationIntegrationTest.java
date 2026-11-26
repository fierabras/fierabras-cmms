package com.grash.integration;

import com.grash.integration.support.TestAssetRecords;
import com.grash.integration.support.TestOrganizations;
import com.grash.integration.support.TestOrganizations.CommonData;
import com.grash.integration.support.TestRecords;
import com.grash.model.Asset;
import com.grash.model.Company;
import com.grash.model.Location;
import com.grash.model.Meter;
import com.grash.model.enums.Status;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * P-19: characterization of the schema before any change. Extracts from the catalog of the database migrated by
 * Liquibase the tables, the foreign keys, their rules and their indexes; compares them with the two CSV files of the
 * policy when {@code -Dp19.policy=<folder>} is given; and records what stops the purge of an organization today.
 * The results are written under {@code -Dp19.out} (default {@code target/p19}). Nothing is changed.
 */
class SchemaClosureCharacterizationIntegrationTest extends AbstractIntegrationTest {

    static final String TABLES = "SELECT table_name FROM information_schema.tables "
            + "WHERE table_schema = current_schema() AND table_type = 'BASE TABLE' ORDER BY 1";

    /** One row per foreign key: child, child columns, parent, rule letter, name, child column leads an index. */
    static final String FOREIGN_KEYS = """
            SELECT child.relname AS child,
                   (SELECT string_agg(a.attname, ',' ORDER BY k.ord) FROM unnest(c.conkey) WITH ORDINALITY k(att, ord)
                    JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.att) AS columns,
                   parent.relname AS parent, c.confdeltype::text AS rule, c.conname AS name,
                   EXISTS (SELECT 1 FROM pg_index i WHERE i.indrelid = c.conrelid AND i.indkey[0] = c.conkey[1]
                           AND i.indisvalid AND i.indpred IS NULL) AS indexed
            FROM pg_constraint c
            JOIN pg_class child ON child.oid = c.conrelid
            JOIN pg_class parent ON parent.oid = c.confrelid
            JOIN pg_namespace n ON n.oid = c.connamespace
            WHERE c.contype = 'f' AND n.nspname = current_schema()
            ORDER BY 1, 2, 3""";

    private static final Map<String, String> RULES = Map.of("a", "NO ACTION", "r", "RESTRICT", "c", "CASCADE",
            "n", "SET NULL", "d", "SET DEFAULT");

    record Fk(String child, String columns, String parent, String rule, String name, boolean indexed) {
        String key() {
            return child + "." + columns;
        }
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;
    private final TestOrganizations organizations;
    private final TestRecords records;
    private final TestAssetRecords assetRecords;

    @Autowired
    SchemaClosureCharacterizationIntegrationTest(JdbcTemplate jdbc, TransactionTemplate transactionTemplate,
                                                 TestOrganizations organizations, TestRecords records,
                                                 TestAssetRecords assetRecords) {
        this.jdbc = jdbc;
        this.transactionTemplate = transactionTemplate;
        this.organizations = organizations;
        this.records = records;
        this.assetRecords = assetRecords;
    }

    @Test
    void catalog_extractedAndComparedWithThePolicy() throws IOException {
        List<String> tables = jdbc.queryForList(TABLES, String.class);
        List<Fk> fks = foreignKeys();
        List<String> lines = new ArrayList<>();
        lines.add("tables " + tables.size() + ", foreign keys " + fks.size() + ", multi-column "
                + fks.stream().filter(fk -> fk.columns().contains(",")).count());
        lines.add("rules " + fks.stream().collect(Collectors.groupingBy(Fk::rule, Collectors.counting())));
        lines.add("without index " + fks.stream().filter(fk -> !fk.indexed()).count() + " "
                + fks.stream().filter(fk -> !fk.indexed()).collect(Collectors.groupingBy(Fk::rule,
                Collectors.counting())));
        write("catalogo_resumen.txt", lines);
        write("catalogo_tablas.txt", tables);
        write("catalogo_fk.csv", fks.stream().map(fk -> String.join(";", fk.child(), fk.columns(), fk.parent(),
                fk.rule(), fk.name(), String.valueOf(fk.indexed()))).toList());
        assertFalse(fks.isEmpty());

        String policy = System.getProperty("p19.policy");
        Assumptions.assumeTrue(policy != null, "no -Dp19.policy: comparison skipped");
        List<Map<String, String>> fkRows = csv(Path.of(policy, "PPB_Reglas_FK_20112026.csv"));
        List<Map<String, String>> tableRows = csv(Path.of(policy, "PPB_Clasificacion_Entidades_20112026.csv"));

        List<String> out = new ArrayList<>();
        Set<String> csvTables = tableRows.stream().map(row -> row.get("tabla")).collect(Collectors.toCollection(TreeSet::new));
        out.add("## Tables in the catalog without a row (" + count(tables, t -> !csvTables.contains(t)) + ")");
        tables.stream().filter(t -> !csvTables.contains(t)).forEach(out::add);
        out.add("## Table rows without a table (" + count(csvTables, t -> !tables.contains(t)) + ")");
        csvTables.stream().filter(t -> !tables.contains(t)).forEach(out::add);

        Map<String, Map<String, String>> byKey = new LinkedHashMap<>();
        fkRows.forEach(row -> byKey.put(row.get("tabla_hija") + "." + row.get("columna"), row));
        Map<String, Fk> catalog = new LinkedHashMap<>();
        fks.forEach(fk -> catalog.put(fk.key(), fk));
        out.add("## Keys in the catalog without a row (" + count(catalog.keySet(), k -> !byKey.containsKey(k)) + ")");
        fks.stream().filter(fk -> !byKey.containsKey(fk.key())).forEach(fk -> out.add(describe(fk)));
        out.add("## Key rows without a key (" + count(byKey.keySet(), k -> !catalog.containsKey(k)) + ")");
        byKey.forEach((k, row) -> {
            if (!catalog.containsKey(k)) out.add(k + " -> " + row.get("tabla_padre") + " " + row.get("regla_actual"));
        });
        List<String> parent = new ArrayList<>(), current = new ArrayList<>(), target = new ArrayList<>(),
                index = new ArrayList<>(), missingIndex = new ArrayList<>();
        catalog.forEach((k, fk) -> {
            Map<String, String> row = byKey.get(k);
            if (!fk.indexed()) missingIndex.add(describe(fk) + (row == null ? "" : " | " + row.get("tipo_de_relacion")));
            if (row == null) return;
            if (!row.get("tabla_padre").equals(fk.parent())) parent.add(describe(fk) + " | csv " + row.get("tabla_padre"));
            if (!row.get("regla_actual").equals(RULES.get(fk.rule())))
                current.add(describe(fk) + " | csv regla_actual " + row.get("regla_actual"));
            if (!row.get("regla_objetivo").equals(RULES.get(fk.rule())))
                target.add(describe(fk) + " | csv regla_objetivo " + row.get("regla_objetivo") + " | "
                        + row.get("tipo_de_relacion") + " | cambia " + row.get("cambia"));
            if (!row.get("tiene_indice").equals(fk.indexed() ? "Sí" : "No"))
                index.add(describe(fk) + " | csv tiene_indice " + row.get("tiene_indice"));
        });
        section(out, "Parent differs", parent);
        section(out, "Catalog rule differs from regla_actual", current);
        section(out, "Catalog rule differs from regla_objetivo", target);
        section(out, "tiene_indice differs from the catalog", index);
        section(out, "Keys without a leading valid, non-partial index", missingIndex);
        write("diferencias_catalogo_csv.txt", out);
    }

    /**
     * Which tables the database empties in cascade from {@code company}, and which keys stop or alter that cascade:
     * protected keys (NO ACTION, RESTRICT) from a table outside the cascade to one inside, and SET NULL keys from
     * outside. Tables outside the cascade that hold rows of an organization must be emptied first.
     */
    @Test
    void organizationPurge_cascadeFromCompany_analysed() throws IOException {
        List<Fk> fks = foreignKeys();
        Set<String> cascade = new TreeSet<>(Set.of("company"));
        boolean grown = true;
        while (grown) {
            grown = false;
            for (Fk fk : fks)
                if (fk.rule().equals("c") && cascade.contains(fk.parent()) && cascade.add(fk.child())) grown = true;
        }
        List<String> out = new ArrayList<>();
        out.add("## Tables deleted in cascade from company (" + cascade.size() + ")");
        out.add(String.join(" ", cascade));
        out.add("## Tables outside the cascade");
        out.add(jdbc.queryForList(TABLES, String.class).stream().filter(t -> !cascade.contains(t))
                .collect(Collectors.joining(" ")));
        out.add("## Protected keys from outside the cascade to a table inside it (block the purge when they have rows)");
        fks.stream().filter(fk -> !cascade.contains(fk.child()) && cascade.contains(fk.parent())
                && (fk.rule().equals("a") || fk.rule().equals("r"))).forEach(fk -> out.add(describe(fk)));
        out.add("## SET NULL keys from outside the cascade to a table inside it (rows changed by the purge)");
        fks.stream().filter(fk -> !cascade.contains(fk.child()) && cascade.contains(fk.parent())
                && fk.rule().equals("n")).forEach(fk -> out.add(describe(fk)));
        out.add("## RESTRICT keys inside the cascade (checked at once, may fail inside one statement)");
        fks.stream().filter(fk -> cascade.contains(fk.child()) && cascade.contains(fk.parent())
                && fk.rule().equals("r")).forEach(fk -> out.add(describe(fk)));
        out.add("## Keys from tables inside the cascade to tables outside it (rows left behind when not shared)");
        fks.stream().filter(fk -> cascade.contains(fk.child()) && !cascade.contains(fk.parent()))
                .forEach(fk -> out.add(describe(fk)));
        write("purga_analisis_catalogo.txt", out);
    }

    /**
     * {@code DELETE FROM company} on an organization with a request portal, a work order revision and a reading,
     * as it is today; then with the revisions, the readings and the portals deleted first. Every attempt is rolled
     * back.
     */
    @Test
    void organizationPurge_today_firstFailingKey() throws IOException {
        CommonData data = organizations.create();
        Company o1 = data.o1().company();
        List<String> out = new ArrayList<>();
        try {
            Location location = records.location(o1);
            Asset asset = records.asset(o1);
            records.portalCiting(o1, location, null);
            records.workOrder(o1, asset, Status.OPEN, false);
            Meter meter = assetRecords.meter(o1, asset);
            assetRecords.reading(meter, 1);
            out.add("work_order_aud rows of O1: " + jdbc.queryForObject("SELECT count(*) FROM work_order_aud WHERE id "
                    + "IN (SELECT id FROM work_order WHERE company_id = ?)", Long.class, o1.getId()));
            out.add("plain DELETE FROM company: " + attempt(o1.getId(), false, false, false));
            out.add("after revisions: " + attempt(o1.getId(), true, false, false));
            out.add("after revisions and readings: " + attempt(o1.getId(), true, true, false));
            out.add("after revisions, readings and portals: " + attempt(o1.getId(), true, true, true));
        } finally {
            records.deleteRequestPortals(data);
            organizations.delete(data);
        }
        write("purga_organizacion_hoy.txt", out);
    }

    /**
     * The purge with what the earlier attempt did not have: a user with a role of the organization (own_user.role_id
     * is RESTRICT), a work order history row and a notification. Rolled back.
     */
    @Test
    void organizationPurge_withRoleHistoryAndNotification() throws IOException {
        CommonData data = organizations.create();
        Long o1 = data.o1().company().getId();
        List<String> out = new ArrayList<>();
        try {
            Long role = records.role(data.o1().company()).getId();
            jdbc.update("UPDATE own_user SET role_id = ? WHERE id = ?", role, data.o1().technician().getId());
            Long workOrder = records.workOrder(data.o1().company(), null, Status.OPEN, false).getId();
            out.add("with a role of O1 assigned: " + attempt(o1, true, true, true));
            jdbc.update("INSERT INTO work_order_history (id, created_at, updated_at, name, work_order_id, user_id) "
                    + "VALUES (nextval('work_order_history_seq'), now(), now(), 'P-19', ?, ?)", workOrder,
                    data.o1().admin().getId());
            out.add("with a work order history row: " + attempt(o1, true, true, true));
            jdbc.update("DELETE FROM work_order_history WHERE work_order_id = ?", workOrder);
            jdbc.update("INSERT INTO notification (id, created_at, updated_at, message, seen, user_id) "
                    + "VALUES (nextval('notification_seq'), now(), now(), 'P-19', false, ?)", data.o1().admin().getId());
            out.add("with a notification: " + transactionTemplate.execute(status -> {
                status.setRollbackOnly();
                TestOrganizations.deleteWorkOrderRevisions(jdbc, o1);
                jdbc.update("DELETE FROM company WHERE id = ?", o1);
                return "completed; notifications left with user_id null: " + jdbc.queryForObject(
                        "SELECT count(*) FROM notification WHERE message = 'P-19' AND user_id IS NULL", Long.class)
                        + "; user_settings rows left: " + jdbc.queryForObject("SELECT count(*) FROM user_settings "
                        + "WHERE id IN (?, ?, ?, ?)", Long.class, data.o1().owner().getUserSettings().getId(),
                        data.o1().admin().getUserSettings().getId(), data.o1().technician().getUserSettings().getId(),
                        -1L);
            }));
            jdbc.update("DELETE FROM notification WHERE message = 'P-19'");
        } finally {
            jdbc.update("DELETE FROM work_order_history WHERE name = 'P-19'");
            records.deleteRequestPortals(data);
            organizations.delete(data);
        }
        write("purga_organizacion_hoy_2.txt", out);
    }

    /**
     * Facts for the design: columns ending in {@code _id} without a foreign key, CHECK constraints, tables with
     * {@code is_demo} and {@code company_id}, unique constraints, and the columns that are NOT NULL without default.
     */
    @Test
    void schemaFacts_forTheDesign() throws IOException {
        List<String> out = new ArrayList<>();
        out.add("## Columns *_id without a foreign key (not the primary key)");
        out.addAll(jdbc.queryForList("""
                SELECT c.table_name || '.' || c.column_name || ' ' || c.data_type FROM information_schema.columns c
                JOIN information_schema.tables t ON t.table_schema = c.table_schema AND t.table_name = c.table_name
                WHERE c.table_schema = current_schema() AND t.table_type = 'BASE TABLE' AND c.column_name LIKE '%\\_id'
                  AND NOT EXISTS (SELECT 1 FROM pg_constraint k JOIN pg_attribute a ON a.attrelid = k.conrelid
                                  AND a.attnum = ANY (k.conkey)
                                  WHERE k.contype IN ('f', 'p') AND k.conrelid = (quote_ident(c.table_name))::regclass
                                  AND a.attname = c.column_name)
                ORDER BY 1""", String.class));
        out.add("## CHECK constraints");
        out.addAll(jdbc.queryForList("SELECT conrelid::regclass::text || ' ' || conname || ' ' || pg_get_constraintdef(oid) "
                + "FROM pg_constraint WHERE contype = 'c' AND connamespace = current_schema()::regnamespace ORDER BY 1",
                String.class));
        out.add("## Tables with is_demo and company_id");
        out.addAll(jdbc.queryForList("SELECT a.table_name FROM information_schema.columns a JOIN information_schema"
                + ".columns b ON b.table_schema = a.table_schema AND b.table_name = a.table_name AND b.column_name = "
                + "'company_id' WHERE a.table_schema = current_schema() AND a.column_name = 'is_demo' ORDER BY 1",
                String.class));
        out.add("## Unique constraints and unique indexes (not primary keys)");
        out.addAll(jdbc.queryForList("SELECT i.indrelid::regclass::text || ' ' || c.relname || ' ' || "
                + "pg_get_indexdef(i.indexrelid) FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid "
                + "JOIN pg_class t ON t.oid = i.indrelid WHERE i.indisunique AND NOT i.indisprimary "
                + "AND t.relnamespace = current_schema()::regnamespace ORDER BY 1", String.class));
        out.add("## Indexes of custom_sequence");
        out.addAll(jdbc.queryForList("SELECT indexname || ' ' || indexdef FROM pg_indexes WHERE tablename = "
                + "'custom_sequence' ORDER BY 1", String.class));
        out.add("## Tables without a primary key");
        out.addAll(jdbc.queryForList(TABLES.replace("ORDER BY 1", "AND NOT EXISTS (SELECT 1 FROM pg_constraint k "
                + "WHERE k.contype = 'p' AND k.conrelid = (quote_ident(table_name))::regclass) ORDER BY 1"), String.class));
        out.add("## Columns NOT NULL without default: table.column type");
        out.addAll(jdbc.queryForList("SELECT c.table_name || '.' || c.column_name || ' ' || c.data_type || "
                + "COALESCE('(' || c.character_maximum_length || ')', '') FROM information_schema.columns c "
                + "JOIN information_schema.tables t ON t.table_schema = c.table_schema AND t.table_name = c.table_name "
                + "WHERE c.table_schema = current_schema() AND t.table_type = 'BASE TABLE' AND c.is_nullable = 'NO' "
                + "AND c.column_default IS NULL ORDER BY 1", String.class));
        out.add("## Data types in use");
        out.addAll(jdbc.queryForList("SELECT DISTINCT c.data_type FROM information_schema.columns c "
                + "WHERE c.table_schema = current_schema() ORDER BY 1", String.class));
        write("esquema_hechos.txt", out);
    }

    private String attempt(Long company, boolean revisions, boolean readings, boolean portals) {
        try {
            return transactionTemplate.execute(status -> {
                status.setRollbackOnly();
                if (revisions) TestOrganizations.deleteWorkOrderRevisions(jdbc, company);
                if (readings) TestOrganizations.deleteReadings(jdbc, company);
                if (portals) {
                    jdbc.update("UPDATE request SET request_portal_id = NULL WHERE company_id = ?", company);
                    jdbc.update("DELETE FROM request_portal_field WHERE company_id = ?", company);
                    jdbc.update("DELETE FROM request_portal WHERE company_id = ?", company);
                }
                jdbc.update("DELETE FROM company WHERE id = ?", company);
                return "completed (rolled back)";
            });
        } catch (RuntimeException e) {
            Throwable cause = e;
            while (cause.getCause() != null) cause = cause.getCause();
            return "FAILED " + cause.getMessage().replaceAll("\\s+", " ");
        }
    }

    private List<Fk> foreignKeys() {
        return jdbc.query(FOREIGN_KEYS, (rs, i) -> new Fk(rs.getString("child"), rs.getString("columns"),
                rs.getString("parent"), rs.getString("rule"), rs.getString("name"), rs.getBoolean("indexed")));
    }

    private static String describe(Fk fk) {
        return fk.child() + "." + fk.columns() + " -> " + fk.parent() + " " + RULES.get(fk.rule()) + " ("
                + fk.name() + (fk.indexed() ? ", indexed" : ", no index") + ")";
    }

    private static void section(List<String> out, String title, List<String> items) {
        out.add("## " + title + " (" + items.size() + ")");
        out.addAll(items);
    }

    private static <T> long count(Iterable<T> items, java.util.function.Predicate<T> filter) {
        long n = 0;
        for (T item : items) if (filter.test(item)) n++;
        return n;
    }

    private static void write(String name, List<String> lines) throws IOException {
        Path folder = Path.of(System.getProperty("p19.out", "target/p19"));
        Files.createDirectories(folder);
        Files.write(folder.resolve(name), lines, StandardCharsets.UTF_8);
    }

    /** A CSV with a header row, quoted fields and doubled quotes, as the policy files are written. */
    static List<Map<String, String>> csv(Path path) throws IOException {
        String text = Files.readString(path, StandardCharsets.UTF_8).replace("﻿", "");
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else if (c == '"') quoted = false;
                else field.append(c);
            } else if (c == '"') quoted = true;
            else if (c == ',') {
                row.add(field.toString());
                field.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                row.add(field.toString());
                field.setLength(0);
                if (row.size() > 1) rows.add(row);
                row = new ArrayList<>();
            } else field.append(c);
        }
        if (field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        List<String> header = rows.get(0);
        List<Map<String, String>> result = new ArrayList<>();
        for (List<String> r : rows.subList(1, rows.size())) {
            Map<String, String> map = new LinkedHashMap<>();
            for (int i = 0; i < header.size(); i++) map.put(header.get(i), i < r.size() ? r.get(i) : "");
            result.add(map);
        }
        return result;
    }
}
