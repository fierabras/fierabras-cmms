package com.grash.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grash.integration.support.TestApi;
import com.grash.integration.support.TestApi.Response;
import com.grash.integration.support.TestOrganizations;
import com.grash.integration.support.TestOrganizations.CommonData;
import com.grash.integration.support.TestRecords;
import com.grash.model.Asset;
import com.grash.model.Company;
import com.grash.model.WorkOrder;
import com.grash.model.enums.Status;
import com.grash.policy.PolicyCatalog;
import com.grash.policy.PolicyCatalog.CatalogKey;
import com.grash.policy.PolicyExtract;
import com.grash.policy.PolicyExtract.Rule;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;

/**
 * P-19 (E-5, E-6, E-7, E-9; PPB-01, 03, 04, 21, 22): the catalog of the database migrated by Liquibase agrees with
 * the versioned extract of the policy: every table and foreign key has its row, every key has its target rule and
 * leads an index. Changesets of the tests, applied to a database created for them, show that each check fails and
 * names the key. The database itself rejects deleting what a historical record cites.
 */
@AutoConfigureMockMvc
class SchemaPolicyIntegrationTest extends AbstractIntegrationTest {

    private static final String FOREIGN_KEY_VIOLATION = "23503";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;
    private final TestOrganizations organizations;
    private final TestRecords records;
    private final TestApi api;
    private final PolicyExtract extract = PolicyExtract.load();

    @Autowired
    SchemaPolicyIntegrationTest(JdbcTemplate jdbc, TransactionTemplate transactionTemplate,
                                TestOrganizations organizations, TestRecords records, MockMvc mockMvc,
                                ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.transactionTemplate = transactionTemplate;
        this.organizations = organizations;
        this.records = records;
        this.api = new TestApi(mockMvc, objectMapper);
    }

    @Nested
    class TheCatalog {

        @Test
        void ca001_everyTableAndForeignKey_hasExactlyOneRow() {
            assertEquals(List.of(), new PolicyCatalog(extract, jdbc).coverage());
        }

        @Test
        void ca011_ca012_ca058_everyForeignKey_hasItsTargetRule_andNoCascadeReference() {
            assertEquals(List.of(), new PolicyCatalog(extract, jdbc).rules());
        }

        @Test
        void ca060_everyForeignKey_leadsAValidNonPartialIndex() {
            assertEquals(List.of(), new PolicyCatalog(extract, jdbc).indexes());
        }

        @Test
        void d3_revisionAsset_foreignKeyNoAction_indexed_andValidatedInANewDatabase() {
            // D-3, D-P19-2 (c): added NOT VALID, then validated because a new database has no orphan
            CatalogKey key = new PolicyCatalog(extract, jdbc).byName().get("work_order_aud.asset_id");
            assertAll(
                    () -> assertEquals("asset", key.parentTable()),
                    () -> assertEquals(Rule.NO_ACTION, key.rule()),
                    () -> assertTrue(key.indexed()),
                    () -> assertTrue(key.validated()));
        }

        @Test
        void dP19_4_theIndexNamesCutTo63_existOncePerSchema_onTheirColumn() {
            Map<String, String> cut = Map.of(
                    "idx_preventive_maintenance_assigned_to_preventive_maintenance_i",
                    "preventive_maintenance_assigned_to.preventive_maintenance_id",
                    "idx_webhook_endpoint_work_order_categories_work_order_categorie",
                    "webhook_endpoint_work_order_categories.work_order_categories_id",
                    "idx_work_order_meter_trigger_assigned_to_work_order_meter_trigg",
                    "work_order_meter_trigger_assigned_to.work_order_meter_trigger_id",
                    "idx_work_order_meter_trigger_customers_work_order_meter_trigger",
                    "work_order_meter_trigger_customers.work_order_meter_trigger_id");
            cut.forEach((index, column) -> assertEquals(List.of(column), jdbc.queryForList(
                    "SELECT t.relname || '.' || a.attname FROM pg_class c JOIN pg_index i ON i.indexrelid = c.oid "
                            + "JOIN pg_class t ON t.oid = i.indrelid "
                            + "JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = i.indkey[0] "
                            + "WHERE c.relname = ? AND c.relnamespace = current_schema()::regnamespace",
                    String.class, index), index));
        }

