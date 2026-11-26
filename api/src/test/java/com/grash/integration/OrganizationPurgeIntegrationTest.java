package com.grash.integration;

import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P-19 (E-10, D-2; CA-PPB-059, PPB-28): the purge of an organization is an operation procedure outside the
 * application. This test runs the very file the procedure cites ({@code scripts/purge/purge-organization.sql}) with
 * {@code psql}, inside the database container, on a database created for the test. Every table that is not global
 * gets rows of O2 and then of O1, written from the catalog with every foreign key filled, and extra rows that cite
 * the organization through each key of the tables the cascade from {@code company} does not reach (revisions,
 * history, readings, notifications). After the purge every table is as it was before O1 was written.
 */
class OrganizationPurgeIntegrationTest extends AbstractIntegrationTest {

    private static final String DATABASE = "p19_purge";
    private static final Path SCRIPT = Path.of("..", "scripts", "purge", "purge-organization.sql");
    private static final String SCRIPT_IN_CONTAINER = "/tmp/purge-organization.sql";
    private static final long GLOBAL = 900_001, O1 = 910_001, O2 = 920_001;
    /** Tables shared by every organization: never seeded per organization, never changed by the purge. */
    private static final Set<String> GLOBAL_TABLES = Set.of("currency", "subscription_plan",
            "subscription_plan_features", "keygen_request_tracker", "databasechangelog", "databasechangeloglock");

    record Column(String name, String type, boolean nullable, boolean hasDefault, Integer length) {
    }

    record ForeignKey(String column, String parent, String parentColumn) {
    }

    private final JdbcTemplate jdbc;

    @Autowired
    OrganizationPurgeIntegrationTest(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Test
    void ca059_organizationWithRowsInEveryTable_purged_nothingOfItLeft_otherOrganizationUnchanged() throws Exception {
        assertTrue(Files.isRegularFile(SCRIPT), "the script of the procedure: " + SCRIPT.toAbsolutePath());
        PostgreSQLContainer<?> container = getPostgresqlContainer();
        jdbc.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
        jdbc.execute("CREATE DATABASE " + DATABASE);
        String url = container.getJdbcUrl().replace("/" + container.getDatabaseName(), "/" + DATABASE);
        try (Connection connection = DriverManager.getConnection(url, container.getUsername(),
                container.getPassword())) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            new Liquibase("db/master.xml", new ClassLoaderResourceAccessor(), database).update("");
            if (!connection.getAutoCommit()) connection.commit();
            connection.setAutoCommit(true);
            JdbcTemplate db = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            Seeder seeder = new Seeder(db);

            db.execute("SET session_replication_role = replica");
            seeder.seedGlobalParents();
            seeder.seedOrganization(O2);
            Map<String, List<String>> withO2 = snapshot(db, seeder.tables);
            seeder.seedOrganization(O1);
            Map<String, List<String>> withO1 = snapshot(db, seeder.tables);
            db.execute("SET session_replication_role = DEFAULT");

            List<String> withoutRowsOfO1 = seeder.organizationTables().stream()
                    .filter(table -> withO1.get(table).equals(withO2.get(table))).toList();
            assertEquals(List.of(), withoutRowsOfO1, "O1 has rows in every table that is not global");

            ExecResult unknown = purge(container, 999_999);
            assertAll("an unknown organization: the script fails and nothing changes",
                    () -> assertNotEquals(0, unknown.getExitCode()),
                    () -> assertTrue(unknown.getStderr().contains("the company does not exist"), unknown.getStderr()),
                    () -> assertEquals(withO1, snapshot(db, seeder.tables)));

            ExecResult result = purge(container, O1);
            assertEquals(0, result.getExitCode(), result.getStdout() + result.getStderr());
            assertTrue(result.getStderr().contains("company " + O1 + " purged"), result.getStderr());
            Map<String, List<String>> after = snapshot(db, seeder.tables);
            List<String> changed = seeder.tables.stream().filter(table -> !after.get(table).equals(withO2.get(table)))
                    .toList();
            assertEquals(List.of(), changed, "tables not as before O1 was written (rows of O1 left, or O2 changed)");
        } finally {
            jdbc.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
        }
    }

