package com.grash.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.grash.integration.support.Concurrently;
import com.grash.integration.support.Concurrently.Outcome;
import com.grash.integration.support.TestApi;
import com.grash.integration.support.TestApi.Response;
import com.grash.integration.support.TestOrganizations;
import com.grash.integration.support.TestOrganizations.CommonData;
import com.grash.job.WorkOrderCreationJob;
import com.grash.model.Company;
import com.grash.model.PreventiveMaintenance;
import com.grash.repository.PreventiveMaintenanceRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
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
    private final TestApi api;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final PreventiveMaintenanceRepository preventiveMaintenanceRepository;
    private final WorkOrderCreationJob workOrderCreationJob;
    private final List<CommonData> created = new ArrayList<>();

    @Autowired
    FolioIntegrationTest(TestOrganizations organizations, MockMvc mockMvc, ObjectMapper objectMapper,
                         JdbcTemplate jdbcTemplate, PreventiveMaintenanceRepository preventiveMaintenanceRepository,
                         WorkOrderCreationJob workOrderCreationJob) {
        this.organizations = organizations;
        this.api = new TestApi(mockMvc, objectMapper);
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.preventiveMaintenanceRepository = preventiveMaintenanceRepository;
        this.workOrderCreationJob = workOrderCreationJob;
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

    private static JobExecutionContext jobContext(long scheduleId) {
        JobDataMap jobDataMap = new JobDataMap();
        jobDataMap.put("scheduleId", scheduleId);
        JobExecutionContext context = mock(JobExecutionContext.class);
        when(context.getMergedJobDataMap()).thenReturn(jobDataMap);
        return context;
    }
}
