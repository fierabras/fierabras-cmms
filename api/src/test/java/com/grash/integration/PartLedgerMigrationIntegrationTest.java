package com.grash.integration;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.changelog.ChangeSet;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P-16b (E-11, E-12, D-1, D-2): the migration of the ledger on data written before it, in a database created for the
 * test in the same container (never a database of somebody else). Every changeset except those of P-16b is applied,
 * data of before is seeded, the changesets of P-16b are applied and checked, then rolled back and checked again.
 */
class PartLedgerMigrationIntegrationTest extends AbstractIntegrationTest {

    private static final String DATABASE = "p16b_migration";
    private static final String CHANGE_LOG = "db/master.xml";
    private static final String OPENING_BALANCE = "Opening balance (migration)";
    private static final long COMPANY = 900_001;

    /** Parts whose stock differs from the sum of their movements, after the change of sign: 0 rows. */
    static final String CHECK = "SELECT p.id FROM part p LEFT JOIN part_transaction t ON t.part_id = p.id "
            + "GROUP BY p.id, p.quantity HAVING abs(p.quantity - COALESCE(SUM(t.quantity), 0)) > 1e-9";

    private final JdbcTemplate jdbc;

    @Autowired
    PartLedgerMigrationIntegrationTest(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Test
    void migrationOnEarlierData_signOriginOpeningBalanceAndKeys_thenRolledBack() throws Exception {
        PostgreSQLContainer<?> container = getPostgresqlContainer();
        jdbc.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
        jdbc.execute("CREATE DATABASE " + DATABASE);
        String url = container.getJdbcUrl().replace("/" + container.getDatabaseName(), "/" + DATABASE);
        try (Connection connection = DriverManager.getConnection(url, container.getUsername(),
                container.getPassword())) {
            JdbcTemplate db = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            Liquibase liquibase = new Liquibase(CHANGE_LOG, new ClassLoaderResourceAccessor(), database);
            List<ChangeSet> unrun = liquibase.listUnrunChangeSets(new Contexts(), new LabelExpression());
            int ledger = (int) unrun.stream().filter(changeSet -> changeSet.getId().contains("_part_ledger_")).count();
            assertEquals(6, ledger);
            assertTrue(unrun.subList(unrun.size() - ledger, unrun.size()).stream()
                    .allMatch(changeSet -> changeSet.getId().contains("_part_ledger_")), "P-16b changesets are last");

            liquibase.update(unrun.size() - ledger, "");
            seedEarlierData(db);
            if (!connection.getAutoCommit()) connection.commit();
            List<String> earlier = db.queryForList("SELECT id || ' ' || quantity FROM part_transaction ORDER BY id",
                    String.class);

            liquibase.update("");

            assertAll(
                    // D-1: the movements of before change sign and get an origin
                    () -> assertEquals(List.of("10 -3 CONSUMPTION", "11 10 RESTOCK", "30 -0 CONSUMPTION",
                            "31 -1 CONSUMPTION"), db.queryForList("SELECT id || ' ' || quantity || ' ' || origin "
                            + "FROM part_transaction WHERE id < 1000 ORDER BY id", String.class)),
                    // D-2: one opening balance per part whose stock differs, without user, demo like its part
                    () -> assertEquals(List.of("2 5 ADJUSTMENT - " + OPENING_BALANCE + " false",
                            "3 5 ADJUSTMENT - " + OPENING_BALANCE + " false",
                            "4 2 ADJUSTMENT - " + OPENING_BALANCE + " false",
                            "5 10 ADJUSTMENT - " + OPENING_BALANCE + " true"),
                            db.queryForList("SELECT part_id || ' ' || quantity || ' ' || origin || ' ' "
                                    + "|| COALESCE(created_by::text, '-') || ' ' || description || ' ' || is_demo "
                                    + "FROM part_transaction WHERE id >= 1000 ORDER BY part_id", String.class)),
                    () -> assertEquals(List.of(), db.queryForList(CHECK, Long.class), "stock = sum of movements"),
                    () -> assertEquals("NO", db.queryForObject("SELECT is_nullable FROM information_schema.columns "
                            + "WHERE table_name = 'part_transaction' AND column_name = 'origin'", String.class)),
                    () -> assertEquals(List.of("purchase_order_id fk_part_transaction_purchase_order a true",
                            "work_order_id fkj99sa2uououd6s977aqyc0yri a true"), keys(db)),
                    () -> assertEquals(7.0, db.queryForObject("SELECT quantity FROM part WHERE id = 1",
                            Double.class), "the stock does not change"));

            liquibase.rollback(ledger, "");

            assertAll(
                    () -> assertEquals(earlier, db.queryForList("SELECT id || ' ' || quantity FROM part_transaction "
                            + "ORDER BY id", String.class), "the movements of before, as they were"),
                    () -> assertEquals(0, db.queryForObject("SELECT count(*) FROM information_schema.columns "
                            + "WHERE table_name = 'part_transaction' AND column_name IN ('origin', "
                            + "'purchase_order_id', 'is_demo')", Integer.class)),
                    () -> assertEquals(List.of("work_order_id fkj99sa2uououd6s977aqyc0yri c false"), keys(db)));
        } finally {
            jdbc.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
        }
    }

    /**
     * Data as the application wrote it before P-16b (consumption positive, restock negative), without the rows they
     * refer to except the company and the work order, whose keys the migration checks.
     */
    private void seedEarlierData(JdbcTemplate db) {
        db.execute("SET session_replication_role = replica");
        db.update("INSERT INTO company (id, created_at, updated_at, employees_count) VALUES (?, now(), now(), 0)",
                COMPANY);
        db.update("INSERT INTO work_order (id, created_at, updated_at, estimated_duration, required_signature, title, "
                + "archived, company_id, is_demo) VALUES (500, now(), now(), 0, false, 'P-16b WO', false, ?, false)",
                COMPANY);
        // 1: consumption 3 and restock 10, stock 7 (no difference) · 2: stock 5 without movement · 3: a consumption
        // edited to 0 and another of 1, stock 4 · 4: nonStock with stock 2 · 5: demo with stock 10 · 6: nothing
        insertPart(db, 1, 7, false, false);
        insertPart(db, 2, 5, false, false);
        insertPart(db, 3, 4, false, false);
        insertPart(db, 4, 2, true, false);
        insertPart(db, 5, 10, false, true);
        insertPart(db, 6, 0, false, false);
        insertMovement(db, 10, 1, 3, 500L);
        insertMovement(db, 11, 1, -10, null);
        insertMovement(db, 30, 3, 0, 500L);
        insertMovement(db, 31, 3, 1, 500L);
        db.execute("SET session_replication_role = DEFAULT");
        db.execute("SELECT setval('part_transaction_seq', 1000)");
    }

    private void insertPart(JdbcTemplate db, long id, double quantity, boolean nonStock, boolean demo) {
        db.update("INSERT INTO part (id, created_at, updated_at, cost, min_quantity, name, non_stock, quantity, "
                + "company_id, is_demo) VALUES (?, now(), now(), 0, 0, ?, ?, ?, ?, ?)", id, "P-16b part " + id,
                nonStock, quantity, COMPANY, demo);
    }

    private void insertMovement(JdbcTemplate db, long id, long part, double quantity, Long workOrder) {
        db.update("INSERT INTO part_transaction (id, created_at, updated_at, created_by, quantity, company_id, "
                + "part_id, work_order_id) VALUES (?, now(), now(), 7, ?, ?, ?, ?)", id, quantity, COMPANY, part,
                workOrder);
    }

    private List<String> keys(JdbcTemplate db) {
        return db.queryForList("""
                SELECT a.attname || ' ' || c.conname || ' ' || c.confdeltype::text || ' '
                       || (EXISTS (SELECT 1 FROM pg_index i WHERE i.indrelid = c.conrelid
                           AND i.indkey[0] = c.conkey[1] AND i.indisvalid AND i.indpred IS NULL))::text
                FROM pg_constraint c
                JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = c.conkey[1]
                WHERE c.contype = 'f' AND c.conrelid = 'part_transaction'::regclass
                  AND a.attname IN ('work_order_id', 'purchase_order_id')
                ORDER BY 1""", String.class);
    }
}