    private ExecResult purge(PostgreSQLContainer<?> container, long company) throws Exception {
        // the bytes of the file, as they are in the repository (copyFileToContainer is not usable with the
        // commons-lang3 of the project)
        String encoded = Base64.getEncoder().encodeToString(Files.readAllBytes(SCRIPT));
        ExecResult copy = container.execInContainer("sh", "-c",
                "echo " + encoded + " | base64 -d > " + SCRIPT_IN_CONTAINER);
        assertEquals(0, copy.getExitCode(), copy.getStderr());
        return container.execInContainer("psql", "postgresql://" + container.getUsername() + ":"
                        + container.getPassword() + "@localhost/" + DATABASE, "-v", "ON_ERROR_STOP=1",
                "-v", "company=" + company, "-f", SCRIPT_IN_CONTAINER);
    }

    /** The fingerprint of every row of every table, sorted. */
    private static Map<String, List<String>> snapshot(JdbcTemplate db, List<String> tables) {
        Map<String, List<String>> rows = new TreeMap<>();
        tables.forEach(table -> rows.put(table, db.queryForList("SELECT md5(t::text) FROM " + quote(table)
                + " t ORDER BY 1", String.class)));
        return rows;
    }

    private static String quote(String identifier) {
        return "\"" + identifier + "\"";
    }

    /**
     * Writes rows from the catalog: one row per table and organization, whose identifier is the base of the
     * organization ({@link #O1}, {@link #O2}), so that each foreign key points to the row of its parent with the same
     * base. Self references are left empty. Triggers and foreign key checks are off while it writes.
     */
    private static final class Seeder {
        private final JdbcTemplate db;
        private final List<String> tables;
        private final Map<String, List<Column>> columns = new LinkedHashMap<>();
        private final Map<String, Set<String>> primaryKeys = new HashMap<>();
        private final Map<String, Map<String, ForeignKey>> foreignKeys = new HashMap<>();
        private final Set<String> cascade = new HashSet<>();
        private final Map<String, Long> globalRows = new HashMap<>();

        Seeder(JdbcTemplate db) {
            this.db = db;
            this.tables = db.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema = "
                    + "current_schema() AND table_type = 'BASE TABLE' ORDER BY 1", String.class);
            db.query("SELECT table_name, column_name, data_type, is_nullable, column_default, "
                    + "character_maximum_length FROM information_schema.columns WHERE table_schema = current_schema() "
                    + "ORDER BY table_name, ordinal_position", rs -> {
                columns.computeIfAbsent(rs.getString(1), table -> new ArrayList<>()).add(new Column(rs.getString(2),
                        rs.getString(3), "YES".equals(rs.getString(4)), rs.getString(5) != null,
                        (Integer) rs.getObject(6)));
            });
            db.query("SELECT t.relname, a.attname FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid "
                    + "JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY (c.conkey) "
                    + "WHERE c.contype = 'p' AND c.connamespace = current_schema()::regnamespace", rs -> {
                primaryKeys.computeIfAbsent(rs.getString(1), table -> new HashSet<>()).add(rs.getString(2));
            });
            List<String[]> cascadeKeys = new ArrayList<>();
            db.query("SELECT t.relname, a.attname, p.relname, pa.attname, c.confdeltype::text, "
                    + "array_length(c.conkey, 1) FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid "
                    + "JOIN pg_class p ON p.oid = c.confrelid "
                    + "JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = c.conkey[1] "
                    + "JOIN pg_attribute pa ON pa.attrelid = c.confrelid AND pa.attnum = c.confkey[1] "
                    + "WHERE c.contype = 'f' AND c.connamespace = current_schema()::regnamespace", rs -> {
                if (rs.getInt(6) != 1) return; // the keys of Quartz, global tables
                foreignKeys.computeIfAbsent(rs.getString(1), table -> new LinkedHashMap<>())
                        .put(rs.getString(2), new ForeignKey(rs.getString(2), rs.getString(3), rs.getString(4)));
                if ("c".equals(rs.getString(5))) cascadeKeys.add(new String[]{rs.getString(1), rs.getString(3)});
            });
            cascade.add("company");
            for (boolean grown = true; grown; ) {
                grown = false;
                for (String[] key : cascadeKeys) if (cascade.contains(key[1]) && cascade.add(key[0])) grown = true;
            }
        }

