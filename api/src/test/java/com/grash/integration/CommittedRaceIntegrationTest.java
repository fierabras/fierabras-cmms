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
import com.grash.model.File;
import com.grash.model.Location;
import com.grash.model.User;
import com.grash.model.WorkOrder;
import com.grash.model.WorkOrderCategory;
import com.grash.model.enums.Status;
import com.grash.repository.FileRepository;
import com.grash.repository.PartRepository;
import com.grash.repository.WorkOrderCategoryRepository;
import com.grash.repository.WorkOrderRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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
    private final WorkOrderCategoryRepository workOrderCategoryRepository;
    private final FileRepository fileRepository;
    private final ObjectMapper objectMapper;
    private CommonData data;
    private User superAdmin;
    private String admin;

    @Autowired
    CommittedRaceIntegrationTest(TestOrganizations organizations, TestRecords records, MockMvc mockMvc,
                                 ObjectMapper objectMapper, JdbcTemplate jdbc, TransactionTemplate transaction,
                                 WorkOrderRepository workOrderRepository, PartRepository partRepository,
                                 FileRepository fileRepository,
                                 WorkOrderCategoryRepository workOrderCategoryRepository) {
        this.organizations = organizations;
        this.records = records;
        this.api = new TestApi(mockMvc, objectMapper);
        this.workOrders = new TestWorkOrders(api, objectMapper, records, workOrderRepository, partRepository,
                fileRepository);
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.workOrderCategoryRepository = workOrderCategoryRepository;
        this.fileRepository = fileRepository;
        this.objectMapper = objectMapper;
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
    void assetGivenTheLocation_whileTheLocationIsDeleted_409LocationInUse_referenceCommitted() throws Exception {
        // P-14 (R-1): the location had no dependents when its deletion started
        Location location = records.location(data.o1().company());
        Asset asset = records.asset(data.o1().company());

        Response result = compete(() -> jdbc.update("UPDATE asset SET location_id = ? WHERE id = ?",
                location.getId(), asset.getId()), () -> api.call(delete("/locations/" + location.getId()), admin));

        assertEquals(409, result.status(), result.toString());
        assertTrue(result.body().contains("\"code\":\"LOCATION_IN_USE\""), result.toString());
        assertTrue(result.body().contains("\"blockingTypes\":[\"ASSET\"]"), result.toString());
        assertEquals(location.getId(), jdbc.queryForObject("SELECT location_id FROM asset WHERE id = ?", Long.class,
                asset.getId()));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE company_id = ?", Integer.class,
                data.o1().company().getId()));
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

    @Test
    void workOrderGivenTheCategory_whileTheCategoryIsDeleted_409_referenceCommitted() throws Exception {
        // P-12b: the check of the rule does not see the uncommitted reference; the foreign key (NO ACTION) does
        WorkOrderCategory category = workOrderCategoryRepository.saveAndFlush(new WorkOrderCategory(
                TestRecords.unique("P-12b category"), data.o1().company().getCompanySettings()));
        WorkOrder workOrder = records.workOrder(data.o1().company(), null, Status.COMPLETE, false);

        Response result = compete(() -> jdbc.update("UPDATE work_order SET category_id = ? WHERE id = ?",
                        category.getId(), workOrder.getId()),
                () -> api.call(delete("/work-order-categories/" + category.getId()), admin));

        assertEquals(409, result.status(), result.toString());
        assertTrue(result.body().contains("WORK_ORDER_CATEGORY_IN_USE"), result.toString());
        assertTrue(result.body().contains("\"blockingTypes\":[\"WORK_ORDER\"]"), result.toString());
        assertEquals(category.getId(), jdbc.queryForObject("SELECT category_id FROM work_order WHERE id = ?",
                Long.class, workOrder.getId()));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE company_id = ?", Integer.class,
                data.o1().company().getId()));
    }

    @Test
    void assetGivenTheImage_whileTheFileIsDeleted_409_referenceCommitted_noStorageDelete() throws Exception {
        // P-17: the check of the rule does not see the uncommitted reference; the foreign key (NO ACTION) does
        Asset asset = records.asset(data.o1().company());
        File file = new File();
        file.setName(TestRecords.unique("P-17 file"));
        file.setPath("p17/" + file.getName());
        file.setCompany(data.o1().company());
        Long fileId = fileRepository.saveAndFlush(file).getId();

        Response result = compete(() -> jdbc.update("UPDATE asset SET image_id = ? WHERE id = ?", fileId,
                        asset.getId()),
                () -> api.call(delete("/files/" + fileId), admin));

        assertEquals(409, result.status(), result.toString());
        assertTrue(result.body().contains("FILE_IN_USE"), result.toString());
        assertTrue(result.body().contains("\"blockingTypes\":[\"ASSET\"]"), result.toString());
        assertEquals(fileId, jdbc.queryForObject("SELECT image_id FROM asset WHERE id = ?", Long.class,
                asset.getId()));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM file WHERE id = ?", Integer.class, fileId));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE company_id = ?", Integer.class,
                data.o1().company().getId()));
        assertEquals(List.of(), storageDouble.calls("delete"));
    }

    private Long pendingRequest() throws Exception {
        Response created = api.call(post("/requests").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"" + TestRecords.unique("P-13 request") + "\"}"), admin);
        assertTrue(created.is2xx(), created.toString());
        return objectMapper.readTree(created.body()).get("id").asLong();
    }

    private Response approve(Long request) {
        return api.call(patch("/requests/" + request + "/approve").contentType(MediaType.APPLICATION_JSON)
                .content("{}"), admin);
    }

    private long workOrdersCiting(Long request) {
        return jdbc.queryForObject("SELECT count(*) FROM work_order WHERE parent_request_id = ?", Long.class,
                request);
    }

    @Test
    void pendingRequestDeleted_whileItIsBeingApproved_409RequestClosed_linkKept() throws Exception {
        // P-13 (R-1): the approval holds the review lock; the deletion waits for it and then reads it approved
        Long request = pendingRequest();
        AtomicReference<Response> approval = new AtomicReference<>();

        Response result = compete(() -> approval.set(approve(request)),
                () -> api.call(delete("/requests/" + request), admin));

        assertEquals(200, approval.get().status(), approval.get().toString());
        assertEquals(409, result.status(), result.toString());
        assertTrue(result.body().contains("REQUEST_CLOSED"), result.toString());
        Long workOrder = jdbc.queryForObject("SELECT work_order_id FROM request WHERE id = ?", Long.class, request);
        assertNotNull(workOrder, "the request stays approved");
        assertEquals(request, jdbc.queryForObject("SELECT parent_request_id FROM work_order WHERE id = ?",
                Long.class, workOrder), "the work order keeps its request");
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE company_id = ?", Integer.class,
                data.o1().company().getId()));
    }

    @Test
    void pendingRequestApproved_whileItIsBeingDeleted_404_noWorkOrder() throws Exception {
        // P-13 (R-2): the deletion holds the review lock; the approval waits for it and then finds no request
        Long request = pendingRequest();
        AtomicReference<Response> deletion = new AtomicReference<>();

        Response result = compete(() -> deletion.set(api.call(delete("/requests/" + request), admin)),
                () -> approve(request));

        assertEquals(200, deletion.get().status(), deletion.get().toString());
        assertEquals(404, result.status(), result.toString());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM request WHERE id = ?", Integer.class, request));
        assertEquals(0, workOrdersCiting(request));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM work_order WHERE company_id = ?", Integer.class,
                data.o1().company().getId()), "no work order was created");
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE company_id = ? "
                + "AND entity_type = 'REQUEST'", Integer.class, data.o1().company().getId()));
    }
}
