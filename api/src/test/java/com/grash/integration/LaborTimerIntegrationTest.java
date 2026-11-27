package com.grash.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grash.integration.support.Concurrently;
import com.grash.integration.support.Concurrently.Outcome;
import com.grash.integration.support.FaultInjector;
import com.grash.integration.support.FaultInjector.Fault;
import com.grash.integration.support.FaultInjector.Operation;
import com.grash.integration.support.TestApi;
import com.grash.integration.support.TestApi.Response;
import com.grash.integration.support.TestOrganizations;
import com.grash.integration.support.TestOrganizations.CommonData;
import com.grash.integration.support.TestRecords;
import com.grash.integration.support.TestWorkOrders;
import com.grash.model.Company;
import com.grash.model.User;
import com.grash.model.WorkOrder;
import com.grash.repository.FileRepository;
import com.grash.repository.PartRepository;
import com.grash.repository.WorkOrderRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * P-21 (C-10): one labor timer per technician and work order, whatever the number of "start" or "stop" requests
 * and their timing; the unique index {@code uq_labor_timer} and the migration of the timers repeated before it.
 */
@AutoConfigureMockMvc
class LaborTimerIntegrationTest extends AbstractIntegrationTest {

    private static final long RATE = 120;
    private static final long HOUR = 3600;
    private static final long SLACK = 30;
    private static final String MIGRATION = "db/changelog/sql/2026_11_26_1795743983_merge_repeated_timers.sql";
    private static final String INDEX = "CREATE UNIQUE INDEX uq_labor_timer ON labor (work_order_id, assigned_to_id) "
            + "WHERE logged";

    private final TestOrganizations organizations;
    private final TestApi api;
    private final TestWorkOrders workOrders;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;
    private final FaultInjector faults;
    private final List<CommonData> created = new ArrayList<>();

    @Autowired
    LaborTimerIntegrationTest(TestOrganizations organizations, TestRecords records, MockMvc mockMvc,
                              ObjectMapper objectMapper, JdbcTemplate jdbc, TransactionTemplate transactionTemplate,
                              WorkOrderRepository workOrderRepository, PartRepository partRepository,
                              FileRepository fileRepository) {
        this.organizations = organizations;
        this.api = new TestApi(mockMvc, objectMapper);
        this.workOrders = new TestWorkOrders(api, objectMapper, records, workOrderRepository, partRepository,
                fileRepository);
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
        this.transactionTemplate = transactionTemplate;
        this.faults = new FaultInjector(jdbc);
    }

    @AfterEach
    void cleanUp() {
        faults.removeAll();
        created.forEach(organizations::delete);
        created.clear();
    }

    // =====================================================================================================
    // Start and stop through the API (the web and the mobile app use the same endpoint)
    // =====================================================================================================

    @Test
    @DisplayName("CA-P21-01: two starts in a row, one timer; the second answer is the same timer")
    void ca01_twoStartsInARow_oneTimer_theSecondAnswerIsTheSameTimer() {
        Context c = context();

        Response first = start(c);
        Response second = start(c);

        assertAll(
                () -> assertEquals(200, first.status(), first.toString()),
                () -> assertEquals(200, second.status(), second.toString()),
                () -> assertEquals(id(first), id(second)),
                () -> assertEquals("RUNNING", json(second).get("status").asText()),
                () -> assertEquals(1, timers(c)));
    }

    @Test
    @DisplayName("CA-P21-02: two simultaneous starts, one timer, no 500; the work order counts the time once")
    void ca02_twoSimultaneousStarts_oneTimer_no500_timeAndCostCountedOnce() {
        Context c = context();

        List<Outcome<Response>> outcomes;
        // the first insert waits one second: both requests read "no timer" before either one inserts
        try (Fault fault = faults.runBefore("labor", Operation.INSERT, "SELECT pg_sleep(1)")) {
            outcomes = Concurrently.run(2, i -> () -> start(c));
        }
        oneHourInEveryTimer(c);
        Response closed = close(c);

        assertAll(
                () -> assertTrue(outcomes.stream().allMatch(Outcome::succeeded), outcomes.toString()),
                () -> assertEquals(List.of(200, 200), outcomes.stream().map(o -> o.value().status()).toList(),
                        outcomes.toString()),
                () -> assertEquals(id(outcomes.get(0).value()), id(outcomes.get(1).value())),
                () -> assertEquals(1, timers(c)),
                () -> assertEquals(200, closed.status(), closed.toString()),
                () -> assertDuration(HOUR, laborTime(c)),
                () -> assertEquals(RATE, laborCost(c)));
    }

