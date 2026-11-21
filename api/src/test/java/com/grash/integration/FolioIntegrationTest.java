package com.grash.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.grash.integration.support.Concurrently;
import com.grash.integration.support.Concurrently.Outcome;
import com.grash.integration.support.TestApi;
import com.grash.integration.support.TestApi.Response;
import com.grash.integration.support.TestOrganizations;
import com.grash.integration.support.TestOrganizations.CommonData;
import com.grash.integration.support.TestRecords;
import com.grash.job.WorkOrderCreationJob;
import com.grash.model.Company;
import com.grash.model.PreventiveMaintenance;
import com.grash.repository.PreventiveMaintenanceRepository;
import com.grash.service.CustomSequenceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * P-20 (C-08): the folio of work orders, assets, locations, requests and preventive maintenances is unique per
 * company, whatever the number of simultaneous creations and the route that creates them.
 * <p>
 * The concurrency tests start every task from one gate ({@link Concurrently}) and check that the tasks really ran
 * at the same time: the last one started before the first one finished.
 */
@AutoConfigureMockMvc
class FolioIntegrationTest extends AbstractIntegrationTest {

    private static final int SIMULTANEOUS = 20;
    private static final int ROUNDS = 3;

    /**
     * The six unique constraints of P-20: name, then table and columns.
     */
    private static final Map<String, String> CONSTRAINTS = new LinkedHashMap<>();

    static {
        CONSTRAINTS.put("uq_custom_sequence_company", "custom_sequence(company_id)");
        CONSTRAINTS.put("uq_work_order_company_custom_id", "work_order(company_id,custom_id)");
        CONSTRAINTS.put("uq_asset_company_custom_id", "asset(company_id,custom_id)");
        CONSTRAINTS.put("uq_location_company_custom_id", "location(company_id,custom_id)");
        CONSTRAINTS.put("uq_request_company_custom_id", "request(company_id,custom_id)");
        CONSTRAINTS.put("uq_preventive_maintenance_company_custom_id", "preventive_maintenance(company_id,custom_id)");
    }

    /**
     * The data corrections of the P-20 changesets, in the order Liquibase runs them.
     */
    private static final List<String> CORRECTIONS = List.of(
            "db/changelog/sql/2026_11_21_1795285030_merge_counter_rows.sql",
            "db/changelog/sql/2026_11_21_1795285030_renumber_repeated_folios.sql");

    /**
     * Each folio type: its table, its counter column and how to create one record through the API.
     */
    private enum Type {
        WORK_ORDER("work_order", "work_order_sequence", "/work-orders"),
        ASSET("asset", "asset_sequence", "/assets"),
        LOCATION("location", "location_sequence", "/locations"),
        REQUEST("request", "request_sequence", "/requests"),
        PREVENTIVE_MAINTENANCE("preventive_maintenance", "preventive_maintenance_sequence",
                "/preventive-maintenances");

        final String table;
        final String counter;
        final String path;

        Type(String table, String counter, String path) {
            this.table = table;
            this.counter = counter;
            this.path = path;
        }
    }

    /**
     * The response of a task and when it ran.
     */
    private record Timed<T>(T value, long startedAt, long finishedAt) {
    }

    private final TestOrganizations organizations;
    private final TestRecords records;
    private final TestApi api;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final PreventiveMaintenanceRepository preventiveMaintenanceRepository;
    private final WorkOrderCreationJob workOrderCreationJob;
    private final CustomSequenceService customSequenceService;
    private final TransactionTemplate transactionTemplate;
    private final List<CommonData> created = new ArrayList<>();

    @Autowired
    FolioIntegrationTest(TestOrganizations organizations, TestRecords records, MockMvc mockMvc,
                         ObjectMapper objectMapper, JdbcTemplate jdbcTemplate,
                         PreventiveMaintenanceRepository preventiveMaintenanceRepository,
                         WorkOrderCreationJob workOrderCreationJob, CustomSequenceService customSequenceService,
                         TransactionTemplate transactionTemplate) {
        this.organizations = organizations;
        this.records = records;
        this.api = new TestApi(mockMvc, objectMapper);
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.preventiveMaintenanceRepository = preventiveMaintenanceRepository;
        this.workOrderCreationJob = workOrderCreationJob;
        this.customSequenceService = customSequenceService;
        this.transactionTemplate = transactionTemplate;
    }

