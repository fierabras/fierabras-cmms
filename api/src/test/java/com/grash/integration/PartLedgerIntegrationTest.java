package com.grash.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grash.dto.imports.PartImportDTO;
import com.grash.integration.support.FaultInjector;
import com.grash.integration.support.TestApi;
import com.grash.integration.support.TestApi.Response;
import com.grash.integration.support.TestOrganizations;
import com.grash.integration.support.TestOrganizations.CommonData;
import com.grash.integration.support.TestRecords;
import com.grash.model.Company;
import com.grash.model.CompanySettings;
import com.grash.model.enums.ApprovalStatus;
import com.grash.model.enums.Language;
import com.grash.model.enums.PartTransactionOrigin;
import com.grash.model.enums.Status;
import com.grash.model.enums.webhook.WebhookEvent;
import com.grash.model.enums.workflow.PurchaseOrderAction;
import com.grash.model.enums.workflow.WFMainCondition;
import com.grash.model.Part;
import com.grash.model.PartQuantity;
import com.grash.model.PartTransaction;
import com.grash.model.PurchaseOrder;
import com.grash.model.User;
import com.grash.model.Workflow;
import com.grash.model.WorkflowAction;
import com.grash.model.WorkOrder;
import com.grash.repository.PartQuantityRepository;
import com.grash.repository.PurchaseOrderRepository;
import com.grash.repository.WorkflowRepository;
import com.grash.service.DemoDataService;
import com.grash.service.ImportService;
import com.grash.service.PartLedgerService;
import com.grash.service.PartLedgerService.Movement;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * P-16b (PPB-04, PPB-16, PPB-30): every change of the stock of a part leaves one movement of the ledger, with its
 * signed quantity (entry positive, exit negative), date, user, origin and document; the stock always equals the sum
 * of the movements of the part; a movement is never changed nor deleted by the application.
 */
@AutoConfigureMockMvc
class PartLedgerIntegrationTest extends AbstractIntegrationTest {

    final TestOrganizations organizations;
    final TestRecords records;
    final TestApi api;
    final ObjectMapper objectMapper;
    final JdbcTemplate jdbc;
    final PartQuantityRepository partQuantityRepository;
    final PurchaseOrderRepository purchaseOrderRepository;
    final PartLedgerService ledger;
    final EntityManager em;
    final TransactionTemplate transaction;
    final FaultInjector faults;
    final ImportService importService;
    final WorkflowRepository workflowRepository;
    final DemoDataService demoDataService;
    final List<CommonData> created = new ArrayList<>();

    @Autowired
    PartLedgerIntegrationTest(TestOrganizations organizations, TestRecords records, MockMvc mockMvc,
                              ObjectMapper objectMapper, JdbcTemplate jdbc,
                              PartQuantityRepository partQuantityRepository,
                              PurchaseOrderRepository purchaseOrderRepository, PartLedgerService ledger,
                              EntityManager em, PlatformTransactionManager transactionManager,
                              ImportService importService, WorkflowRepository workflowRepository,
                              DemoDataService demoDataService) {
        this.organizations = organizations;
        this.records = records;
        this.api = new TestApi(mockMvc, objectMapper);
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
        this.partQuantityRepository = partQuantityRepository;
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.ledger = ledger;
        this.em = em;
        this.transaction = new TransactionTemplate(transactionManager);
        this.faults = new FaultInjector(jdbc);
        this.importService = importService;
        this.workflowRepository = workflowRepository;
        this.demoDataService = demoDataService;
    }

    @AfterEach
    void cleanUp() {
        faults.removeAll();
        created.forEach(organizations::delete);
        created.clear();
    }

    CommonData data() {
        CommonData data = organizations.create();
        created.add(data);
        return data;
    }

    // --- records ---

    /**
     * A part with {@code quantity} in stock and, when it is not 0, the movement that explains it.
     */
    Part part(Company company, double quantity) {
        Part part = records.part(company, null);
        if (quantity != 0) {
            jdbc.update("UPDATE part SET quantity = ? WHERE id = ?", quantity, part.getId());
            jdbc.update("INSERT INTO part_transaction (id, created_at, updated_at, company_id, part_id, quantity, "
                    + "origin, is_demo) VALUES (nextval('part_transaction_seq'), now(), now(), ?, ?, ?, "
                    + "'ADJUSTMENT', false)", company.getId(), part.getId(), quantity);
        }
        return part;
    }

    PurchaseOrder purchaseOrder(Company company) {
        PurchaseOrder purchaseOrder = new PurchaseOrder();
        purchaseOrder.setName(TestRecords.unique("P-16b PO"));
        purchaseOrder.setStatus(ApprovalStatus.PENDING);
        purchaseOrder.setCompany(company);
        return purchaseOrderRepository.saveAndFlush(purchaseOrder);
    }

    PartQuantity line(Part part, WorkOrder workOrder, PurchaseOrder purchaseOrder, double quantity) {
        PartQuantity line = new PartQuantity(part, workOrder, purchaseOrder, quantity);
        line.setCompany(part.getCompany());
        return partQuantityRepository.saveAndFlush(line);
    }

    // --- queries ---