        List<String> organizationTables() {
            return tables.stream().filter(table -> !global(table)).toList();
        }

        private static boolean global(String table) {
            return GLOBAL_TABLES.contains(table) || table.startsWith("qrtz_");
        }

        /** A row in each global table that an organization cites; an existing one if there is any. */
        void seedGlobalParents() {
            for (String table : organizationTables())
                for (ForeignKey key : keys(table).values())
                    if (global(key.parent()) && !globalRows.containsKey(key.parent())) {
                        Long existing = db.queryForObject("SELECT min(" + quote(key.parentColumn()) + ") FROM "
                                + quote(key.parent()), Long.class);
                        if (existing == null) {
                            insert(key.parent(), GLOBAL, 0, null);
                            existing = GLOBAL;
                        }
                        globalRows.put(key.parent(), existing);
                    }
        }

        void seedOrganization(long base) {
            for (String table : organizationTables()) insert(table, base, 0, null);
            // the tables outside the cascade from company: one more row per key into it, citing only that key
            int extra = 100;
            for (String table : organizationTables()) {
                if (cascade.contains(table)) continue;
                for (ForeignKey key : keys(table).values())
                    if (cascade.contains(key.parent()) && !key.parent().equals(table)) insert(table, base, extra++, key);
            }
        }

        private Map<String, ForeignKey> keys(String table) {
            return foreignKeys.getOrDefault(table, Map.of());
        }

        /**
         * One row of {@code table} for the organization {@code base}. With {@code only}, the nullable foreign keys
         * other than {@code only} are left empty.
         */
        private void insert(String table, long base, int offset, ForeignKey only) {
            List<String> names = new ArrayList<>(), values = new ArrayList<>();
            Set<String> primaryKey = primaryKeys.getOrDefault(table, Set.of());
            for (Column column : columns.get(table)) {
                ForeignKey key = keys(table).get(column.name());
                String value;
                if (key != null) {
                    boolean skip = key.parent().equals(table) || only != null && key != only;
                    if (skip && column.nullable()) continue;
                    value = String.valueOf(global(key.parent()) && base != GLOBAL ? globalRows.get(key.parent())
                            : key.parent().equals(table) ? base + offset : base);
                } else if ("rev".equals(column.name())) {
                    value = String.valueOf(base); // the revision record of the organization (no foreign key)
                } else if (primaryKey.contains(column.name()) && isInteger(column)) {
                    value = String.valueOf(base + offset);
                } else if (column.nullable() || column.hasDefault()) {
                    continue;
                } else {
                    value = literal(table, column, base, offset);
                }
                names.add(quote(column.name()));
                values.add(value);
            }
            db.execute("INSERT INTO " + quote(table) + " (" + String.join(", ", names) + ") VALUES ("
                    + String.join(", ", values) + ")");
        }

        private static boolean isInteger(Column column) {
            return Set.of("bigint", "integer", "smallint").contains(column.type());
        }

        private static String literal(String table, Column column, long base, int offset) {
            return switch (column.type()) {
                case "bigint", "integer", "smallint", "numeric", "double precision" -> "1";
                case "boolean" -> "false";
                case "date" -> "current_date";
                case "timestamp with time zone", "timestamp without time zone" -> "now()";
                case "jsonb" -> "'{}'::jsonb";
                case "bytea" -> "'\\x00'::bytea";
                case "character varying", "text" -> {
                    String text = "P-19 " + base + "-" + offset + " " + table + "." + column.name();
                    yield "'" + (column.length() == null ? text : text.substring(0, Math.min(text.length(),
                            column.length()))).replace("'", "''") + "'";
                }
                default -> throw new IllegalStateException("no value for " + table + "." + column.name() + " "
                        + column.type());
            };
        }
    }
}
