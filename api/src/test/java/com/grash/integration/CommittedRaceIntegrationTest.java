package com.grash.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.grash.integration.support.TestApi;
import com.grash.integration.support.TestApi.Response;
import com.grash.integration.support.TestOrganizations;
import com.grash.integration.support.TestOrganizations.CommonData;
import com.grash.integration.support.TestRecords;
import com.grash.integration.support.TestWorkOrders;
import com.grash.integration.support.TestWorkOrders.Fixture;
import com.grash.model.Asset;
import com.grash.model.User;
import com.grash.model.WorkOrder;
import com.grash.model.enums.Status;
import com.grash.repository.FileRepository;
import com.grash.repository.PartRepository;
import com.grash.repository.WorkOrderRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

/**
 * P-12c (H1-13): races between two real transactions, from the independent audit H-1. Unlike the tests that insert
 * the reference with a trigger inside the transaction of the deletion, here the reference is written by another
 * session, and the result is read after both commit: a 409 or a coherent state, never a 500 nor an orphan row.
 */
@AutoConfigureMockMvc
class CommittedRaceIntegrationTest extends AbstractIntegrationTest {

    private static final long WAIT_SECONDS = 15;

    private final TestOrganizations organizations;
    private final TestRecords records;
    private final TestApi api;
    private final TestWorkOrders workOrders;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private CommonData data;
    private User superAdmin;
    private String admin;

    @Autowired
    CommittedRaceIntegrationTest(TestOrganizations organizations, TestRecords records, MockMvc mockMvc,
                                 ObjectMapper objectMapper, JdbcTemplate jdbc, TransactionTemplate transaction,
                                 WorkOrderRepository workOrderRepository, PartRepository partRepository,
                                 FileRepository fileRepository) {
        this.organizations = organizations;
        this.records = records;
        this.api = new TestApi(mockMvc, objectMapper);
        this.workOrders = new TestWorkOrders(api, objectMapper, records, workOrderRepository, partRepository,
                fileRepository);
        this.jdbc = jdbc;
        this.transaction = transaction;
    }

    @BeforeEach
    void setUp() {
        data = organizations.create();
        admin = api.login(data.o1().admin());
    }

    @AfterEach
    void cleanUp() {
        organizations.delete(data);
        if (superAdmin != null) organizations.deleteUser(superAdmin);
    }

    /**
     * Runs {@code reference} in a transaction that stays open until PostgreSQL shows the {@code deletion}, started on
     * another thread, waiting for this session; then commits it and returns the answer of the deletion.
     */
    private Response compete(Runnable reference, Supplier<Response> deletion) throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<Response> request = transaction.execute(status -> {
                reference.run();
                Future<Response> pending = worker.submit(deletion::get);
                long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
                while (System.nanoTime() < limit) {
                    if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT FROM pg_stat_activity a "
                            + "WHERE pg_backend_pid() = ANY(pg_blocking_pids(a.pid)))", Boolean.class)))
                        return pending;
                    if (pending.isDone()) break;
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(25));
                }
                throw new AssertionError("the second session was not seen waiting");
            });
            return request.get(WAIT_SECONDS + 5, TimeUnit.SECONDS);
        } finally {
            worker.shutdownNow();
        }
    }

    private int sessionAtBarrier(CyclicBarrier start) {
        int pid = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
        try {
            start.await(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return pid;
    }

    private Status status(Long id) {
        return Status.values()[jdbc.queryForObject("SELECT status FROM work_order WHERE id = ?", Integer.class, id)];
    }

    @Test
    void workOrderCitingTheAsset_whileTheAssetIsDeleted_409_referenceCommitted() throws Exception {
        Asset asset = records.asset(data.o1().company());

        Response result = compete(() -> records.workOrder(data.o1().company(), asset, Status.OPEN, false),
                () -> api.call(delete("/assets/" + asset.getId()), admin));

        assertEquals(409, result.status(), result.toString());
        assertTrue(result.body().contains("ASSET_IN_USE"), result.toString());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM work_order w JOIN asset a ON a.id = w.asset_id "
                + "WHERE a.id = ?", Integer.class, asset.getId()));
    }

    @Test
    void userAssignedToAWorkOrder_whileTheUserIsDeleted_409_referenceCommitted() throws Exception {
        User technician = data.o1().technician();
        WorkOrder workOrder = records.workOrder(data.o1().company(), null, Status.OPEN, false);
        superAdmin = organizations.superAdmin();
        String caller = api.login(superAdmin, "SUPER_ADMIN");

        Response result = compete(() -> jdbc.update("UPDATE work_order SET primary_user_id = ? WHERE id = ?",
                technician.getId(), workOrder.getId()),
                () -> api.call(delete("/auth/" + technician.getUsername()), caller));

        assertEquals(409, result.status(), result.toString());
        assertTrue(result.body().contains("USER_IN_USE"), result.toString());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM work_order w JOIN own_user u "
                + "ON w.primary_user_id = u.id WHERE w.id = ? AND u.id = ?", Integer.class, workOrder.getId(),
                technician.getId()));
    }

    @Test
    void costEdited_whileItsWorkOrderIsClosed_coherent() throws Exception {
        Fixture f = workOrders.create(data.o1().admin(), data.o1().technician(), Status.OPEN, false);
        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> edit = worker.submit(() -> transaction.execute(status -> {
                sessionAtBarrier(start);
                return workOrders.call(patch("/additional-costs/" + f.cost()), admin,
                        Map.of("cost", 19, "description", "P-12c edited")).status();
            }));
            transaction.executeWithoutResult(status -> {
                sessionAtBarrier(start);
                Response close = workOrders.call(patch("/work-orders/" + f.id() + "/change-status"), admin,
                        Map.of("status", "COMPLETE"));
                assertEquals(200, close.status(), close.toString());
            });
            int http = edit.get(WAIT_SECONDS + 5, TimeUnit.SECONDS);

            boolean changed = Boolean.TRUE.equals(jdbc.queryForObject("SELECT description = 'P-12c edited' "
                    + "FROM additional_cost WHERE id = ? AND work_order_id = ?", Boolean.class, f.cost(), f.id()));
            assertEquals(Status.COMPLETE, status(f.id()));
            assertTrue((http == 409 && !changed) || (http == 200 && changed),
                    "HTTP " + http + ", cost changed: " + changed);
        } finally {
            worker.shutdownNow();
        }
    }
}
