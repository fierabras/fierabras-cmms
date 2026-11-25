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

    /**
     * P-16: a pending purchase order with one line of {@code quantity} of {@code part}, through the API.
     */
    private Long pendingPurchaseOrder(Long part, double quantity) throws Exception {
        Response created = api.call(post("/purchase-orders").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + TestRecords.unique("P-16 PO") + "\"}"), admin);
        assertTrue(created.is2xx(), created.toString());
        Long purchaseOrder = objectMapper.readTree(created.body()).get("id").asLong();
        Response line = api.call(post("/part-quantities").contentType(MediaType.APPLICATION_JSON)
                .content("{\"part\":{\"id\":" + part + "},\"purchaseOrder\":{\"id\":" + purchaseOrder
                        + "},\"quantity\":" + quantity + "}"), admin);
        assertTrue(line.is2xx(), line.toString());
        return purchaseOrder;
    }

    private Response approvePurchaseOrder(Long purchaseOrder) {
        return api.call(patch("/purchase-orders/" + purchaseOrder + "/respond").param("approved", "true"), admin);
    }

    private double stock(Long part) {
        return jdbc.queryForObject("SELECT quantity FROM part WHERE id = ?", Double.class, part);
    }

    @Test
    void pendingPurchaseOrderDeleted_whileItIsBeingApproved_409PurchaseOrderClosed_stockAddedOnce() throws Exception {
        // P-16 (R-1): the approval holds the row; the deletion waits for it and then reads it approved
        Long part = records.part(data.o1().company(), null).getId();
        Long purchaseOrder = pendingPurchaseOrder(part, 3);
        AtomicReference<Response> approval = new AtomicReference<>();

        Response result = compete(() -> approval.set(approvePurchaseOrder(purchaseOrder)),
                () -> api.call(delete("/purchase-orders/" + purchaseOrder), admin));

        assertEquals(200, approval.get().status(), approval.get().toString());
        assertEquals(409, result.status(), result.toString());
        assertTrue(result.body().contains("PURCHASE_ORDER_CLOSED"), result.toString());
        assertEquals(0, jdbc.queryForObject("SELECT status FROM purchase_order WHERE id = ?", Integer.class,
                purchaseOrder), "the purchase order stays approved (APPROVED is 0)");
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM part_quantity WHERE purchase_order_id = ?",
                Integer.class, purchaseOrder));
        assertEquals(3, stock(part), "the stock is added once");
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE company_id = ?", Integer.class,
                data.o1().company().getId()));
    }

    @Test
    void pendingPurchaseOrderApproved_whileItIsBeingDeleted_404_noStockAdded() throws Exception {
        // P-16 (R-2): the deletion holds the row; the approval waits for it and then finds no purchase order
        Long part = records.part(data.o1().company(), null).getId();
        Long purchaseOrder = pendingPurchaseOrder(part, 3);
        AtomicReference<Response> deletion = new AtomicReference<>();

        Response result = compete(() -> deletion.set(api.call(delete("/purchase-orders/" + purchaseOrder), admin)),
                () -> approvePurchaseOrder(purchaseOrder));

        assertEquals(200, deletion.get().status(), deletion.get().toString());
        assertEquals(404, result.status(), result.toString());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM purchase_order WHERE id = ?", Integer.class,
                purchaseOrder));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM part_quantity WHERE purchase_order_id = ?",
                Integer.class, purchaseOrder));
        assertEquals(0, stock(part), "no stock added by a purchase order that no longer exists");
        assertEquals(List.of("PURCHASE_ORDER {\"part_quantity\": 1}"), jdbc.queryForList("SELECT entity_type || ' ' "
                + "|| cascade_rows::text FROM audit_log WHERE company_id = ?", String.class,
                data.o1().company().getId()));
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

    /**
     * P-14b (D-6): runs {@code first} in a transaction that stays open while {@code second} runs to its answer on
     * another session, then commits {@code first}. For an archiving left open, the new work order does not wait: its
     * foreign key check reads the asset row without waiting for the update.
     */
    private Response whileOpen(Runnable first, Supplier<Response> second) throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            return transaction.execute(status -> {
                first.run();
                try {
                    return worker.submit(second::get).get(WAIT_SECONDS, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
        } finally {
            worker.shutdownNow();
        }
    }

    private Response createWorkOrderCiting(Asset asset) {
        return api.call(post("/work-orders").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\""
                + TestRecords.unique("P-14b WO") + "\",\"asset\":{\"id\":" + asset.getId() + "}}"), admin);
    }

    /**
     * P-14b (D-6): the admitted final states: (i) 409 ASSET_RETIRED and no work order, or (ii) 200 and a work order
     * that cites the archived asset (as if it had been created first). Never a 500, and the asset stays archived.
     */
    private void assertAdmittedState(Response result, Asset asset) {
        assertTrue(result.status() == 200 || result.status() == 409, result.toString());
        assertEquals(Boolean.TRUE, jdbc.queryForObject("SELECT archived FROM asset WHERE id = ?", Boolean.class,
                asset.getId()));
        int citing = jdbc.queryForObject("SELECT count(*) FROM work_order WHERE asset_id = ?", Integer.class,
                asset.getId());
        if (result.status() == 409) {
            assertTrue(result.body().contains("\"code\":\"ASSET_RETIRED\""), result.toString());
            assertEquals(0, citing);
        } else assertEquals(1, citing);
    }

    @Test
    void workOrderCitingTheAsset_whileTheAssetIsBeingArchived_admittedState() throws Exception {
        Asset asset = records.asset(data.o1().company());

        Response result = whileOpen(() -> jdbc.update("UPDATE asset SET archived = true WHERE id = ?",
                asset.getId()), () -> createWorkOrderCiting(asset));

        assertAdmittedState(result, asset);
        // without a lock (D-6 a) the other session does not see the archiving yet: state (ii)
        assertEquals(200, result.status(), result.toString());
    }

    @Test
    void assetArchived_whileAWorkOrderCitingItIsBeingCreated_waits_thenArchives_admittedState() throws Exception {
        // the update of the asset waits for the transaction that inserts the work order (its foreign key holds a
        // share lock on the asset row), so the archiving lands after it: state (ii)
        Asset asset = records.asset(data.o1().company());

        Response result = compete(() -> records.workOrder(data.o1().company(), asset, Status.OPEN, false),
                () -> api.call(patch("/assets/" + asset.getId()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"archived\":true}"), admin));

        assertEquals(200, result.status(), result.toString());
        assertAdmittedState(result, asset);
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE company_id = ? AND action = "
                + "'RETIRE'", Integer.class, data.o1().company().getId()));
    }

    /**
     * P-16b: replaces the lines of the purchase order by one line of {@code quantity} of {@code part}.
     */
    private Response changeLines(Long purchaseOrder, Long part, double quantity) {
        return api.call(patch("/part-quantities/purchase-order/" + purchaseOrder)
                .contentType(MediaType.APPLICATION_JSON)
                .content("[{\"part\":{\"id\":" + part + "},\"quantity\":" + quantity + "}]"), admin);
    }

    /**
     * P-16b: the movements of {@code part} as "quantity origin purchase order".
     */
    private List<String> movements(Long part) {
        return jdbc.queryForList("SELECT quantity || ' ' || origin || ' ' || COALESCE(purchase_order_id::text, '-') "
                + "FROM part_transaction WHERE part_id = ? ORDER BY id", String.class, part);
    }

    @Test
    void purchaseOrderLinesChanged_whileItIsBeingApproved_409PurchaseOrderClosed_stockOfTheApprovedLine()
            throws Exception {
        // P-16b (E-16, TD-P16-04): the approval holds the purchase order; the change of its lines waits for it and then
        // reads it approved
        Long part = records.part(data.o1().company(), null).getId();
        Long purchaseOrder = pendingPurchaseOrder(part, 3);
        AtomicReference<Response> approval = new AtomicReference<>();

        Response result = compete(() -> approval.set(approvePurchaseOrder(purchaseOrder)),
                () -> changeLines(purchaseOrder, part, 5));

        assertEquals(200, approval.get().status(), approval.get().toString());
        assertEquals(409, result.status(), result.toString());
        assertTrue(result.body().contains("PURCHASE_ORDER_CLOSED"), result.toString());
        assertEquals(3, jdbc.queryForObject("SELECT quantity FROM part_quantity WHERE purchase_order_id = ?",
                Double.class, purchaseOrder));
        assertEquals(3, stock(part));
        assertEquals(List.of("3 PURCHASE_ORDER " + purchaseOrder), movements(part));
    }

    @Test
    void purchaseOrderApproved_whileItsLinesAreBeingChanged_approvedWithTheNewLine() throws Exception {
        // P-16b (E-16, TD-P16-04): the change of the lines holds the purchase order; the approval waits for it and then
        // adds the stock of the line that was saved
        Long part = records.part(data.o1().company(), null).getId();
        Long purchaseOrder = pendingPurchaseOrder(part, 3);
        AtomicReference<Response> lines = new AtomicReference<>();

        Response result = compete(() -> lines.set(changeLines(purchaseOrder, part, 5)),
                () -> approvePurchaseOrder(purchaseOrder));

        assertEquals(200, lines.get().status(), lines.get().toString());
        assertEquals(200, result.status(), result.toString());
        assertEquals(5, stock(part));
        assertEquals(List.of("5 PURCHASE_ORDER " + purchaseOrder), movements(part));
    }

    /**
     * P-16b: adds {@code part} to the work order through the bulk replacement, which consumes one.
     */
    private Response consumeOne(WorkOrder workOrder, Long part) {
        return api.call(patch("/part-quantities/work-order/" + workOrder.getId())
                .contentType(MediaType.APPLICATION_JSON).content("[" + part + "]"), admin);
    }

    /**
     * P-16b: a part with {@code quantity} in stock, with the movement that explains it.
     */
    private Long partWithStock(double quantity) {
        Long part = records.part(data.o1().company(), null).getId();
        jdbc.update("UPDATE part SET quantity = ? WHERE id = ?", quantity, part);
        jdbc.update("INSERT INTO part_transaction (id, created_at, updated_at, company_id, part_id, quantity, origin, "
                + "is_demo) VALUES (nextval('part_transaction_seq'), now(), now(), ?, ?, ?, 'ADJUSTMENT', "
                + "false)", data.o1().company().getId(), part, quantity);
        return part;
    }

    private double sumOfMovements(Long part) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(quantity), 0) FROM part_transaction WHERE part_id = ?",
                Double.class, part);
    }

    @Test
    void lastUnitConsumedOnTwoWorkOrdersAtOnce_one200AndOne406_neverBelowZero() throws Exception {
        // P-16b (E-1): the ledger locks the part; the second consumption waits and then finds no stock
        Long part = partWithStock(1);
        WorkOrder first = records.workOrder(data.o1().company(), null, Status.OPEN, false);
        WorkOrder second = records.workOrder(data.o1().company(), null, Status.OPEN, false);
        AtomicReference<Response> held = new AtomicReference<>();

        Response result = compete(() -> held.set(consumeOne(first, part)), () -> consumeOne(second, part));

        assertEquals(200, held.get().status(), held.get().toString());
        assertEquals(406, result.status(), result.toString());
        assertEquals(0, stock(part));
        assertEquals(0, sumOfMovements(part));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM part_quantity WHERE work_order_id = ?",
                Integer.class, second.getId()));
    }

    @Test
    void twoUnitsConsumedOnTwoWorkOrdersAtOnce_both200_noStockLost() throws Exception {
        // P-16b (E-1): without the lock both would read 2 and write 1
        Long part = partWithStock(2);
        WorkOrder first = records.workOrder(data.o1().company(), null, Status.OPEN, false);
        WorkOrder second = records.workOrder(data.o1().company(), null, Status.OPEN, false);
        AtomicReference<Response> held = new AtomicReference<>();

        Response result = compete(() -> held.set(consumeOne(first, part)), () -> consumeOne(second, part));

        assertEquals(200, held.get().status(), held.get().toString());
        assertEquals(200, result.status(), result.toString());
        assertEquals(0, stock(part));
        assertEquals(0, sumOfMovements(part));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM part_transaction WHERE part_id = ? "
                + "AND origin = 'CONSUMPTION'", Integer.class, part));
    }

    @Test
    void purchaseOrderLineChangedAlone_whileItIsBeingApproved_409PurchaseOrderClosed() throws Exception {
        // P-16b (E-16): the line is read before the lock; the state of its purchase order must be the committed one
        Long part = records.part(data.o1().company(), null).getId();
        Long purchaseOrder = pendingPurchaseOrder(part, 3);
        Long line = jdbc.queryForObject("SELECT id FROM part_quantity WHERE purchase_order_id = ?", Long.class,
                purchaseOrder);
        AtomicReference<Response> approval = new AtomicReference<>();

        Response result = compete(() -> approval.set(approvePurchaseOrder(purchaseOrder)),
                () -> api.call(patch("/part-quantities/" + line).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":5}"), admin));

        assertEquals(200, approval.get().status(), approval.get().toString());
        assertEquals(409, result.status(), result.toString());
        assertEquals(3, jdbc.queryForObject("SELECT quantity FROM part_quantity WHERE id = ?", Double.class, line));
        assertEquals(3, stock(part));
    }
}
