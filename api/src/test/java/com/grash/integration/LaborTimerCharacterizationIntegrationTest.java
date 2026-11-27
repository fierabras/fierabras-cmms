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
import com.grash.model.User;
import com.grash.model.WorkOrder;
import com.grash.repository.FileRepository;
import com.grash.repository.PartRepository;
import com.grash.repository.WorkOrderRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * P-21 characterization: fixes, before any change, what the labor timer does today when a technician sends
 * "start" or "stop" twice, in a row and at the same time; how much time and cost the work order counts in each case;
 * the other ways to write a timer row ({@code labor.logged = true}); and what a unique index does over repeated
 * timers.
 */
@AutoConfigureMockMvc
class LaborTimerCharacterizationIntegrationTest extends AbstractIntegrationTest {

    /**
     * Hourly rate of the technician: one hour of timer costs this much.
     */
    private static final long RATE = 120;
    private static final long HOUR = 3600;
    /**
     * Seconds a stop may add beyond the hour the test puts in the timer: the time the test itself takes.
     */
    private static final long SLACK = 30;

    private final TestOrganizations organizations;
    private final TestApi api;
    private final TestWorkOrders workOrders;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;
    private final FaultInjector faults;
    private final List<CommonData> created = new ArrayList<>();

    @Autowired
    LaborTimerCharacterizationIntegrationTest(TestOrganizations organizations, TestRecords records, MockMvc mockMvc,
                                              ObjectMapper objectMapper, JdbcTemplate jdbc,
                                              WorkOrderRepository workOrderRepository,
                                              PartRepository partRepository, FileRepository fileRepository) {
        this.organizations = organizations;
        this.api = new TestApi(mockMvc, objectMapper);
        this.workOrders = new TestWorkOrders(api, objectMapper, records, workOrderRepository, partRepository,
                fileRepository);
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
        this.faults = new FaultInjector(jdbc);
    }

    @AfterEach
    void cleanUp() {
        faults.removeAll();
        created.forEach(organizations::delete);
        created.clear();
    }

    // =====================================================================================================
    // Start
    // =====================================================================================================

    @Test
    void twoStartsInARow_oneTimer_theSecondAnswerIsTheSameTimer() {
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
    void twoSimultaneousStarts_todayTwoTimers_andTheWorkOrderCountsTimeAndCostTwice() {
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
                () -> assertEquals(List.of(200, 200), outcomes.stream().map(o -> o.value().status()).toList()),
                () -> assertEquals(200, closed.status(), closed.toString()),
                // today: two timers of the same technician on the same work order
                () -> assertEquals(2, timers(c)),
                () -> assertEquals(0, running(c), "closing the work order stops both"),
                // today: the work order counts the hour, and its cost, twice
                () -> assertDuration(2 * HOUR, laborTime(c)),
                () -> assertEquals(2 * RATE, laborCost(c)));
    }

    // =====================================================================================================
    // Stop
    // =====================================================================================================