        @Test
        void tdP20_04_theRedundantIndexOfTheSequences_isRetired_theUniqueOneStays() {
            assertEquals(List.of("uq_custom_sequence_company"), jdbc.queryForList("SELECT indexname FROM pg_indexes "
                    + "WHERE schemaname = current_schema() AND tablename = 'custom_sequence' "
                    + "AND indexdef LIKE '%(company_id)%' ORDER BY 1", String.class));
        }
    }

    /**
     * CA-PPB-002, 058 (R), 061: each check fails and names its key when a changeset of the test adds a table and a
     * key without row, puts {@code part.category_id} in CASCADE or adds a key without index; without them it passes.
     */
    @Nested
    class TestChangesets {

        private static final String DATABASE = "p19_schema";

        @Test
        void eachCheck_failsAndNamesTheKey_andPassesWithoutTheTestChangesets() throws Exception {
            PostgreSQLContainer<?> container = getPostgresqlContainer();
            jdbc.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
            jdbc.execute("CREATE DATABASE " + DATABASE);
            String url = container.getJdbcUrl().replace("/" + container.getDatabaseName(), "/" + DATABASE);
            try (Connection connection = DriverManager.getConnection(url, container.getUsername(),
                    container.getPassword())) {
                JdbcTemplate db = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
                Database database = DatabaseFactory.getInstance()
                        .findCorrectDatabaseImplementation(new JdbcConnection(connection));
                new Liquibase("db/master.xml", new ClassLoaderResourceAccessor(), database).update("");

                PolicyCatalog without = new PolicyCatalog(extract, db);
                assertAll(
                        () -> assertEquals(List.of(), without.coverage()),
                        () -> assertEquals(List.of(), without.rules()),
                        () -> assertEquals(List.of(), without.indexes()));

                new Liquibase("db/p19/test-changesets.xml", new ClassLoaderResourceAccessor(), database).update("");
                if (!connection.getAutoCommit()) connection.commit();

                PolicyCatalog with = new PolicyCatalog(extract, db);
                assertAll(
                        () -> assertEquals(List.of("table p19_unindexed has no row in the policy",
                                "table p19_unlisted has no row in the policy",
                                "foreign key p19_unindexed.part_id -> part has no row in the policy",
                                "foreign key p19_unlisted.asset_id -> asset has no row in the policy"),
                                with.coverage()),
                        () -> assertEquals(List.of(
                                "foreign key part.category_id -> part_category is CASCADE, its target rule is "
                                        + "NO ACTION",
                                "foreign key part.category_id -> part_category is CASCADE and is a reference "
                                        + "(PPB-03)"), with.rules()),
                        () -> assertEquals(List.of("foreign key p19_unindexed.part_id -> part has no index on "
                                + "part_id"), with.indexes()));
            } finally {
                jdbc.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
            }
        }
    }

    /**
     * E-9 (CA-PPB-016, 125; PPB-04): deleting in the database what a historical record cites fails with 23503 and
     * the record is kept.
     */
    @Nested
    class DatabaseGuards {

        @Test
        void ca016_workOrderWithALedgerMovement_deleteInTheDatabase_23503_movementKept() {
            CommonData data = organizations.create();
            try {
                Company company = data.o1().company();
                Long part = records.part(company, data.o1().admin()).getId();
                Long workOrder = records.workOrder(company, null, Status.OPEN, false).getId();
                jdbc.update("INSERT INTO part_transaction (id, created_at, updated_at, company_id, part_id, quantity, "
                        + "origin, work_order_id) VALUES (nextval('part_transaction_seq'), now(), now(), ?, ?, -1, "
                        + "'CONSUMPTION', ?)", company.getId(), part, workOrder);

                assertEquals(FOREIGN_KEY_VIOLATION, sqlState("DELETE FROM work_order WHERE id = ?", workOrder));
                assertEquals(1, count("SELECT count(*) FROM part_transaction WHERE work_order_id = ?", workOrder));
            } finally {
                organizations.delete(data);
            }
        }