    long count(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, Long.class, arguments);
    }

    double stock(Part part) {
        return jdbc.queryForObject("SELECT quantity FROM part WHERE id = ?", Double.class, part.getId());
    }

    /**
     * The movements of {@code part} after the one that explains its starting stock, oldest first, as
     * "quantity/origin/work order/purchase order/user" (an absent value is "-").
     */
    List<String> movements(Part part) {
        return jdbc.queryForList("SELECT quantity, origin, work_order_id, purchase_order_id, created_by "
                        + "FROM part_transaction WHERE part_id = ? AND NOT (origin = 'ADJUSTMENT' AND created_by "
                        + "IS NULL) ORDER BY id", part.getId()).stream()
                .map(row -> row.get("quantity") + "/" + row.get("origin") + "/" + orDash(row.get("work_order_id"))
                        + "/" + orDash(row.get("purchase_order_id")) + "/" + orDash(row.get("created_by")))
                .collect(Collectors.toList());
    }

    /**
     * Every movement of {@code part}, as JSON rows, to check that earlier movements never change. The identifiers
     * are not in the order of creation: the starting movement takes its own value of the sequence.
     */
    List<String> rows(Part part) {
        return jdbc.queryForList("SELECT row_to_json(t)::text FROM part_transaction t WHERE part_id = ? ORDER BY id",
                String.class, part.getId());
    }

    void assertStockIsTheSumOfItsMovements(Part part) {
        double sum = jdbc.queryForObject("SELECT COALESCE(SUM(quantity), 0) FROM part_transaction WHERE part_id = ?",
                Double.class, part.getId());
        assertEquals(stock(part), sum, 1e-9, "stock = sum of the movements of part " + part.getId());
    }

    static String orDash(Object value) {
        return value == null ? "-" : value.toString();
    }

    static String movement(double quantity, PartTransactionOrigin origin, WorkOrder workOrder,
                           PurchaseOrder purchaseOrder, User user) {
        return quantity + "/" + origin + "/" + (workOrder == null ? "-" : workOrder.getId()) + "/"
                + (purchaseOrder == null ? "-" : purchaseOrder.getId()) + "/" + (user == null ? "-" : user.getId());
    }

    Response call(MockHttpServletRequestBuilder request, User user) {
        return api.call(request, api.login(user));
    }

    Response send(MockHttpServletRequestBuilder request, String body, User user) {
        return call(request.contentType(MediaType.APPLICATION_JSON).content(body), user);
    }

    JsonNode json(Response response) {
        try {
            return objectMapper.readTree(response.body());
        } catch (Exception e) {
            throw new AssertionError("not JSON: " + response, e);
        }
    }

    Long addByReplacement(WorkOrder workOrder, Part part, User user) {
        Response response = send(patch("/part-quantities/work-order/" + workOrder.getId()), "[" + part.getId() + "]",
                user);
        assertEquals(200, response.status(), response.toString());
        for (JsonNode line : json(response)) {
            if (line.get("part").get("id").asLong() == part.getId()) return line.get("id").asLong();
        }
        throw new AssertionError("no line of part " + part.getId() + " in " + response);
    }

    Response changeLine(Long line, double quantity, User user) {
        return send(patch("/part-quantities/" + line), "{\"quantity\":" + quantity + "}", user);
    }

    // =====================================================================================================
    // Block 1 · Model, schema, single point, entries and exits
    // =====================================================================================================

    @Nested
    class Schema {

        @Test
        void ledgerTable_originAndPurchaseOrderAndDemo() {
            List<String> columns = jdbc.queryForList("SELECT column_name || ' ' || data_type || ' ' || is_nullable "
                    + "FROM information_schema.columns WHERE table_schema = current_schema() "
                    + "AND table_name = 'part_transaction' AND column_name IN ('origin', 'purchase_order_id', "
                    + "'is_demo') ORDER BY column_name", String.class);
            assertEquals(List.of("is_demo boolean NO", "origin character varying NO",
                    "purchase_order_id bigint YES"), columns);
        }

        @Test
        void foreignKeysOfTheLedger_noActionWithIndex() {
            // E-12 (PPB-21, PPB-22); before P-16b: work_order_id CASCADE without index, no purchase order
            List<String> keys = jdbc.queryForList("""
                    SELECT a.attname || ' -> ' || c.confrelid::regclass::text || ' ' || c.conname || ' '
                           || c.confdeltype::text || ' '
                           || (EXISTS (SELECT 1 FROM pg_index i WHERE i.indrelid = c.conrelid
                               AND i.indkey[0] = c.conkey[1] AND i.indisvalid AND i.indpred IS NULL))::text
                    FROM pg_constraint c
                    JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = c.conkey[1]
                    WHERE c.contype = 'f' AND c.conrelid = 'part_transaction'::regclass
                    ORDER BY 1""", String.class);
            assertEquals(List.of("company_id -> company fkib1h4krpiuuk1meixq7qf9g86 c true",
                    "part_id -> part fkbx2tggt11xc2ofuwui2xsxkg0 a true",
                    "purchase_order_id -> purchase_order fk_part_transaction_purchase_order a true",
                    "work_order_id -> work_order fkj99sa2uououd6s977aqyc0yri a true"), keys);
        }

        @Test
        void ca016_workOrderWithAMovement_notDeletedInTheDatabase() {
            // CA-PPB-016 (additional to the 12 criteria of the package, C-5)
            CommonData data = data();
            Part part = part(data.o1().company(), 5);
            WorkOrder workOrder = records.workOrder(data.o1().company(), null, Status.OPEN, false);
            addByReplacement(workOrder, part, data.o1().admin());

            DataIntegrityViolationException failure = assertThrows(DataIntegrityViolationException.class,
                    () -> jdbc.update("DELETE FROM work_order WHERE id = ?", workOrder.getId()));

            assertEquals("23503", ((SQLException) failure.getMostSpecificCause()).getSQLState());
            assertEquals(1, count("SELECT count(*) FROM part_transaction WHERE work_order_id = ?",
                    workOrder.getId()));
        }
    }

    @Nested
    class SinglePoint {

        @Test
        void outsideATransaction_rejected() {
            CommonData data = data();
            Part part = part(data.o1().company(), 1);

            assertThrows(IllegalTransactionStateException.class, () -> ledger.lock(part.getId()));
            assertThrows(IllegalTransactionStateException.class,
                    () -> ledger.record(part, 1, PartTransactionOrigin.ADJUSTMENT, Movement.by(null)));
        }

        @Test
        void partNotLocked_rejected_nothingWritten() {
            CommonData data = data();
            Part part = part(data.o1().company(), 1);

            assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> ledger.record(
                    em.find(Part.class, part.getId()), 1, PartTransactionOrigin.ADJUSTMENT, Movement.by(null))));

            assertEquals(1, stock(part));
            assertEquals(List.of(), movements(part));
        }

        @Test
        void lockedPart_stockAndMovementTogether_zeroAndNonStockLeaveNothing() {
            CommonData data = data();
            User admin = data.o1().admin();
            Part part = part(data.o1().company(), 1);
            Part nonStock = part(data.o1().company(), 0);
            jdbc.update("UPDATE part SET non_stock = true WHERE id = ?", nonStock.getId());

            transaction.executeWithoutResult(status -> {
                assertTrue(ledger.record(ledger.lock(part.getId()), 2, PartTransactionOrigin.ADJUSTMENT,
                        Movement.by(admin)).isPresent());
                assertTrue(ledger.record(ledger.lock(part.getId()), 0, PartTransactionOrigin.ADJUSTMENT,
                        Movement.by(admin)).isEmpty());
                assertTrue(ledger.record(ledger.lock(nonStock.getId()), 4, PartTransactionOrigin.ADJUSTMENT,
                        Movement.by(admin)).isEmpty());
            });

            assertEquals(3, stock(part));
            assertEquals(List.of(movement(2, PartTransactionOrigin.ADJUSTMENT, null, null, admin)), movements(part));
            assertEquals(0, stock(nonStock));
            assertEquals(List.of(), movements(nonStock));
            assertStockIsTheSumOfItsMovements(part);
        }
    }

    @Nested
    class EntriesAndExits {

        @Test
        void restocked_entryWithOriginRestock() {
            CommonData data = data();
            User admin = data.o1().admin();
            Part part = part(data.o1().company(), 1);

            Response response = send(post("/parts/" + part.getId() + "/restock"),
                    "{\"quantity\":2,\"description\":\"P-16b restock\"}", admin);

            assertEquals(200, response.status(), response.toString());
            assertEquals(3, stock(part));
            assertEquals(List.of(movement(2, PartTransactionOrigin.RESTOCK, null, null, admin)), movements(part));
            assertStockIsTheSumOfItsMovements(part);
        }

        @Test
        void addedByReplacement_exitWithOriginConsumptionAndTheWorkOrder() {
            CommonData data = data();
            User admin = data.o1().admin();
            Part part = part(data.o1().company(), 5);
            WorkOrder workOrder = records.workOrder(data.o1().company(), null, Status.OPEN, false);

            addByReplacement(workOrder, part, admin);

            assertEquals(4, stock(part));
            assertEquals(List.of(movement(-1, PartTransactionOrigin.CONSUMPTION, workOrder, null, admin)),
                    movements(part));
            assertStockIsTheSumOfItsMovements(part);
        }

        @Test
        void lineRaised_anotherExitForTheDifference() {
            CommonData data = data();
            User admin = data.o1().admin();
            Part part = part(data.o1().company(), 10);
            WorkOrder workOrder = records.workOrder(data.o1().company(), null, Status.OPEN, false);
            Long line = addByReplacement(workOrder, part, admin);

            Response response = changeLine(line, 3, admin);

            assertEquals(200, response.status(), response.toString());
            assertEquals(7, stock(part));
            assertEquals(List.of(movement(-1, PartTransactionOrigin.CONSUMPTION, workOrder, null, admin),
                    movement(-2, PartTransactionOrigin.CONSUMPTION, workOrder, null, admin)), movements(part));
            assertStockIsTheSumOfItsMovements(part);
        }

        @Test
        void moreThanTheStock_406AsBefore_nothingWritten() {
            CommonData data = data();
            Part part = part(data.o1().company(), 0);
            WorkOrder workOrder = records.workOrder(data.o1().company(), null, Status.OPEN, false);

            Response response = send(patch("/part-quantities/work-order/" + workOrder.getId()),
                    "[" + part.getId() + "]", data.o1().admin());

            assertEquals(406, response.status(), response.toString());
            assertTrue(response.body().contains("The quantity should not be negative"), response.toString());
            assertEquals(0, count("SELECT count(*) FROM part_quantity WHERE part_id = ?", part.getId()));
            assertEquals(List.of(), movements(part));
        }
    }

    // =====================================================================================================
    // Block 2 · Immutability and reversal of consumptions (PPB-04, PPB-16)
    // =====================================================================================================

    @Nested
    class Reversal {

        /** Part with stock 10, consumed 3 on an open work order (one added, then raised to 3): stock 7. */
        record Consumed(CommonData data, User admin, Part part, WorkOrder workOrder, Long line) {
        }

        Consumed consumed() {
            CommonData data = data();
            User admin = data.o1().admin();
            Part part = part(data.o1().company(), 10);
            WorkOrder workOrder = records.workOrder(data.o1().company(), null, Status.OPEN, false);
            Long line = addByReplacement(workOrder, part, admin);
            assertEquals(200, changeLine(line, 3, admin).status());
            assertEquals(7, stock(part));
            return new Consumed(data, admin, part, workOrder, line);
        }

        @Test
        void ca015_lineReduced_reversalForTheDifference_earlierMovementsUnchanged() {
            Consumed c = consumed();
            List<String> before = rows(c.part());

            Response response = changeLine(c.line(), 1, c.admin());

            assertEquals(200, response.status(), response.toString());
            assertEquals(9, stock(c.part()));
            assertTrue(rows(c.part()).containsAll(before), "earlier movements unchanged");
            assertEquals(movement(2, PartTransactionOrigin.REVERSAL, c.workOrder(), null, c.admin()),
                    movements(c.part()).get(2));
            assertStockIsTheSumOfItsMovements(c.part());
        }

        @Test
        void lineChangedToTheSameQuantity_nothingWritten() {
            Consumed c = consumed();
            List<String> before = rows(c.part());

            Response response = changeLine(c.line(), 3, c.admin());

            assertEquals(200, response.status(), response.toString());
            assertEquals(7, stock(c.part()));
            assertEquals(before, rows(c.part()));
        }

        @Test
        void ca044_lineDeleted_reversalOfTheLine_earlierMovementsUnchanged() {
            Consumed c = consumed();
            List<String> before = rows(c.part());

            Response response = call(delete("/part-quantities/" + c.line()), c.admin());

            assertEquals(200, response.status(), response.toString());
            assertEquals(10, stock(c.part()));
            assertTrue(rows(c.part()).containsAll(before), "earlier movements unchanged");
            assertEquals(movement(3, PartTransactionOrigin.REVERSAL, c.workOrder(), null, c.admin()),
                    movements(c.part()).get(2));
            assertStockIsTheSumOfItsMovements(c.part());
        }

        @Test
        void ca044_lineRemovedByReplacement_reversalOfTheLine_earlierMovementsUnchanged() {
            Consumed c = consumed();
            List<String> before = rows(c.part());

            Response response = send(patch("/part-quantities/work-order/" + c.workOrder().getId()), "[]",
                    c.admin());

            assertEquals(200, response.status(), response.toString());
            assertEquals(0, count("SELECT count(*) FROM part_quantity WHERE id = ?", c.line()));
            assertEquals(10, stock(c.part()));
            assertTrue(rows(c.part()).containsAll(before), "earlier movements unchanged");
            assertEquals(movement(3, PartTransactionOrigin.REVERSAL, c.workOrder(), null, c.admin()),
                    movements(c.part()).get(2));
            assertStockIsTheSumOfItsMovements(c.part());
        }

        @Test
        void ca135_reversalFailsOnDelete_nothingChanged() {
            Consumed c = consumed();
            List<String> before = rows(c.part());

            Response response;
            try (FaultInjector.Fault fault = faults.failOn("part_transaction", FaultInjector.Operation.INSERT)) {
                response = call(delete("/part-quantities/" + c.line()), c.admin());
                assertEquals(1, fault.hits());
            }

            assertFalse(response.is2xx(), response.toString());
            assertEquals(1, count("SELECT count(*) FROM part_quantity WHERE id = ?", c.line()));
            assertEquals(7, stock(c.part()));
            assertEquals(before, rows(c.part()));
        }

        @Test
        void ca135_reversalFailsOnReplacement_nothingChanged() {
            Consumed c = consumed();
            List<String> before = rows(c.part());

            Response response;
            try (FaultInjector.Fault fault = faults.failOn("part_transaction", FaultInjector.Operation.INSERT)) {
                response = send(patch("/part-quantities/work-order/" + c.workOrder().getId()), "[]", c.admin());
                assertEquals(1, fault.hits());
            }

            assertFalse(response.is2xx(), response.toString());
            assertEquals(1, count("SELECT count(*) FROM part_quantity WHERE id = ?", c.line()));
            assertEquals(7, stock(c.part()));
            assertEquals(before, rows(c.part()));
        }

        @Test
        void auditRecordFailsAfterTheReversal_nothingChanged_noWebhook() throws InterruptedException {
            // PPB-25: the reversal, the removal and its audit record are committed together; the webhook of the change
            // of stock leaves only after the commit (C-3)
            Consumed c = consumed();
            records.endpoint(c.data().o1().company(), WebhookEvent.PART_QUANTITY_CHANGED);
            // the webhooks of the committed consumptions are asynchronous: they may still reach this endpoint
            Thread.sleep(2000);
            webhookDouble.reset();
            List<String> before = rows(c.part());

            Response response;
            try (FaultInjector.Fault fault = faults.failOn("audit_log", FaultInjector.Operation.INSERT)) {
                response = call(delete("/part-quantities/" + c.line()), c.admin());
                assertEquals(1, fault.hits());
            }
            Thread.sleep(2000);

            assertFalse(response.is2xx(), response.toString());
            assertEquals(1, count("SELECT count(*) FROM part_quantity WHERE id = ?", c.line()));
            assertEquals(7, stock(c.part()));
            assertEquals(before, rows(c.part()));
            assertEquals(List.of(), webhookDouble.calls("PART_QUANTITY_CHANGED"));
        }

        @Test
        void reductionFails_lineKept() {
            Consumed c = consumed();

            try (FaultInjector.Fault fault = faults.failOn("part_transaction", FaultInjector.Operation.INSERT)) {
                assertFalse(changeLine(c.line(), 1, c.admin()).is2xx());
            }

            assertEquals(3.0, jdbc.queryForObject("SELECT quantity FROM part_quantity WHERE id = ?", Double.class,
                    c.line()));
            assertEquals(7, stock(c.part()));
        }

        @Test
        void lineCreatedByPost_consumed() {
            // D-P16b-6; before P-16b: the line was created without consumption
            CommonData data = data();
            User admin = data.o1().admin();
            Part part = part(data.o1().company(), 5);
            WorkOrder workOrder = records.workOrder(data.o1().company(), null, Status.OPEN, false);

            Response response = send(post("/part-quantities"), "{\"part\":{\"id\":" + part.getId()
                    + "},\"workOrder\":{\"id\":" + workOrder.getId() + "},\"quantity\":3}", admin);

            assertEquals(200, response.status(), response.toString());
            assertEquals(2, stock(part));
            assertEquals(List.of(movement(-3, PartTransactionOrigin.CONSUMPTION, workOrder, null, admin)),
                    movements(part));
        }

        @Test
        void lineWithoutMovementDeleted_200_reversalOfTheLine() {
            // before P-16b: 500 (no movement to delete)
            CommonData data = data();
            User admin = data.o1().admin();
            Part part = part(data.o1().company(), 5);
            WorkOrder workOrder = records.workOrder(data.o1().company(), null, Status.OPEN, false);
            PartQuantity line = line(part, workOrder, null, 3);

            Response response = call(delete("/part-quantities/" + line.getId()), admin);

            assertEquals(200, response.status(), response.toString());
            assertEquals(8, stock(part));
            assertEquals(List.of(movement(3, PartTransactionOrigin.REVERSAL, workOrder, null, admin)),
                    movements(part));
        }

        @Test
        void replacementAddingTwoParts_secondWithoutStock_406_nothingConsumed() {
            CommonData data = data();
            Part enough = part(data.o1().company(), 5);
            Part none = part(data.o1().company(), 0);
            WorkOrder workOrder = records.workOrder(data.o1().company(), null, Status.OPEN, false);

            Response response = send(patch("/part-quantities/work-order/" + workOrder.getId()),
                    "[" + enough.getId() + "," + none.getId() + "]", data.o1().admin());

            assertEquals(406, response.status(), response.toString());
            assertEquals(5, stock(enough));
            assertEquals(List.of(), movements(enough));
            assertEquals(0, count("SELECT count(*) FROM part_quantity WHERE work_order_id = ?", workOrder.getId()));
        }

        @Test
        void nonStockLineRemoved_nothingWritten() {
            CommonData data = data();
            Part part = part(data.o1().company(), 0);
            jdbc.update("UPDATE part SET non_stock = true, quantity = 4 WHERE id = ?", part.getId());
            WorkOrder workOrder = records.workOrder(data.o1().company(), null, Status.OPEN, false);
            Long line = addByReplacement(workOrder, part, data.o1().admin());

            assertEquals(200, call(delete("/part-quantities/" + line), data.o1().admin()).status());

            assertEquals(4, stock(part));
            assertEquals(List.of(), movements(part));
        }

        @Test
        void movementChangedThroughTheEntity_notWritten() {
            Consumed c = consumed();
            List<String> before = rows(c.part());

            transaction.executeWithoutResult(status -> {
                PartTransaction movement = em.createQuery("SELECT t FROM PartTransaction t WHERE t.part.id = :part",
                        PartTransaction.class).setParameter("part", c.part().getId()).getResultList().get(0);
                movement.setQuantity(100);
                movement.setDescription("changed");
                em.flush();
            });

            assertEquals(before, rows(c.part()));
        }
    }

    // =====================================================================================================
    // Block 3 · Creation, adjustment and import (PPB-30, D-P16b-5, A-10, C-1)
    // =====================================================================================================

    @Nested
    class CreationAdjustmentImport {

        PartImportDTO row(Part part, double quantity, String nonStock, String category) {
            return PartImportDTO.builder().id(part == null ? null : part.getId())
                    .name(part == null ? TestRecords.unique("P-16b imported") : part.getName())
                    .quantity(quantity).nonStock(nonStock).category(category).build();
        }

        Part byName(Company company, String name) {
            Long id = jdbc.queryForObject("SELECT id FROM part WHERE company_id = ? AND name = ?", Long.class,
                    company.getId(), name);
            return em.find(Part.class, id);
        }

        @Test
        void ca081_createdWithStock_entryWithOriginCreationByTheUser() {
            CommonData data = data();
            User admin = data.o1().admin();

            Response response = send(post("/parts"), "{\"name\":\"" + TestRecords.unique("P-16b P8")
                    + "\",\"quantity\":5}", admin);

            assertEquals(200, response.status(), response.toString());
            Part part = em.find(Part.class, json(response).get("id").asLong());
            assertEquals(5, stock(part));
            assertEquals(List.of(movement(5, PartTransactionOrigin.CREATION, null, null, admin)), movements(part));
            assertNotNull(jdbc.queryForObject("SELECT created_at FROM part_transaction WHERE part_id = ?",
                    java.sql.Timestamp.class, part.getId()));
            assertStockIsTheSumOfItsMovements(part);
        }

        @Test
        void createdWithoutStock_noMovement() {
            CommonData data = data();

            Response response = send(post("/parts"), "{\"name\":\"" + TestRecords.unique("P-16b part") + "\"}",
                    data.o1().admin());

            assertEquals(200, response.status(), response.toString());
            Part part = em.find(Part.class, json(response).get("id").asLong());
            assertEquals(0, stock(part));
            assertEquals(List.of(), movements(part));
        }

        @Test
        void nonStockCreatedWithStock_bornAtZero_noMovement() {
            // D-P16b-5; before P-16b: born with 5
            CommonData data = data();

            Response response = send(post("/parts"), "{\"name\":\"" + TestRecords.unique("P-16b part")
                    + "\",\"nonStock\":true,\"quantity\":5}", data.o1().admin());

            assertEquals(200, response.status(), response.toString());
            Part part = em.find(Part.class, json(response).get("id").asLong());
            assertEquals(0, stock(part));
            assertEquals(List.of(), movements(part));
        }

        @Test
        void ca083_patchedWithAnotherQuantity_adjustmentForTheDifference_earlierUnchanged() {
            CommonData data = data();
            User admin = data.o1().admin();
            Part part = part(data.o1().company(), 9);
            List<String> before = rows(part);

            Response response = send(patch("/parts/" + part.getId()), "{\"name\":\"" + part.getName()
                    + "\",\"quantity\":7}", admin);

            assertEquals(200, response.status(), response.toString());
            assertEquals(7, stock(part));
            assertEquals(List.of(movement(-2, PartTransactionOrigin.ADJUSTMENT, null, null, admin)),
                    movements(part));
            assertTrue(rows(part).containsAll(before), "earlier movements unchanged");
            assertStockIsTheSumOfItsMovements(part);
        }

        @Test
        void patchedWithTheSameQuantity_noMovement() {
            CommonData data = data();
            Part part = part(data.o1().company(), 9);

            Response response = send(patch("/parts/" + part.getId()), "{\"name\":\"" + part.getName()
                    + "\",\"quantity\":9,\"description\":\"P-16b\"}", data.o1().admin());

            assertEquals(200, response.status(), response.toString());
            assertEquals(9, stock(part));
            assertEquals(List.of(), movements(part));
        }

        @Test
        void patchedWithoutQuantity_stockSetToZero_withAnAdjustment() {
            // D-P16b-11: kept as before (the quantity of the DTO is a primitive), now with its movement
            CommonData data = data();
            User admin = data.o1().admin();
            Part part = part(data.o1().company(), 9);

            Response response = send(patch("/parts/" + part.getId()), "{\"name\":\"" + part.getName() + "\"}",
                    admin);

            assertEquals(200, response.status(), response.toString());
            assertEquals(0, stock(part));
            assertEquals(List.of(movement(-9, PartTransactionOrigin.ADJUSTMENT, null, null, admin)),
                    movements(part));
            assertStockIsTheSumOfItsMovements(part);
        }

        @Test
        void patchedToNonStockWithAnotherQuantity_quantityKept_noMovement() {
            CommonData data = data();
            Part part = part(data.o1().company(), 9);

            Response response = send(patch("/parts/" + part.getId()), "{\"name\":\"" + part.getName()
                    + "\",\"nonStock\":true,\"quantity\":3}", data.o1().admin());

            assertEquals(200, response.status(), response.toString());
            assertEquals(9, stock(part));
            assertTrue(jdbc.queryForObject("SELECT non_stock FROM part WHERE id = ?", Boolean.class, part.getId()));
            assertEquals(List.of(), movements(part));
        }

        @Test
        void ca087_adjustmentFails_nothingChanged() {
            CommonData data = data();
            Part part = part(data.o1().company(), 7);
            List<String> before = rows(part);

            Response response;
            try (FaultInjector.Fault fault = faults.failOn("part_transaction", FaultInjector.Operation.INSERT)) {
                response = send(patch("/parts/" + part.getId()), "{\"name\":\"" + part.getName()
                        + "\",\"quantity\":4,\"description\":\"P-16b changed\"}", data.o1().admin());
                assertEquals(1, fault.hits());
            }

            assertFalse(response.is2xx(), response.toString());
            assertEquals(7, stock(part));
            assertEquals(before, rows(part));
            assertNull(jdbc.queryForObject("SELECT description FROM part WHERE id = ?", String.class, part.getId()));
        }

        @Test
        void ca084_imported_movementForTheDifferenceOnly_byWhoImports() {
            CommonData data = data();
            Company company = data.o1().company();
            User admin = data.o1().admin();
            Part p8 = part(company, 7);
            Part p9 = part(company, 3);
            PartImportDTO created = row(null, 4, null, null);

            importService.importParts(List.of(row(p8, 12, null, null), row(p9, 3, null, null), created), company,
                    admin);

            assertEquals(12, stock(p8));
            assertEquals(List.of(movement(5, PartTransactionOrigin.IMPORT, null, null, admin)), movements(p8));
            assertEquals(3, stock(p9));
            assertEquals(List.of(), movements(p9));
            Part newPart = byName(company, created.getName());
            assertEquals(List.of(movement(4, PartTransactionOrigin.IMPORT, null, null, admin)), movements(newPart));
            assertStockIsTheSumOfItsMovements(p8);
            assertStockIsTheSumOfItsMovements(newPart);
        }

        @Test
        void importedThroughTheApi_movementByWhoAskedForIt() throws Exception {
            // C-1: the import runs asynchronously, without the user of the request; the actor is passed down
            CommonData data = data();
            User admin = data.o1().admin();
            Part part = part(data.o1().company(), 2);

            Response response = send(post("/import/parts").param("uuid", "p16b-import"), "[{\"id\":"
                    + part.getId() + ",\"name\":\"" + part.getName() + "\",\"quantity\":6}]", admin);
            assertEquals(200, response.status(), response.toString());

            long deadline = System.currentTimeMillis() + 20_000;
            while (stock(part) != 6 && System.currentTimeMillis() < deadline) Thread.sleep(50);
            assertEquals(List.of(movement(4, PartTransactionOrigin.IMPORT, null, null, admin)), movements(part));
        }

        @Test
        void a10_nonStockReadFromItsOwnColumn() {
            // before P-16b: read from the category column
            CommonData data = data();
            Company company = data.o1().company();
            Part ownColumn = part(company, 0);
            Part categoryColumn = part(company, 0);

            importService.importParts(List.of(row(ownColumn, 0, "true", null), row(categoryColumn, 0, null,
                    "true")), company, data.o1().admin());

            assertTrue(jdbc.queryForObject("SELECT non_stock FROM part WHERE id = ?", Boolean.class,
                    ownColumn.getId()));
            assertFalse(jdbc.queryForObject("SELECT non_stock FROM part WHERE id = ?", Boolean.class,
                    categoryColumn.getId()));
        }

        @Test
        void ca127_nonStockImportedWithStock_noChange_noMovement() {
            CommonData data = data();
            Part part = part(data.o1().company(), 0);
            jdbc.update("UPDATE part SET non_stock = true WHERE id = ?", part.getId());

            importService.importParts(List.of(row(part, 10, "true", null)), data.o1().company(), data.o1().admin());

            assertEquals(0, stock(part));
            assertEquals(List.of(), movements(part));
        }
    }

    // =====================================================================================================
    // Block 4 · Purchase orders: one approval by every route (PPB-30, D-13, D-P16b-2, D-P16b-3, C-2, C-3)
    // =====================================================================================================

    @Nested
    class PurchaseOrders {

        Workflow approvingWorkflow(Company company, WFMainCondition main) {
            WorkflowAction action = new WorkflowAction();
            action.setCompany(company);
            action.setPurchaseOrderAction(PurchaseOrderAction.APPROVE);
            Workflow workflow = new Workflow();
            workflow.setCompany(company);
            workflow.setTitle(TestRecords.unique("P-16b workflow"));
            workflow.setMainCondition(main);
            workflow.setAction(action);
            workflow.setSecondaryConditions(new ArrayList<>());
            return workflowRepository.saveAndFlush(workflow);
        }

        String status(Long purchaseOrder) {
            return ApprovalStatus.values()[jdbc.queryForObject("SELECT status FROM purchase_order WHERE id = ?",
                    Integer.class, purchaseOrder)].name();
        }

        Response respond(PurchaseOrder purchaseOrder, User user) {
            return call(patch("/purchase-orders/" + purchaseOrder.getId() + "/respond").param("approved", "true"),
                    user);
        }

        @Test
        void ca082_approvedDirectly_oneEntryPerLineWithThePurchaseOrder() {
            CommonData data = data();
            User admin = data.o1().admin();
            Part p8 = part(data.o1().company(), 0);
            Part p9 = part(data.o1().company(), 1);
            PurchaseOrder po5 = purchaseOrder(data.o1().company());
            line(p8, null, po5, 4);
            line(p9, null, po5, 2);

            Response response = respond(po5, admin);

            assertEquals(200, response.status(), response.toString());
            assertEquals("APPROVED", status(po5.getId()));
            assertEquals(4, stock(p8));
            assertEquals(3, stock(p9));
            assertEquals(List.of(movement(4, PartTransactionOrigin.PURCHASE_ORDER, null, po5, admin)),
                    movements(p8));
            assertEquals(List.of(movement(2, PartTransactionOrigin.PURCHASE_ORDER, null, po5, admin)),
                    movements(p9));
            assertStockIsTheSumOfItsMovements(p8);
            assertStockIsTheSumOfItsMovements(p9);
        }

        @Test
        void ca127_nonStockPartApproved_noChange_noMovement_otherLinesAdded() {
            CommonData data = data();
            Part p11 = part(data.o1().company(), 0);
            jdbc.update("UPDATE part SET non_stock = true WHERE id = ?", p11.getId());
            Part stocked = part(data.o1().company(), 0);
            PurchaseOrder purchaseOrder = purchaseOrder(data.o1().company());
            line(p11, null, purchaseOrder, 2);
            line(stocked, null, purchaseOrder, 1);

            assertEquals(200, respond(purchaseOrder, data.o1().admin()).status());

            assertEquals(0, stock(p11));
            assertEquals(List.of(), movements(p11));
            assertEquals(1, stock(stocked));
        }

        @Test
        void ca128_createdWithALineAndApprovedByAWorkflow_sameEffectAsTheDirectRoute_webhookAfterCommit() {
            // D-P16b-2 (b), C-3; before P-16b: approved on creation without lines and without stock
            CommonData data = data();
            User admin = data.o1().admin();
            Part p8 = part(data.o1().company(), 0);
            approvingWorkflow(data.o1().company(), WFMainCondition.PURCHASE_ORDER_CREATED);
            records.endpoint(data.o1().company(), WebhookEvent.PART_QUANTITY_CHANGED);

            Response response = send(post("/purchase-orders"), "{\"name\":\"" + TestRecords.unique("P-16b PO6")
                    + "\",\"partQuantities\":[{\"part\":{\"id\":" + p8.getId() + "},\"quantity\":2}]}", admin);

            assertEquals(200, response.status(), response.toString());
            JsonNode body = json(response);
            Long po6 = body.get("id").asLong();
            assertEquals("APPROVED", body.get("status").asText());
            assertEquals("APPROVED", status(po6));
            assertEquals(1, body.get("partQuantities").size());
            assertEquals(2, stock(p8));
            assertEquals(List.of(movement(2, PartTransactionOrigin.PURCHASE_ORDER, null,
                    em.find(PurchaseOrder.class, po6), admin)), movements(p8));
            webhookDouble.awaitCalls("PART_QUANTITY_CHANGED", 1, Duration.ofSeconds(10));
        }

        @Test
        void createdWithoutLines_pendingAsBefore() {
            CommonData data = data();

            Response response = send(post("/purchase-orders"), "{\"name\":\"" + TestRecords.unique("P-16b PO")
                    + "\"}", data.o1().admin());

            assertEquals(200, response.status(), response.toString());
            assertEquals("PENDING", json(response).get("status").asText());
            assertEquals(0, json(response).get("partQuantities").size());
        }

        @Test
        void createdWithALineOfAnotherOrganization_403_nothingCreated() {
            CommonData data = data();
            Part foreign = part(data.o2().company(), 0);
            String name = TestRecords.unique("P-16b PO");

            Response response = send(post("/purchase-orders"), "{\"name\":\"" + name + "\",\"partQuantities\":"
                    + "[{\"part\":{\"id\":" + foreign.getId() + "},\"quantity\":2}]}", data.o1().admin());

            assertEquals(403, response.status(), response.toString());
            assertEquals(0, count("SELECT count(*) FROM purchase_order WHERE name = ?", name));
            assertEquals(0, count("SELECT count(*) FROM part_quantity WHERE part_id = ?", foreign.getId()));
        }

        @Test
        void approvedByAWorkflowOnUpdate_stockAdded_thenNotAnsweredAgain() {
            // D-13; before P-16b: approved without stock
            CommonData data = data();
            User admin = data.o1().admin();
            Part part = part(data.o1().company(), 0);
            PurchaseOrder purchaseOrder = purchaseOrder(data.o1().company());
            line(part, null, purchaseOrder, 2);
            approvingWorkflow(data.o1().company(), WFMainCondition.PURCHASE_ORDER_UPDATED);

            Response response = send(patch("/purchase-orders/" + purchaseOrder.getId()), "{\"name\":\""
                    + purchaseOrder.getName() + "\"}", admin);

            assertEquals(200, response.status(), response.toString());
            assertEquals("APPROVED", json(response).get("status").asText());
            assertEquals(2, stock(part));
            assertEquals(List.of(movement(2, PartTransactionOrigin.PURCHASE_ORDER, null, purchaseOrder, admin)),
                    movements(part));
            assertEquals(409, respond(purchaseOrder, admin).status());
            assertEquals(2, stock(part));
        }
    }

    // =====================================================================================================
    // Block 5 · Demo data and demo purge (PPB-20 d, PPB-30, D-3, D-P16b-1, D-P16b-7, D-P16b-8)
    // =====================================================================================================

    @Nested
    class DemoData {

        Response purge(User user) {
            return call(delete("/demo/demo-data"), user);
        }

        /** A demo part with {@code quantity} in stock, explained by a demo movement (as the demo data are born). */
        Part demoPart(Company company, double quantity) {
            Part part = records.part(company, null);
            jdbc.update("UPDATE part SET is_demo = true, quantity = ? WHERE id = ?", quantity, part.getId());
            jdbc.update("INSERT INTO part_transaction (id, created_at, updated_at, company_id, part_id, quantity, "
                    + "origin, is_demo) VALUES (nextval('part_transaction_seq'), now(), now(), ?, ?, ?, "
                    + "'CREATION', true)", company.getId(), part.getId(), quantity);
            return part;
        }

        PurchaseOrder approvedDemoPurchaseOrder(Part part, double quantity, User admin) {
            PurchaseOrder purchaseOrder = purchaseOrder(part.getCompany());
            PartQuantity line = line(part, null, purchaseOrder, quantity);
            assertEquals(200, call(patch("/purchase-orders/" + purchaseOrder.getId() + "/respond")
                    .param("approved", "true"), admin).status());
            jdbc.update("UPDATE purchase_order SET is_demo = true WHERE id = ?", purchaseOrder.getId());
            jdbc.update("UPDATE part_quantity SET is_demo = true WHERE id = ?", line.getId());
            return purchaseOrder;
        }

        @Test
        void ca086_partNotDemoSuppliedByAnApprovedDemoPurchaseOrder_purchaseOrderPurged_stockCompensated() {
            // before P-16b: the demo purchase order was kept and its 3 stayed in the stock
            CommonData data = data();
            User admin = data.o1().admin();
            Part p10 = part(data.o1().company(), 0);
            PurchaseOrder purchaseOrder = approvedDemoPurchaseOrder(p10, 3, admin);
            assertEquals(3, stock(p10));

            Response response = purge(admin);

            assertEquals(200, response.status(), response.toString());
            assertEquals(0, count("SELECT count(*) FROM purchase_order WHERE id = ?", purchaseOrder.getId()));
            assertEquals(0, count("SELECT count(*) FROM part_quantity WHERE purchase_order_id = ?",
                    purchaseOrder.getId()));
            assertEquals(0, stock(p10));
            assertEquals(List.of(movement(3, PartTransactionOrigin.PURCHASE_ORDER, null, null, admin),
                    movement(-3, PartTransactionOrigin.DEMO_PURGE, null, null, admin)), movements(p10));
            assertEquals(List.of("Demo purchase order " + purchaseOrder.getName() + " (#" + purchaseOrder.getId()
                            + ") purged", "[demo purchase order " + purchaseOrder.getName() + " (#"
                            + purchaseOrder.getId() + ") purged]"),
                    jdbc.queryForList("SELECT description FROM part_transaction WHERE part_id = ? AND created_by "
                            + "IS NOT NULL ORDER BY origin", String.class, p10.getId()));
            assertStockIsTheSumOfItsMovements(p10);
        }

        @Test
        void partNotDemoConsumedOnADemoWorkOrder_workOrderPurged_consumptionCompensated() {
            // before P-16b: the movement fell with the work order (CASCADE) and the stock stayed at 4
            CommonData data = data();
            User admin = data.o1().admin();
            Part part = part(data.o1().company(), 5);
            WorkOrder workOrder = records.workOrder(data.o1().company(), null, Status.OPEN, true);
            addByReplacement(workOrder, part, admin);
            String folio = TestRecords.unique("WO");
            jdbc.update("UPDATE work_order SET custom_id = ? WHERE id = ?", folio, workOrder.getId());

            Response response = purge(admin);

            assertEquals(200, response.status(), response.toString());
            assertEquals(0, count("SELECT count(*) FROM work_order WHERE id = ?", workOrder.getId()));
            assertEquals(5, stock(part));
            assertEquals(List.of(movement(-1, PartTransactionOrigin.CONSUMPTION, null, null, admin),
                    movement(1, PartTransactionOrigin.DEMO_PURGE, null, null, admin)), movements(part));
            assertTrue(jdbc.queryForObject("SELECT description FROM part_transaction WHERE part_id = ? "
                    + "AND origin = 'CONSUMPTION'", String.class, part.getId())
                    .contains("[demo work order " + folio + " purged]"));
            assertStockIsTheSumOfItsMovements(part);
        }

        @Test
        void demoPartConsumedOnADemoWorkOrder_purgedWithItsMovements() {
            // before P-16b: purged too (the movement fell with the work order)
            CommonData data = data();
            User admin = data.o1().admin();
            Part part = demoPart(data.o1().company(), 5);
            WorkOrder workOrder = records.workOrder(data.o1().company(), null, Status.OPEN, true);
            Long line = addByReplacement(workOrder, part, admin);
            jdbc.update("UPDATE part_quantity SET is_demo = true WHERE id = ?", line);

            Response response = purge(admin);

            assertEquals(200, response.status(), response.toString());
            assertEquals(0, count("SELECT count(*) FROM part WHERE id = ?", part.getId()));
            assertEquals(0, count("SELECT count(*) FROM part_transaction WHERE part_id = ?", part.getId()));
        }

        @Test
        void demoPartAdjustedByAUser_409DemoDataInUse_nothingDeleted() {
            // P-18 (E-5, PPB-20 d): before, 409 RECORD_IN_USE from the global handler
            // D-P16-6 (as a demo part restocked): a movement that is not demo, of a demo part, rejects the purge
            CommonData data = data();
            User admin = data.o1().admin();
            Part part = demoPart(data.o1().company(), 5);
            assertEquals(200, send(patch("/parts/" + part.getId()), "{\"name\":\"" + part.getName()
                    + "\",\"quantity\":2}", admin).status());

            Response response = purge(admin);

            assertEquals(409, response.status(), response.toString());
            assertTrue(response.body().contains("DEMO_DATA_IN_USE"), response.toString());
            assertEquals(1, count("SELECT count(*) FROM part WHERE id = ?", part.getId()));
            assertEquals(2, count("SELECT count(*) FROM part_transaction WHERE part_id = ?", part.getId()));
        }

        @Test
        void compensationAboveTheStock_406AsAnyExit_nothingPurged() {
            // D-P16b-8: what the demo purchase order added was already consumed on a real work order
            CommonData data = data();
            User admin = data.o1().admin();
            Part part = part(data.o1().company(), 0);
            PurchaseOrder purchaseOrder = approvedDemoPurchaseOrder(part, 3, admin);
            WorkOrder workOrder = records.workOrder(data.o1().company(), null, Status.OPEN, false);
            Long line = addByReplacement(workOrder, part, admin);
            assertEquals(200, changeLine(line, 2, admin).status());
            List<String> before = rows(part);

            Response response = purge(admin);

            assertEquals(406, response.status(), response.toString());
            assertEquals(1, count("SELECT count(*) FROM purchase_order WHERE id = ?", purchaseOrder.getId()));
            assertEquals(1, stock(part));
            assertEquals(before, rows(part));
        }

        @Test
        void factoryDemoData_bornWithTheirMovements_stockIsTheSum_thenPurged() throws InterruptedException {
            // D-P16b-7; before P-16b: the demo parts had stock without movement and their line did not consume
            CommonData data = data();
            User admin = data.o1().admin();
            Long companyId = data.o1().company().getId();
            demoDataService.createDemoData(admin, data.o1().company());
            // createDemoData is asynchronous
            for (int i = 0; i < 150 && count("SELECT count(*) FROM request WHERE company_id = ?", companyId) < 1; i++)
                Thread.sleep(200);

            assertEquals(List.of("Air Filter 9 CREATION:10 CONSUMPTION:-1", "Fuse 2A 20 CREATION:20",
                    "V-Belt 5 CREATION:5"), jdbc.queryForList("""
                    SELECT p.name || ' ' || p.quantity || ' ' || string_agg(t.origin || ':' || t.quantity, ' '
                           ORDER BY t.id)
                    FROM part p JOIN part_transaction t ON t.part_id = p.id
                    WHERE p.company_id = ? AND p.is_demo AND t.is_demo AND t.created_by = ?
                    GROUP BY p.name, p.quantity ORDER BY p.name""", String.class, companyId, admin.getId()));
            assertEquals(List.of(), jdbc.queryForList("SELECT p.id FROM part p LEFT JOIN part_transaction t "
                    + "ON t.part_id = p.id WHERE p.company_id = ? GROUP BY p.id, p.quantity "
                    + "HAVING abs(p.quantity - COALESCE(SUM(t.quantity), 0)) > 1e-9", Long.class, companyId));

            Response response = purge(admin);

            assertEquals(200, response.status(), response.toString());
            assertEquals(0, count("SELECT count(*) FROM part WHERE company_id = ?", companyId));
            assertEquals(0, count("SELECT count(*) FROM part_transaction WHERE company_id = ?", companyId));
        }
    }

    // =====================================================================================================
    // Block 6 · Export of the ledger (E-14, D-4)
    // =====================================================================================================

    @Nested
    class Export {

        /** Exports the ledger of the organization of {@code user} and returns its lines. */
        List<String> export(User user) {
            Response response = call(get("/export/part-transactions").param("uuid",
                    TestRecords.unique("p16b-export")), user);
            assertEquals(200, response.status(), response.toString());
            String path = (String) storageDouble.awaitCalls("generateSignedUrl", 1, Duration.ofSeconds(20)).get(0)
                    .arguments().get(0);
            return new String(storageDouble.download(path), StandardCharsets.UTF_8).lines().toList();
        }

        /** The last four cells of the row of the movement with {@code origin}, joined by "|". */
        String lastCells(List<String> lines, String origin) {
            return lines.subList(1, lines.size()).stream().map(line -> line.split(",", -1))
                    .filter(cells -> cells[cells.length - 4].equals(origin))
                    .map(cells -> String.join("|", List.of(cells).subList(cells.length - 4, cells.length)))
                    .findFirst().orElseThrow(() -> new AssertionError("no " + origin + " in " + lines));
        }

        void setLanguage(Company company, Language language) {
            transaction.executeWithoutResult(status -> em.find(CompanySettings.class,
                    company.getCompanySettings().getId()).getGeneralPreferences().setLanguage(language));
        }

        PurchaseOrder approved(Part part, double quantity, User admin) {
            PurchaseOrder purchaseOrder = purchaseOrder(part.getCompany());
            line(part, null, purchaseOrder, quantity);
            assertEquals(200, call(patch("/purchase-orders/" + purchaseOrder.getId() + "/respond")
                    .param("approved", "true"), admin).status());
            return purchaseOrder;
        }

        @Test
        void english_originUserAndPurchaseOrderAtTheEnd() {
            // before P-16b: no origin, user nor purchase order columns
            CommonData data = data();
            User admin = data.o1().admin();
            Part part = part(data.o1().company(), 1);
            PurchaseOrder purchaseOrder = approved(part, 2, admin);
            String name = jdbc.queryForObject("SELECT first_name || ' ' || last_name FROM own_user WHERE id = ?",
                    String.class, admin.getId());

            List<String> lines = export(admin);

            assertTrue(lines.get(0).endsWith(",Description,Date created,Origin,User,Purchase order ID,Purchase order"),
                    lines.get(0));
            assertEquals("Purchase order|" + name + "|" + purchaseOrder.getId() + "|" + purchaseOrder.getName(),
                    lastCells(lines, "Purchase order"));
            assertEquals("Adjustment|||", lastCells(lines, "Adjustment"), "a movement without user");
        }

        @Test
        void spanish_headersAndOrigins() {
            CommonData data = data();
            User admin = data.o1().admin();
            setLanguage(data.o1().company(), Language.ES);
            Part part = part(data.o1().company(), 5);
            WorkOrder workOrder = records.workOrder(data.o1().company(), null, Status.OPEN, false);
            Long line = addByReplacement(workOrder, part, admin);
            assertEquals(200, call(delete("/part-quantities/" + line), admin).status());
            approved(part, 2, admin);

            List<String> lines = export(admin);

            assertTrue(lines.get(0).endsWith(",Origen,Usuario,ID de orden de compra,Orden de compra"), lines.get(0));
            for (String origin : List.of("Ajuste", "Consumo", "Reversión", "Orden de compra"))
                lastCells(lines, origin);
        }
    }

    // =====================================================================================================
    // Block 7 · Permissions of part-quantities (E-15, TD-P12-01, PPB-27): 404, then 403, then 409
    // =====================================================================================================

    @Nested
    class PartQuantityPermissions {

        String body(Part part, WorkOrder workOrder, PurchaseOrder purchaseOrder, double quantity) {
            return "{\"part\":{\"id\":" + part.getId() + "}"
                    + (workOrder == null ? "" : ",\"workOrder\":{\"id\":" + workOrder.getId() + "}")
                    + (purchaseOrder == null ? "" : ",\"purchaseOrder\":{\"id\":" + purchaseOrder.getId() + "}")
                    + ",\"quantity\":" + quantity + "}";
        }

        @Test
        void technicianWhoCannotEditTheWorkOrder_postAndPatch403_nothingChanged_get200() {
            // before P-16b: 200 for the three (no check)
            CommonData data = data();
            User technician = data.o1().technician();
            Part part = part(data.o1().company(), 10);
            WorkOrder workOrder = records.workOrder(data.o1().company(), null, Status.OPEN, false);
            PartQuantity line = line(part, workOrder, null, 1);

            assertEquals(403, send(post("/part-quantities"), body(part, workOrder, null, 2), technician).status());
            assertEquals(403, changeLine(line.getId(), 4, technician).status());
            // the technician sees every work order (the view rule of GET /part-quantities/work-order/{id})
            assertEquals(200, call(get("/part-quantities/" + line.getId()), technician).status());
            assertEquals(1, count("SELECT count(*) FROM part_quantity WHERE work_order_id = ?", workOrder.getId()));
            assertEquals(1.0, jdbc.queryForObject("SELECT quantity FROM part_quantity WHERE id = ?", Double.class,
                    line.getId()));
            assertEquals(10, stock(part));
        }

        @Test
        void userWhoCannotSeeTheWorkOrder_get403() {
            // a role that sees only its own work orders (no viewOther): the line of another work order is not shown
            CommonData data = data();
            User technician = data.o1().technician();
            Part part = part(data.o1().company(), 10);
            WorkOrder workOrder = records.workOrder(data.o1().company(), null, Status.OPEN, false);
            PartQuantity line = line(part, workOrder, null, 1);
            Long role = jdbc.queryForObject("SELECT role_id FROM own_user WHERE id = ?", Long.class,
                    technician.getId());
            jdbc.update("UPDATE own_user SET role_id = ? WHERE id = ?", records.role(data.o1().company()).getId(),
                    technician.getId());
            try {
                assertEquals(403, call(get("/part-quantities/" + line.getId()), technician).status());
                jdbc.update("UPDATE work_order SET primary_user_id = ? WHERE id = ?", technician.getId(),
                        workOrder.getId());
                assertEquals(200, call(get("/part-quantities/" + line.getId()), technician).status());
            } finally {
                jdbc.update("UPDATE own_user SET role_id = ? WHERE id = ?", role, technician.getId());
            }
        }

        @Test
        void technicianAssignedToTheWorkOrder_postAndPatch200() {
            CommonData data = data();
            User technician = data.o1().technician();
            Part part = part(data.o1().company(), 10);
            WorkOrder workOrder = records.workOrder(data.o1().company(), null, Status.OPEN, false);
            jdbc.update("UPDATE work_order SET primary_user_id = ? WHERE id = ?", technician.getId(),
                    workOrder.getId());

            Response created = send(post("/part-quantities"), body(part, workOrder, null, 2), technician);
            assertEquals(200, created.status(), created.toString());
            assertEquals(200, changeLine(json(created).get("id").asLong(), 3, technician).status());
            assertEquals(7, stock(part));
        }

        @Test
        void technicianOnAPurchaseOrder_get403_postAndPatch403() {
            // the technician has no view nor edit of purchase orders and did not create this one
            CommonData data = data();
            User technician = data.o1().technician();
            Part part = part(data.o1().company(), 0);
            PurchaseOrder purchaseOrder = purchaseOrder(data.o1().company());
            PartQuantity line = line(part, null, purchaseOrder, 1);

            assertEquals(403, call(get("/part-quantities/" + line.getId()), technician).status());
            assertEquals(403, send(post("/part-quantities"), body(part, null, purchaseOrder, 2), technician)
                    .status());
            assertEquals(403, changeLine(line.getId(), 4, technician).status());
            assertEquals(1, count("SELECT count(*) FROM part_quantity WHERE purchase_order_id = ?",
                    purchaseOrder.getId()));
        }

        @Test
        void adminOnAPurchaseOrder_postAndPatch200() {
            CommonData data = data();
            User admin = data.o1().admin();
            Part part = part(data.o1().company(), 0);
            PurchaseOrder purchaseOrder = purchaseOrder(data.o1().company());

            Response created = send(post("/part-quantities"), body(part, null, purchaseOrder, 2), admin);
            assertEquals(200, created.status(), created.toString());
            assertEquals(200, changeLine(json(created).get("id").asLong(), 3, admin).status());
            assertEquals(200, call(get("/part-quantities/" + json(created).get("id").asLong()), admin).status());
        }

        @Test
        void otherOrganizationOnClosedParents_403Never409() {
            // the parent is loaded before any check: another organization gets 403, never the 409 of the state
            CommonData data = data();
            User other = data.o2().admin();
            Part part = part(data.o1().company(), 10);
            WorkOrder closed = records.workOrder(data.o1().company(), null, Status.COMPLETE, false);
            PartQuantity workOrderLine = line(part, closed, null, 1);
            PurchaseOrder approved = purchaseOrder(data.o1().company());
            PartQuantity purchaseOrderLine = line(part, null, approved, 1);
            jdbc.update("UPDATE purchase_order SET status = ? WHERE id = ?", ApprovalStatus.APPROVED.ordinal(),
                    approved.getId());
            Part own = part(data.o2().company(), 0);

            assertEquals(403, send(post("/part-quantities"), body(own, closed, null, 1), other).status());
            assertEquals(403, send(post("/part-quantities"), body(own, null, approved, 1), other).status());
            assertEquals(403, changeLine(workOrderLine.getId(), 2, other).status());
            assertEquals(403, changeLine(purchaseOrderLine.getId(), 2, other).status());
            assertEquals(403, call(get("/part-quantities/" + workOrderLine.getId()), other).status());
            assertEquals(403, call(get("/part-quantities/" + purchaseOrderLine.getId()), other).status());
        }

        @Test
        void sameOrganizationOnClosedParents_409AfterThePermission() {
            CommonData data = data();
            User admin = data.o1().admin();
            Part part = part(data.o1().company(), 10);
            WorkOrder closed = records.workOrder(data.o1().company(), null, Status.COMPLETE, false);
            PurchaseOrder approved = purchaseOrder(data.o1().company());
            jdbc.update("UPDATE purchase_order SET status = ? WHERE id = ?", ApprovalStatus.APPROVED.ordinal(),
                    approved.getId());

            assertEquals(409, send(post("/part-quantities"), body(part, closed, null, 1), admin).status());
            assertEquals(409, send(post("/part-quantities"), body(part, null, approved, 1), admin).status());
            assertEquals(403, send(post("/part-quantities"), body(part, closed, null, 1), data.o1().technician())
                    .status(), "403 before 409");
        }

        @Test
        void unknownParents_404() {
            CommonData data = data();
            Part part = part(data.o1().company(), 10);

            assertEquals(404, send(post("/part-quantities"), "{\"part\":{\"id\":" + part.getId()
                    + "},\"workOrder\":{\"id\":999999999},\"quantity\":1}", data.o1().admin()).status());
            assertEquals(404, send(post("/part-quantities"), "{\"part\":{\"id\":" + part.getId()
                    + "},\"purchaseOrder\":{\"id\":999999999},\"quantity\":1}", data.o1().admin()).status());
            assertEquals(404, call(get("/part-quantities/999999999"), data.o1().admin()).status());
        }
    }

    // =====================================================================================================
    // Block 8 · Every route at once: the stock of each part is the sum of its movements (PPB-30)
    // =====================================================================================================

    @Nested
    class EveryRoute {

        @Test
        void afterEveryRoute_stockOfEachPartIsTheSumOfItsMovements() throws InterruptedException {
            CommonData data = data();
            Company company = data.o1().company();
            User admin = data.o1().admin();
            WorkOrder workOrder = records.workOrder(company, null, Status.OPEN, false);
            // creation, adjustment, restock
            Long created = json(send(post("/parts"), "{\"name\":\"" + TestRecords.unique("P-16b all")
                    + "\",\"quantity\":8}", admin)).get("id").asLong();
            Part a = em.find(Part.class, created);
            assertEquals(200, send(patch("/parts/" + a.getId()), "{\"name\":\"" + a.getName()
                    + "\",\"quantity\":12}", admin).status());
            assertEquals(200, send(post("/parts/" + a.getId() + "/restock"), "{\"quantity\":3}", admin).status());
            // consumption by every route, raised, reduced, removed
            Part b = part(company, 20);
            Long line = addByReplacement(workOrder, a, admin);
            assertEquals(200, changeLine(line, 4, admin).status());
            assertEquals(200, changeLine(line, 2, admin).status());
            Response posted = send(post("/part-quantities"), "{\"part\":{\"id\":" + b.getId()
                    + "},\"workOrder\":{\"id\":" + workOrder.getId() + "},\"quantity\":5}", admin);
            assertEquals(200, posted.status(), posted.toString());
            assertEquals(200, call(delete("/part-quantities/" + json(posted).get("id").asLong()), admin).status());
            assertEquals(200, send(patch("/part-quantities/work-order/" + workOrder.getId()),
                    "[" + b.getId() + "]", admin).status());
            assertEquals(200, send(patch("/part-quantities/work-order/" + workOrder.getId()), "[]", admin)
                    .status());
            // import
            importService.importParts(List.of(PartImportDTO.builder().id(b.getId()).name(b.getName()).quantity(30)
                    .build()), company, admin);
            // purchase orders: direct, and by a workflow on creation; then a demo one purged
            PurchaseOrder direct = purchaseOrder(company);
            line(a, null, direct, 6);
            assertEquals(200, call(patch("/purchase-orders/" + direct.getId() + "/respond")
                    .param("approved", "true"), admin).status());
            WorkflowAction action = new WorkflowAction();
            action.setCompany(company);
            action.setPurchaseOrderAction(PurchaseOrderAction.APPROVE);
            Workflow workflow = new Workflow();
            workflow.setCompany(company);
            workflow.setTitle(TestRecords.unique("P-16b workflow"));
            workflow.setMainCondition(WFMainCondition.PURCHASE_ORDER_CREATED);
            workflow.setAction(action);
            workflow.setSecondaryConditions(new ArrayList<>());
            workflowRepository.saveAndFlush(workflow);
            Response automatic = send(post("/purchase-orders"), "{\"name\":\"" + TestRecords.unique("P-16b PO")
                    + "\",\"partQuantities\":[{\"part\":{\"id\":" + b.getId() + "},\"quantity\":4}]}", admin);
            assertEquals("APPROVED", json(automatic).get("status").asText());
            Long demo = json(send(post("/purchase-orders"), "{\"name\":\"" + TestRecords.unique("P-16b demo")
                    + "\",\"partQuantities\":[{\"part\":{\"id\":" + a.getId() + "},\"quantity\":1}]}", admin))
                    .get("id").asLong();
            jdbc.update("UPDATE purchase_order SET is_demo = true WHERE id = ?", demo);
            jdbc.update("UPDATE part_quantity SET is_demo = true WHERE purchase_order_id = ?", demo);
            assertEquals(200, call(delete("/demo/demo-data"), admin).status());

            assertEquals(List.of(), jdbc.queryForList("SELECT p.id FROM part p LEFT JOIN part_transaction t "
                    + "ON t.part_id = p.id WHERE p.company_id = ? GROUP BY p.id, p.quantity "
                    + "HAVING abs(p.quantity - COALESCE(SUM(t.quantity), 0)) > 1e-9", Long.class, company.getId()),
                    "every part: stock = sum of its movements");
            assertEquals(List.of("ADJUSTMENT", "CONSUMPTION", "CREATION", "DEMO_PURGE", "IMPORT", "PURCHASE_ORDER",
                    "RESTOCK", "REVERSAL"), jdbc.queryForList("SELECT DISTINCT origin FROM part_transaction "
                    + "WHERE company_id = ? ORDER BY origin", String.class, company.getId()));
        }
    }
}