    @Test
    @DisplayName("CA-P21-03: two stops in a row, the timer stops once and its duration counts once")
    void ca03_twoStopsInARow_theTimerStopsOnce_durationCountedOnce() {
        Context c = context();
        assertEquals(200, start(c).status());
        oneHourInEveryTimer(c);

        Response first = stop(c);
        Response second = stop(c);

        assertAll(
                () -> assertEquals(200, first.status(), first.toString()),
                () -> assertEquals(200, second.status(), second.toString()),
                () -> assertEquals(id(first), id(second)),
                () -> assertEquals("STOPPED", json(second).get("status").asText()),
                () -> assertEquals(1, timers(c)),
                () -> assertDuration(HOUR, laborTime(c)),
                () -> assertEquals(RATE, laborCost(c)));
    }

    @Test
    @DisplayName("CA-P21-03: two simultaneous stops, the timer stops once and its duration counts once")
    void ca03_twoSimultaneousStops_theTimerStopsOnce_durationCountedOnce() {
        Context c = context();
        assertEquals(200, start(c).status());
        oneHourInEveryTimer(c);

        List<Outcome<Response>> outcomes;
        // the first update waits one second, holding the row: both requests read the timer running
        try (Fault fault = faults.runBefore("labor", Operation.UPDATE, "SELECT pg_sleep(1)")) {
            outcomes = Concurrently.run(2, i -> () -> stop(c));
        }

        assertAll(
                () -> assertTrue(outcomes.stream().allMatch(Outcome::succeeded), outcomes.toString()),
                () -> assertEquals(List.of(200, 200), outcomes.stream().map(o -> o.value().status()).toList(),
                        outcomes.toString()),
                () -> assertEquals(1, timers(c)),
                () -> assertEquals(0, running(c)),
                () -> assertDuration(HOUR, laborTime(c)),
                () -> assertEquals(RATE, laborCost(c)));
    }

    // =====================================================================================================
    // Other writers of a timer row: a 409, the rest of the API unchanged
    // =====================================================================================================

    @Test
    @DisplayName("D-P21-2: POST /labors with a second timer of the technician answers 409 LABOR_TIMER_TAKEN")
    void postLabors_aSecondTimerOfTheTechnician_409() {
        Context c = context();
        assertEquals(200, start(c).status());

        Response response = workOrders.call(post("/labors"), api.login(c.admin()), Map.of(
                "workOrder", Map.of("id", c.workOrder().getId()),
                "assignedTo", Map.of("id", c.technician().getId()),
                "logged", true, "status", "RUNNING", "startedAt", System.currentTimeMillis()));

        assertAll(
                () -> assertTimerTaken(response),
                () -> assertEquals(1, timers(c)));
    }

    @Test
    @DisplayName("D-P21-2: POST /labors with a timer row of a technician without one still creates it")
    void postLabors_aTimerOfATechnicianWithoutOne_200() {
        Context c = context();

        Response response = workOrders.call(post("/labors"), api.login(c.admin()), Map.of(
                "workOrder", Map.of("id", c.workOrder().getId()),
                "assignedTo", Map.of("id", c.technician().getId()),
                "logged", true, "startedAt", System.currentTimeMillis()));

        assertAll(
                () -> assertEquals(200, response.status(), response.toString()),
                () -> assertEquals(1, timers(c)));
    }

    @Test
    @DisplayName("D-P21-2: PATCH /labors/{id} reassigning a timer to a technician with a timer answers 409")
    void patchLabors_reassigningATimer_toATechnicianWithATimer_409() {
        Context c = context();
        Long adminTimer = id(api.call(post("/labors/work-order/" + c.workOrder().getId()), api.login(c.admin())));
        assertEquals(200, start(c).status());

        Response response = workOrders.call(patch("/labors/" + adminTimer), api.login(c.admin()), Map.of(
                "assignedTo", Map.of("id", c.technician().getId()),
                "startedAt", System.currentTimeMillis()));

        assertAll(
                () -> assertTimerTaken(response),
                () -> assertEquals(1, timers(c)),
                () -> assertEquals(c.admin().getId(), jdbc.queryForObject(
                        "SELECT assigned_to_id FROM labor WHERE id = ?", Long.class, adminTimer)));
    }

