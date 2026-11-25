package com.grash.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grash.integration.support.FaultInjector;
import com.grash.integration.support.TestApi;
import com.grash.integration.support.TestApi.Response;
import com.grash.integration.support.TestOrganizations;
import com.grash.integration.support.TestOrganizations.CommonData;
import com.grash.integration.support.TestRecords;
import com.grash.model.Company;
import com.grash.model.Part;
import com.grash.model.PartQuantity;
import com.grash.model.PurchaseOrder;
import com.grash.model.User;
import com.grash.model.WorkOrder;
import com.grash.model.enums.ApprovalStatus;
import com.grash.model.enums.PartTransactionOrigin;
import com.grash.model.enums.Status;
import com.grash.repository.PartQuantityRepository;
import com.grash.repository.PurchaseOrderRepository;
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

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
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
    final List<CommonData> created = new ArrayList<>();

    @Autowired
    PartLedgerIntegrationTest(TestOrganizations organizations, TestRecords records, MockMvc mockMvc,
                              ObjectMapper objectMapper, JdbcTemplate jdbc,
                              PartQuantityRepository partQuantityRepository,
                              PurchaseOrderRepository purchaseOrderRepository, PartLedgerService ledger,
                              EntityManager em, PlatformTransactionManager transactionManager) {
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
     * Every movement of {@code part}, as JSON rows, to check that earlier movements never change.
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
}