    @Test
    void twoStopsInARow_theTimerStopsOnce_andItsDurationCountsOnce() {
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
                () -> assertEquals(json(first).get("duration").asLong(), json(second).get("duration").asLong()),
                () -> assertEquals(1, timers(c)),
                () -> assertDuration(HOUR, laborTime(c)),
                () -> assertEquals(RATE, laborCost(c)));
    }

    @Test
    void twoSimultaneousStops_theTimerStopsOnce_andItsDurationCountsOnce() {
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
                () -> assertEquals(List.of(200, 200), outcomes.stream().map(o -> o.value().status()).toList()),
                () -> assertEquals(1, timers(c)),
                () -> assertEquals(0, running(c)),
                // the second write replaces the first; it does not add to it
                () -> assertDuration(HOUR, laborTime(c)),
                () -> assertEquals(RATE, laborCost(c)));
    }

    @Test
    void theStatusOfATimer_isStoredAsItsOrdinal_stopped0_running1() {
        Context c = context();
        Long id = id(start(c));
        Integer whileRunning = jdbc.queryForObject("SELECT status FROM labor WHERE id = ?", Integer.class, id);
        stop(c);
        Integer afterStop = jdbc.queryForObject("SELECT status FROM labor WHERE id = ?", Integer.class, id);

        assertAll(
                () -> assertEquals(1, whileRunning),
                () -> assertEquals(0, afterStop));
    }

    // =====================================================================================================
    // Other writers of a timer row
    // =====================================================================================================

    @Test
    void postLabors_withLoggedTrue_todayCreatesASecondTimerOfTheTechnician() {
        Context c = context();
        assertEquals(200, start(c).status());

        Response created = workOrders.call(post("/labors"), api.login(c.admin()), Map.of(
                "workOrder", Map.of("id", c.workOrder().getId()),
                "assignedTo", Map.of("id", c.technician().getId()),
                "logged", true, "status", "RUNNING", "startedAt", System.currentTimeMillis()));

        assertAll(
                () -> assertEquals(200, created.status(), created.toString()),
                () -> assertTrue(json(created).get("logged").asBoolean()),
                () -> assertEquals(2, timers(c)));
    }

    @Test
    void patchLabors_reassigningATimer_toATechnicianWithATimer_todayLeavesHimTwo() {
        Context c = context();
        Long adminTimer = id(api.call(post("/labors/work-order/" + c.workOrder().getId()), api.login(c.admin())));
        assertEquals(200, start(c).status());

        Response reassigned = workOrders.call(patch("/labors/" + adminTimer), api.login(c.admin()), Map.of(
                "assignedTo", Map.of("id", c.technician().getId()),
                "startedAt", System.currentTimeMillis()));

        assertAll(
                () -> assertEquals(200, reassigned.status(), reassigned.toString()),
                () -> assertEquals(2, timers(c)));
    }

    // =====================================================================================================
    // A unique index over repeated timers
    // =====================================================================================================

    @Test
    void aUniqueIndexOnTheTimer_cannotBeCreatedOverRepeatedTimers() {
        Context c = context();
        assertEquals(200, start(c).status());
        assertEquals(200, workOrders.call(post("/labors"), api.login(c.admin()), Map.of(
                "workOrder", Map.of("id", c.workOrder().getId()),
                "assignedTo", Map.of("id", c.technician().getId()),
                "logged", true, "startedAt", System.currentTimeMillis())).status());

        try {
            DataAccessException failure = assertThrows(DataAccessException.class, () -> jdbc.execute(
                    "CREATE UNIQUE INDEX p21_probe ON labor (work_order_id, assigned_to_id) WHERE logged"));
            assertTrue(String.valueOf(failure.getMostSpecificCause().getMessage())
                    .contains("could not create unique index"), failure.getMostSpecificCause().getMessage());
        } finally {
            jdbc.execute("DROP INDEX IF EXISTS p21_probe");
        }
    }

    // =====================================================================================================
    // Helpers
    // =====================================================================================================

    private record Context(User admin, User technician, WorkOrder workOrder, String technicianToken) {
    }

    /**
     * An open work order assigned to the technician T of a new organization, whose hourly rate is {@link #RATE}.
     */
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

    /**
     * Moves the start of every timer of the work order one hour back, so that a stop adds one hour.
     */
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

    /**
     * Labor time of the work order, as the cost reports add it (WorkOrderRepository, WorkOrderService).
     */
    private long laborTime(Context c) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(duration), 0) FROM labor WHERE work_order_id = ?",
                Long.class, c.workOrder().getId());
    }

    private long laborCost(Context c) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(hourly_rate * duration / 3600), 0) FROM labor "
                + "WHERE work_order_id = ?", Long.class, c.workOrder().getId());
    }

    private static void assertDuration(long expected, long actual) {
        assertTrue(actual >= expected && actual < expected + SLACK, "expected about " + expected + " s, got "
                + actual);
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