    @Test
    @DisplayName("D-P21-2: additional time entries (logged = false) of a technician with a timer are not limited")
    void postLabors_additionalTimeOfATechnicianWithATimer_200() {
        Context c = context();
        assertEquals(200, start(c).status());

        for (int i = 0; i < 2; i++) {
            Response response = workOrders.call(post("/labors"), api.login(c.admin()), Map.of(
                    "workOrder", Map.of("id", c.workOrder().getId()),
                    "assignedTo", Map.of("id", c.technician().getId()),
                    "duration", 600, "startedAt", System.currentTimeMillis()));
            assertEquals(200, response.status(), response.toString());
        }

        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM labor WHERE work_order_id = ?", Long.class,
                c.workOrder().getId()));
    }

    // =====================================================================================================
    // Migration of the timers repeated before the index
    // =====================================================================================================

    @Test
    @DisplayName("CA-P21-04: the migration leaves one timer per technician and work order, without losing time or "
            + "cost, and the index can then be created")
    void ca04_migration_oneTimerPerTechnicianAndWorkOrder_timeAndCostKept() {
        Context c = context();
        Company company = c.workOrder().getCompany();
        Long withRunning = c.workOrder().getId();
        Long allStopped = workOrders.workOrder(company, c.technician()).getId();
        Long single = workOrders.workOrder(company, c.technician()).getId();
        Long technician = c.technician().getId();

        transactionTemplate.executeWithoutResult(status -> {
            status.setRollbackOnly();
            jdbc.execute("SET LOCAL lock_timeout = '30s'");
            jdbc.execute("DROP INDEX uq_labor_timer");
            // a stopped timer, then two running ones: the first running one stays the timer
            long stoppedFirst = labor(company, withRunning, technician, true, 0, 3600, 100);
            long runningKept = labor(company, withRunning, technician, true, 1, 600, 120);
            long runningRepeated = labor(company, withRunning, technician, true, 1, 0, 120);
            long additional = labor(company, withRunning, technician, false, 0, 1800, 90);
            // no timer running: the lowest id stays the timer
            long stoppedKept = labor(company, allStopped, technician, true, 0, 1200, 100);
            long stoppedRepeated = labor(company, allStopped, technician, true, 0, 2400, 200);
            // not repeated: unchanged
            long alone = labor(company, single, technician, true, 1, 300, 100);
            Map<Long, List<Long>> totalsBefore = Map.of(withRunning, totals(withRunning),
                    allStopped, totals(allStopped), single, totals(single));

            jdbc.execute(sql(MIGRATION));
            jdbc.execute(INDEX);

            assertAll(
                    () -> assertEquals(List.of(runningKept), timerIds(withRunning, technician)),
                    () -> assertEquals(List.of(stoppedKept), timerIds(allStopped, technician)),
                    () -> assertEquals(List.of(alone), timerIds(single, technician)),
                    () -> assertRow(runningKept, true, 1, 600, 120),
                    () -> assertRow(stoppedFirst, false, 0, 3600, 100),
                    () -> assertRow(runningRepeated, false, 0, 0, 120),
                    () -> assertRow(additional, false, 0, 1800, 90),
                    () -> assertRow(stoppedKept, true, 0, 1200, 100),
                    () -> assertRow(stoppedRepeated, false, 0, 2400, 200),
                    () -> assertRow(alone, true, 1, 300, 100),
                    () -> assertEquals(totalsBefore.get(withRunning), totals(withRunning)),
                    () -> assertEquals(totalsBefore.get(allStopped), totals(allStopped)),
                    () -> assertEquals(totalsBefore.get(single), totals(single)),
                    () -> assertEquals(7, jdbc.queryForObject("SELECT COUNT(*) FROM labor WHERE work_order_id IN "
                            + "(?, ?, ?)", Long.class, withRunning, allStopped, single), "no row is deleted"));
        });
    }

    @Test
    @DisplayName("CA-P21-04: the migration changes nothing in a database without repeated timers")
    void ca04_migration_withoutRepeatedTimers_changesNothing() {
        Context c = context();
        assertEquals(200, start(c).status());
        List<Map<String, Object>> before = jdbc.queryForList("SELECT * FROM labor ORDER BY id");

        transactionTemplate.executeWithoutResult(status -> {
            status.setRollbackOnly();
            int changed = jdbc.update(sql(MIGRATION));

            assertAll(
                    () -> assertEquals(0, changed),
                    () -> assertEquals(before, jdbc.queryForList("SELECT * FROM labor ORDER BY id")));
        });
    }

    // =====================================================================================================
    // Helpers
    // =====================================================================================================

    private record Context(User admin, User technician, WorkOrder workOrder, String technicianToken) {
    }

    private Context context() {
        CommonData data = organizations.create();
        created.add(data);
        User t = data.o1().technician();
        jdbc.update("UPDATE own_user SET rate = ? WHERE id = ?", RATE, t.getId());
        WorkOrder workOrder = workOrders.workOrder(data.o1().company(), t);
        return new Context(data.o1().admin(), t, workOrder, api.login(t));
    }

    private Response start(Context c) {
        return api.call(post("/labors/work-order/" + c.workOrder().getId()), c.technicianToken());
    }

    private Response stop(Context c) {
        return api.call(post("/labors/work-order/" + c.workOrder().getId()).param("start", "false"),
                c.technicianToken());
    }

    private Response close(Context c) {
        return workOrders.call(patch("/work-orders/" + c.workOrder().getId() + "/change-status"),
                api.login(c.admin()), Map.of("status", "COMPLETE"));
    }

    private void oneHourInEveryTimer(Context c) {
        jdbc.update("UPDATE labor SET started_at = started_at - INTERVAL '1 hour' WHERE work_order_id = ? AND logged",
                c.workOrder().getId());
    }

    private long timers(Context c) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM labor WHERE work_order_id = ? AND assigned_to_id = ? "
                + "AND logged", Long.class, c.workOrder().getId(), c.technician().getId());
    }

    private long running(Context c) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM labor WHERE work_order_id = ? AND logged AND status = 1",
                Long.class, c.workOrder().getId());
    }

    private long laborTime(Context c) {
        return totals(c.workOrder().getId()).get(0);
    }

    private long laborCost(Context c) {
        return totals(c.workOrder().getId()).get(1);
    }

    /**
     * Labor time and cost of a work order, as the cost reports add them (WorkOrderRepository, WorkOrderService).
     */
    private List<Long> totals(Long workOrderId) {
        Map<String, Object> row = jdbc.queryForMap("SELECT COALESCE(SUM(duration), 0) AS time, "
                + "COALESCE(SUM(hourly_rate * duration / 3600), 0) AS cost FROM labor WHERE work_order_id = ?",
                workOrderId);
        return List.of(((Number) row.get("time")).longValue(), ((Number) row.get("cost")).longValue());
    }

    private long labor(Company company, Long workOrderId, Long assignedTo, boolean logged, int status, long duration,
                       long hourlyRate) {
        Long id = jdbc.queryForObject("SELECT nextval('labor_seq')", Long.class);
        jdbc.update("INSERT INTO labor (id, created_at, updated_at, duration, hourly_rate, include_to_total_time, "
                        + "logged, started_at, status, company_id, assigned_to_id, work_order_id, is_demo) "
                        + "VALUES (?, now(), now(), ?, ?, true, ?, now(), ?, ?, ?, ?, false)",
                id, duration, hourlyRate, logged, status, company.getId(), assignedTo, workOrderId);
        return id;
    }

    private List<Long> timerIds(Long workOrderId, Long assignedTo) {
        return jdbc.queryForList("SELECT id FROM labor WHERE work_order_id = ? AND assigned_to_id = ? AND logged "
                + "ORDER BY id", Long.class, workOrderId, assignedTo);
    }

    private void assertRow(long id, boolean logged, int status, long duration, long hourlyRate) {
        Map<String, Object> row = jdbc.queryForMap("SELECT logged, status, duration, hourly_rate FROM labor "
                + "WHERE id = ?", id);
        assertEquals(List.of(logged, status, duration, hourlyRate), List.of(row.get("logged"),
                ((Number) row.get("status")).intValue(), ((Number) row.get("duration")).longValue(),
                ((Number) row.get("hourly_rate")).longValue()), "labor " + id);
    }

    private void assertTimerTaken(Response response) {
        assertEquals(409, response.status(), response.toString());
        assertEquals("LABOR_TIMER_TAKEN", json(response).get("code").asText(), response.toString());
        assertFalse(response.body().contains("uq_labor_timer"), "no constraint name: " + response);
    }

    private static void assertDuration(long expected, long actual) {
        assertTrue(actual >= expected && actual < expected + SLACK, "expected about " + expected + " s, got "
                + actual);
    }

    private static String sql(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Long id(Response response) {
        return json(response).get("id").asLong();
    }

    private JsonNode json(Response response) {
        try {
            return objectMapper.readTree(response.body());
        } catch (Exception e) {
            throw new IllegalStateException(response.toString(), e);
        }
    }
}