    @AfterEach
    void cleanUp() {
        created.forEach(organizations::delete);
        created.clear();
    }

    private CommonData newOrganizations() {
        CommonData data = organizations.create();
        created.add(data);
        return data;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------------------

    private String body(Type type, String label) {
        ObjectNode body = objectMapper.createObjectNode();
        switch (type) {
            case WORK_ORDER, REQUEST -> body.put("title", label);
            case ASSET, LOCATION -> body.put("name", label);
            case PREVENTIVE_MAINTENANCE -> body.put("name", label).put("title", label).put("frequency", 1)
                    .put("recurrenceType", "DAILY").put("recurrenceBasedOn", "SCHEDULED_DATE")
                    // far in the future: the scheduler must not fire it during the test
                    .put("startsOn", "2099-01-01T00:00:00.000+00:00");
        }
        return body.toString();
    }

    private Response create(Type type, String label, String token) {
        return api.call(post(type.path).contentType(MediaType.APPLICATION_JSON).content(body(type, label)), token);
    }

    private static <T> Callable<Timed<T>> timed(Callable<T> task) {
        return () -> {
            long startedAt = System.nanoTime();
            T value = task.call();
            return new Timed<>(value, startedAt, System.nanoTime());
        };
    }

    /**
     * Fails unless every task succeeded and the tasks overlapped: the last start came before the first end.
     */
    private static <T> List<T> valuesOfOverlapping(List<Outcome<Timed<T>>> outcomes) {
        List<String> errors = outcomes.stream().filter(outcome -> !outcome.succeeded())
                .map(outcome -> String.valueOf(outcome.error())).toList();
        assertTrue(errors.isEmpty(), "tasks failed: " + errors);
        long lastStart = outcomes.stream().mapToLong(outcome -> outcome.value().startedAt()).max().orElseThrow();
        long firstEnd = outcomes.stream().mapToLong(outcome -> outcome.value().finishedAt()).min().orElseThrow();
        assertTrue(lastStart < firstEnd, "the tasks did not run at the same time");
        return outcomes.stream().map(outcome -> outcome.value().value()).toList();
    }

    private List<String> folios(Type type, Company company) {
        return jdbcTemplate.queryForList("SELECT custom_id FROM " + type.table + " WHERE company_id = ?",
                String.class, company.getId());
    }

    private static List<String> repeated(List<String> folios) {
        Map<String, Integer> counts = new HashMap<>();
        folios.forEach(folio -> counts.merge(folio, 1, Integer::sum));
        return counts.entrySet().stream().filter(entry -> entry.getValue() > 1).map(Map.Entry::getKey).sorted()
                .toList();
    }

    private long counter(Type type, Company company) {
        return jdbcTemplate.queryForObject("SELECT " + type.counter + " FROM custom_sequence WHERE company_id = ?",
                Long.class, company.getId());
    }

    private long counterRows(Company company) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM custom_sequence WHERE company_id = ?", Long.class,
                company.getId());
    }

    private static void assertAll2xx(List<Response> responses) {
        List<Response> failed = responses.stream().filter(response -> !response.is2xx()).toList();
        assertTrue(failed.isEmpty(), failed.size() + " of " + responses.size() + " creations failed: " + failed);
    }

    // ---------------------------------------------------------------------------------------------------------
    // CA-P20-01 to 04: concurrency
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("CA-P20-01: 20 simultaneous work orders through the API get 20 different folios")
    void simultaneousWorkOrders_getDifferentFolios() {
        CommonData data = newOrganizations();
        Company company = data.o1().company();
        String token = api.login(data.o1().admin());
        // the counter row exists: this test is about the increment, CA-P20-03 about the first use
        assertTrue(create(Type.WORK_ORDER, "P-20 first WO", token).is2xx());

        for (int round = 0; round < ROUNDS; round++) {
            int currentRound = round;
            List<Response> responses = valuesOfOverlapping(Concurrently.run(SIMULTANEOUS, i -> timed(
                    () -> create(Type.WORK_ORDER, "P-20 WO " + currentRound + "-" + i, token))));

            assertAll2xx(responses);
            List<String> folios = folios(Type.WORK_ORDER, company);
            assertEquals(List.of(), repeated(folios), "repeated folios after round " + round);
            assertEquals(1 + (round + 1) * SIMULTANEOUS, folios.size());
        }
    }

    @Test
    @DisplayName("CA-P20-02: mixed simultaneous creations of the five types repeat no folio and no counter goes back")
    void simultaneousMixedCreations_noRepeatedFolioAndNoCounterGoesBack() {
        CommonData data = newOrganizations();
        Company company = data.o1().company();
        String token = api.login(data.o1().admin());
        assertTrue(create(Type.WORK_ORDER, "P-20 first WO", token).is2xx());
        Type[] types = Type.values();

        for (int round = 0; round < ROUNDS; round++) {
            Map<Type, Long> before = new HashMap<>();
            for (Type type : types) before.put(type, counter(type, company));
            int currentRound = round;
            List<Response> responses = valuesOfOverlapping(Concurrently.run(SIMULTANEOUS, i -> timed(
                    () -> create(types[i % types.length], "P-20 mixed " + currentRound + "-" + i, token))));

            assertAll2xx(responses);
            int perType = SIMULTANEOUS / types.length;
            for (Type type : types) {
                assertEquals(List.of(), repeated(folios(type, company)), "repeated " + type + " folios, round "
                        + round);
                assertTrue(counter(type, company) >= before.get(type) + perType, type + " counter went back: "
                        + before.get(type) + " + " + perType + " created -> " + counter(type, company));
            }
        }
    }

    @Test
    @DisplayName("CA-P20-03: two simultaneous first creations in a new company both succeed and leave one counter row")
    void simultaneousFirstCreations_oneCounterRow() {
        for (int repetition = 0; repetition < 5; repetition++) {
            CommonData data = newOrganizations();
            Company company = data.o1().company();
            // Two people (C-08 scenario d). One user in two threads would also hit C-11 (its cached entity is
            // shared between threads) before the folio is taken.
            List<String> tokens = List.of(api.login(data.o1().owner()), api.login(data.o1().admin()));
            String token = tokens.get(1);
            assertEquals(0, counterRows(company), "a new company has no counter row yet");
            int currentRepetition = repetition;

            List<Response> responses = valuesOfOverlapping(Concurrently.run(2, i -> timed(
                    () -> create(Type.WORK_ORDER, "P-20 first " + currentRepetition + "-" + i, tokens.get(i)))));

            assertAll2xx(responses);
            assertEquals(1, counterRows(company), "counter rows, repetition " + repetition);
            assertEquals(List.of(), repeated(folios(Type.WORK_ORDER, company)));
            assertTrue(create(Type.ASSET, "P-20 next", token).is2xx(), "a later creation still works");
        }
    }

    @Test
    @DisplayName("CA-P20-04: preventive maintenances due at once generate their work orders, by the scheduler route, "
            + "with different folios")
    void simultaneousScheduledWorkOrders_getDifferentFolios() {
        CommonData data = newOrganizations();
        Company company = data.o1().company();
        assertTrue(create(Type.WORK_ORDER, "P-20 first WO", api.login(data.o1().admin())).is2xx());
        SecurityContextHolder.clearContext();
        List<JobExecutionContext> contexts = new ArrayList<>();
        for (int i = 0; i < SIMULTANEOUS; i++) {
            PreventiveMaintenance preventiveMaintenance = new PreventiveMaintenance();
            preventiveMaintenance.setName("P-20 PM " + i);
            preventiveMaintenance.setTitle("P-20 PM " + i);
            preventiveMaintenance.setCompany(company);
            contexts.add(jobContext(preventiveMaintenanceRepository.saveAndFlush(preventiveMaintenance).getSchedule()
                    .getId()));
        }

        for (int round = 0; round < ROUNDS; round++) {
            valuesOfOverlapping(Concurrently.run(SIMULTANEOUS, i -> timed(() -> {
                workOrderCreationJob.executeInternal(contexts.get(i));
                return true;
            })));

            List<String> folios = folios(Type.WORK_ORDER, company);
            assertEquals(List.of(), repeated(folios), "repeated folios after round " + round);
            assertEquals(1 + (round + 1) * SIMULTANEOUS, folios.size());
        }
    }

    @Test
    @DisplayName("CA-P20-09: the folio is unique per company: the first work order of two companies has the same folio")
    void twoCompanies_sameFirstFolio() {
        CommonData data = newOrganizations();

        assertTrue(create(Type.WORK_ORDER, "P-20 O1 WO", api.login(data.o1().admin())).is2xx());
        assertTrue(create(Type.WORK_ORDER, "P-20 O2 WO", api.login(data.o2().admin())).is2xx());

        assertEquals(List.of("WO000001"), folios(Type.WORK_ORDER, data.o1().company()));
        assertEquals(List.of("WO000001"), folios(Type.WORK_ORDER, data.o2().company()));
    }

    // ---------------------------------------------------------------------------------------------------------
    // CA-P20-05 and 06: what the database guarantees, and the correction of existing data
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("CA-P20-05: the migrated database has the six unique constraints")
    void migratedDatabase_hasTheSixUniqueConstraints() {
        Map<String, String> found = new HashMap<>();
        jdbcTemplate.query("""
                        SELECT c.conname, c.conrelid::regclass::text || '(' ||
                               string_agg(a.attname, ',' ORDER BY k.position) || ')' AS definition
                        FROM pg_constraint c
                                 CROSS JOIN LATERAL unnest(c.conkey) WITH ORDINALITY AS k(attnum, position)
                                 JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.attnum
                        WHERE c.contype = 'u' AND c.conname = ANY (?)
                        GROUP BY c.conname, c.conrelid""",
                row -> {
                    found.put(row.getString(1), row.getString(2));
                }, (Object) CONSTRAINTS.keySet().toArray(String[]::new));

        assertEquals(new HashMap<>(CONSTRAINTS), found);
    }

    @Test
    @DisplayName("CA-P20-06: the correction merges repeated counter rows and gives new folios to the newer records of "
            + "a repeated folio, without deleting any record")
    void correction_mergesCounterRowsAndRenumbersRepeatedFolios() {
        CommonData data = newOrganizations();
        Company company = data.o1().company();
        String token = api.login(data.o1().admin());
        for (int i = 0; i < 4; i++) assertTrue(create(Type.WORK_ORDER, "P-20 WO " + i, token).is2xx());
        for (int i = 0; i < 3; i++) assertTrue(create(Type.ASSET, "P-20 asset " + i, token).is2xx());
        records.asset(company);
        records.asset(company);
        long wo1 = idOf(Type.WORK_ORDER, company, "WO000001");
        long wo2 = idOf(Type.WORK_ORDER, company, "WO000002");
        long wo3 = idOf(Type.WORK_ORDER, company, "WO000003");
        long wo4 = idOf(Type.WORK_ORDER, company, "WO000004");
        long a1 = idOf(Type.ASSET, company, "A000001");
        long a2 = idOf(Type.ASSET, company, "A000002");
        long a3 = idOf(Type.ASSET, company, "A000003");

        // DDL is transactional in PostgreSQL: everything below, constraints included, is rolled back
        transactionTemplate.executeWithoutResult(status -> {
            status.setRollbackOnly();
            jdbcTemplate.execute("SET LOCAL lock_timeout = '30s'");
            CONSTRAINTS.forEach((name, definition) -> jdbcTemplate.execute("ALTER TABLE " + table(definition)
                    + " DROP CONSTRAINT " + name));
            // a second counter row, with a lower work order counter and a higher asset counter
            jdbcTemplate.update("INSERT INTO custom_sequence (id, company_id, work_order_sequence, asset_sequence) "
                    + "VALUES (nextval('custom_sequence_seq'), ?, 2, 9)", company.getId());
            // WO000002 and WO000003 repeat WO000001; the record of WO000003 is the oldest
            jdbcTemplate.update("UPDATE work_order SET custom_id = 'WO000001' WHERE id IN (?, ?)", wo2, wo3);
            jdbcTemplate.update("UPDATE work_order SET created_at = '2020-01-01' WHERE id = ?", wo3);
            // A000002 repeats A000001; A000003 becomes a hand-written folio in the product format
            jdbcTemplate.update("UPDATE asset SET custom_id = 'A000001' WHERE id = ?", a2);
            jdbcTemplate.update("UPDATE asset SET custom_id = 'A000050' WHERE id = ?", a3);
            assertEquals(2, counterRows(company));
            int workOrders = folios(Type.WORK_ORDER, company).size();
            int assets = folios(Type.ASSET, company).size();

            CORRECTIONS.forEach(path -> jdbcTemplate.execute(sql(path)));
            // fails if any company of the database still has a repeated folio or counter row
            CONSTRAINTS.forEach((name, definition) -> jdbcTemplate.execute("ALTER TABLE " + table(definition)
                    + " ADD CONSTRAINT " + name + " UNIQUE " + columns(definition)));

            assertAll(
                    () -> assertEquals(1, counterRows(company)),
                    () -> assertEquals(workOrders, folios(Type.WORK_ORDER, company).size(), "no work order lost"),
                    () -> assertEquals(assets, folios(Type.ASSET, company).size(), "no asset lost"),
                    () -> assertEquals("WO000001", folioOf(Type.WORK_ORDER, wo3), "the oldest keeps the folio"),
                    () -> assertEquals("WO000005", folioOf(Type.WORK_ORDER, wo1)),
                    () -> assertEquals("WO000006", folioOf(Type.WORK_ORDER, wo2)),
                    () -> assertEquals("WO000004", folioOf(Type.WORK_ORDER, wo4), "a folio not repeated is kept"),
                    () -> assertEquals(7, counter(Type.WORK_ORDER, company)),
                    () -> assertEquals("A000001", folioOf(Type.ASSET, a1), "the oldest keeps the folio"),
                    () -> assertEquals("A000051", folioOf(Type.ASSET, a2), "above the highest folio"),
                    () -> assertEquals("A000050", folioOf(Type.ASSET, a3), "a folio not repeated is kept"),
                    () -> assertEquals(52, counter(Type.ASSET, company)),
                    () -> assertEquals(2, folios(Type.ASSET, company).stream().filter(folio -> folio == null)
                            .count(), "null folios stay null"));
            String nextWorkOrder = String.format("WO%06d", customSequenceService.getNextWorkOrderSequence(company));
            String nextAsset = String.format("A%06d", customSequenceService.getNextAssetSequence(company));
            assertFalse(folios(Type.WORK_ORDER, company).contains(nextWorkOrder), nextWorkOrder + " exists");
            assertFalse(folios(Type.ASSET, company).contains(nextAsset), nextAsset + " exists");
        });
    }

    private long idOf(Type type, Company company, String folio) {
        return jdbcTemplate.queryForObject("SELECT id FROM " + type.table + " WHERE company_id = ? AND custom_id = ?",
                Long.class, company.getId(), folio);
    }

    private String folioOf(Type type, long id) {
        return jdbcTemplate.queryForObject("SELECT custom_id FROM " + type.table + " WHERE id = ?", String.class, id);
    }

    private static String table(String definition) {
        return definition.substring(0, definition.indexOf('('));
    }

    private static String columns(String definition) {
        return definition.substring(definition.indexOf('('));
    }

    private static String sql(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // CA-P20-07 and 08: the asset folio written by hand
    // ---------------------------------------------------------------------------------------------------------

    private static final String ASSET_FOLIO_TAKEN = "ASSET_FOLIO_TAKEN";
    /**
     * Fragments that reveal SQL, constraints or stack traces; none may appear in a rejection.
     */
    private static final List<String> INTERNALS = List.of("sql", "constraint", "uq_", "violates", "duplicate key",
            "custom_id", "exception", "at com.");

    private long assetId(Response created) {
        try {
            return objectMapper.readTree(created.body()).get("id").asLong();
        } catch (Exception e) {
            throw new AssertionError("not JSON: " + created, e);
        }
    }

    private Response patchAsset(long id, String name, String folio, String token) {
        ObjectNode body = objectMapper.createObjectNode().put("name", name).put("status", "OPERATIONAL")
                .put("customId", folio);
        return api.call(patch("/assets/" + id).contentType(MediaType.APPLICATION_JSON).content(body.toString()),
                token);
    }

    private Map<String, Object> assetRow(long id) {
        return jdbcTemplate.queryForMap("SELECT name, custom_id, status FROM asset WHERE id = ?", id);
    }

    private void assertFolioTaken(Response response) {
        String lowerBody = response.body().toLowerCase(java.util.Locale.ROOT);
        assertAll(
                () -> assertEquals(409, response.status(), response.toString()),
                () -> assertTrue(response.body().contains("\"code\":\"" + ASSET_FOLIO_TAKEN + "\""), response.body()),
                () -> assertTrue(response.body().contains("\"success\":false"), response.body()),
                () -> INTERNALS.forEach(fragment -> assertFalse(lowerBody.contains(fragment),
                        "the body reveals '" + fragment + "': " + response)));
    }

    @Test
    @DisplayName("CA-P20-07: giving an asset the folio of another asset answers 409 ASSET_FOLIO_TAKEN and changes nothing")
    void assetPatch_folioOfAnotherAsset_conflict() {
        CommonData data = newOrganizations();
        Company company = data.o1().company();
        String token = api.login(data.o1().admin());
        assertTrue(create(Type.ASSET, "P-20 asset 1", token).is2xx());
        long second = assetId(create(Type.ASSET, "P-20 asset 2", token));
        Map<String, Object> before = assetRow(second);

        Response response = patchAsset(second, "P-20 renamed", "A000001", token);

        assertFolioTaken(response);
        assertEquals(before, assetRow(second), "nothing changed");
        assertEquals(List.of(), repeated(folios(Type.ASSET, company)));
    }

    @Test
    @DisplayName("CA-P20-07: two simultaneous edits that give the same new folio to two assets: one succeeds, the other "
            + "answers 409, never 500")
    void simultaneousAssetPatches_sameNewFolio_oneConflict() {
        CommonData data = newOrganizations();
        Company company = data.o1().company();
        // two people: one user in two threads would also hit C-11
        List<String> tokens = List.of(api.login(data.o1().owner()), api.login(data.o1().admin()));
        for (int round = 0; round < ROUNDS; round++) {
            List<Long> assets = List.of(assetId(create(Type.ASSET, "P-20 race a " + round, tokens.get(0))),
                    assetId(create(Type.ASSET, "P-20 race b " + round, tokens.get(0))));
            String folio = "P-20 HAND " + round;

            List<Response> responses = valuesOfOverlapping(Concurrently.run(2, i -> timed(
                    () -> patchAsset(assets.get(i), "P-20 race " + i, folio, tokens.get(i)))));

            assertEquals(1, responses.stream().filter(Response::is2xx).count(), responses.toString());
            assertFolioTaken(responses.stream().filter(response -> !response.is2xx()).findFirst().orElseThrow());
            assertEquals(List.of(), repeated(folios(Type.ASSET, company)));
        }
    }

    @Test
    @DisplayName("CA-P20-08: a new asset never takes a folio written by hand on another asset")
    void assetCreation_skipsFolioWrittenByHand() {
        CommonData data = newOrganizations();
        Company company = data.o1().company();
        String token = api.login(data.o1().admin());
        long first = assetId(create(Type.ASSET, "P-20 asset 1", token));
        // the counter would hand out A000002 next
        assertTrue(patchAsset(first, "P-20 asset 1", "A000002", token).is2xx());

        Response created = create(Type.ASSET, "P-20 asset 2", token);

        assertTrue(created.is2xx(), created.toString());
        assertEquals("A000003", assetRow(assetId(created)).get("custom_id"));
        assertEquals(List.of(), repeated(folios(Type.ASSET, company)));
    }

    private static JobExecutionContext jobContext(long scheduleId) {
        JobDataMap jobDataMap = new JobDataMap();
        jobDataMap.put("scheduleId", scheduleId);
        JobExecutionContext context = mock(JobExecutionContext.class);
        when(context.getMergedJobDataMap()).thenReturn(jobDataMap);
        return context;
    }
}