        @Test
        void ca125_teamCitedOnlyByAnEarlierRevision_deleteInTheDatabase_23503_revisionKeepsIt() {
            CommonData data = organizations.create();
            try {
                Company company = data.o1().company();
                Long team = jdbc.queryForObject("INSERT INTO team (id, created_at, updated_at, name, company_id) "
                        + "VALUES (nextval('team_seq'), now(), now(), 'P-19 E3', ?) RETURNING id", Long.class,
                        company.getId());
                Long workOrder = records.workOrder(company, null, Status.OPEN, false).getId();
                jdbc.update("UPDATE work_order_aud SET team_id = ? WHERE id = ?", team, workOrder);
                assertEquals(0, count("SELECT count(*) FROM work_order WHERE team_id = ?", team), "only the revision");

                assertEquals(FOREIGN_KEY_VIOLATION, sqlState("DELETE FROM team WHERE id = ?", team));
                assertEquals(1, count("SELECT count(*) FROM work_order_aud WHERE id = ? AND team_id = ?", workOrder,
                        team));
            } finally {
                organizations.delete(data);
            }
        }
    }

    /**
     * D-3, approved by the responsible person: a revision of a work order that is not demo cites a demo asset; the
     * demo purge is rejected whole with {@code DEMO_DATA_IN_USE} and the asset is kept. Before P-19 the purge
     * deleted the asset and left the revision with a dangling id.
     */
    @Nested
    class DemoPurge {

        @Test
        void revisionOfARealWorkOrderCitesADemoAsset_409DemoDataInUse_assetKept() throws Exception {
            CommonData data = organizations.create();
            try {
                Company company = data.o1().company();
                Asset demoAsset = records.asset(company, null, true);
                WorkOrder real = records.workOrder(company, null, Status.OPEN, false);
                jdbc.update("UPDATE work_order_aud SET asset_id = ? WHERE id = ?", demoAsset.getId(), real.getId());

                Response response = api.call(delete("/demo/demo-data"), api.login(data.o1().admin()));

                assertEquals(409, response.status(), response.toString());
                JsonNode body = new ObjectMapper().readTree(response.body());
                List<String> types = new ArrayList<>();
                if (body.has("blockingTypes")) body.get("blockingTypes").forEach(type -> types.add(type.asText()));
                assertAll(
                        () -> assertEquals("DEMO_DATA_IN_USE", body.get("code").asText()),
                        () -> assertFalse(body.get("success").asBoolean()),
                        () -> assertEquals(List.of("WORK_ORDER_HISTORY"), types),
                        () -> assertEquals(1, count("SELECT count(*) FROM asset WHERE id = ?", demoAsset.getId())),
                        () -> assertEquals(1, count("SELECT count(*) FROM work_order_aud WHERE id = ? AND asset_id = ?",
                                real.getId(), demoAsset.getId())));
            } finally {
                organizations.delete(data);
            }
        }
    }

    private long count(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, Long.class, arguments);
    }

    /** The SQLSTATE of {@code sql} run in a transaction that is always rolled back; {@code null} if it passes. */
    private String sqlState(String sql, Object... arguments) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                status.setRollbackOnly();
                jdbc.update(sql, arguments);
            });
            return null;
        } catch (DataAccessException failure) {
            Throwable cause = failure;
            while (cause != null && !(cause instanceof SQLException)) cause = cause.getCause();
            return cause == null ? failure.toString() : ((SQLException) cause).getSQLState();
        }
    }
}
