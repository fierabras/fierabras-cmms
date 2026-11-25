package com.grash.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grash.dto.imports.PartImportDTO;
import com.grash.integration.support.TestApi;
import com.grash.integration.support.TestApi.Response;
import com.grash.integration.support.TestOrganizations;
import com.grash.integration.support.TestOrganizations.CommonData;
import com.grash.integration.support.TestRecords;
import com.grash.model.Asset;
import com.grash.model.Company;
import com.grash.model.Part;
import com.grash.model.PartCategory;
import com.grash.model.PartQuantity;
import com.grash.model.PurchaseOrder;
import com.grash.model.User;
import com.grash.model.WorkOrder;
import com.grash.model.WorkOrderCategory;
import com.grash.model.Workflow;
import com.grash.model.WorkflowAction;
import com.grash.model.enums.ApprovalStatus;
import com.grash.model.enums.Status;
import com.grash.model.enums.workflow.PurchaseOrderAction;
import com.grash.model.enums.workflow.WFMainCondition;
import com.grash.repository.PartCategoryRepository;
import com.grash.repository.PartQuantityRepository;
import com.grash.repository.PurchaseOrderRepository;
import com.grash.repository.WorkOrderCategoryRepository;
import com.grash.repository.WorkflowRepository;
import com.grash.service.ImportService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * P-16b characterization: fixes, before any change, which movement of the part ledger ({@code part_transaction})
 * each route that changes the stock of a part leaves, what happens to the movement when a consumption is reduced or
 * removed by each route, how a purchase order approval adds stock by the direct route and by a workflow, with and
 * without {@code nonStock}, how the import sets the stock and {@code nonStock}, how the demo purge treats a part that
 * is not demo, and what the part analytics and the ledger export answer for a fixed set of facts. The tests that show
 * a stock change without movement, a movement edited or deleted, the old sign, a workflow that approves without
 * stock, {@code nonStock} ignored or the missing permissions of {@code part-quantities} are updated to the contract
 * of P-16b, as step 8 of the order allows. Archiving a work order with consumptions is already characterized by
 * {@code WorkOrderLifecycleIntegrationTest} (its snapshot includes the stock and the movements).
 */
@AutoConfigureMockMvc
class PartLedgerCharacterizationIntegrationTest extends AbstractIntegrationTest {

    private final TestOrganizations organizations;
    private final TestRecords records;
    private final TestApi api;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;
    private final PartQuantityRepository partQuantityRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final WorkflowRepository workflowRepository;
    private final ImportService importService;
    private final PartCategoryRepository partCategoryRepository;
    private final WorkOrderCategoryRepository workOrderCategoryRepository;
    private final List<CommonData> created = new ArrayList<>();

    @Autowired
    PartLedgerCharacterizationIntegrationTest(TestOrganizations organizations, TestRecords records, MockMvc mockMvc,
                                              ObjectMapper objectMapper, JdbcTemplate jdbc,
                                              PartQuantityRepository partQuantityRepository,
                                              PurchaseOrderRepository purchaseOrderRepository,
                                              WorkflowRepository workflowRepository, ImportService importService,
                                              PartCategoryRepository partCategoryRepository,
                                              WorkOrderCategoryRepository workOrderCategoryRepository) {
        this.organizations = organizations;
        this.records = records;
        this.api = new TestApi(mockMvc, objectMapper);
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
        this.partQuantityRepository = partQuantityRepository;
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.workflowRepository = workflowRepository;
        this.importService = importService;
        this.partCategoryRepository = partCategoryRepository;
        this.workOrderCategoryRepository = workOrderCategoryRepository;
    }

    @AfterEach
    void cleanUp() {
        created.forEach(organizations::delete);
        created.clear();
    }

    private CommonData data() {
        CommonData data = organizations.create();
        created.add(data);
        return data;
    }

    // --- records ---

    private Part part(Company company, double quantity) {
        Part part = records.part(company, null);
        jdbc.update("UPDATE part SET quantity = ? WHERE id = ?", quantity, part.getId());
        return part;
    }

    private Part nonStockPart(Company company, double quantity) {
        Part part = part(company, quantity);
        jdbc.update("UPDATE part SET non_stock = true WHERE id = ?", part.getId());
        return part;
    }

    private PurchaseOrder purchaseOrder(Company company) {
        PurchaseOrder purchaseOrder = new PurchaseOrder();
        purchaseOrder.setName(TestRecords.unique("P-16b PO"));
        purchaseOrder.setStatus(ApprovalStatus.PENDING);
        purchaseOrder.setCompany(company);
        return purchaseOrderRepository.saveAndFlush(purchaseOrder);
    }

    private PartQuantity line(Part part, WorkOrder workOrder, PurchaseOrder purchaseOrder, double quantity) {
        PartQuantity line = new PartQuantity(part, workOrder, purchaseOrder, quantity);
        line.setCompany(part.getCompany());
        return partQuantityRepository.saveAndFlush(line);
    }

    private Workflow approvingWorkflow(Company company, WFMainCondition main) {
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

    // --- queries ---

    private long count(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, Long.class, arguments);
    }

    private double stock(Part part) {
        return jdbc.queryForObject("SELECT quantity FROM part WHERE id = ?", Double.class, part.getId());
    }

    /**
     * The movements of {@code part}, oldest first, as "quantity/work order/created by" (an absent value is "-").
     */
    private List<String> movements(Part part) {
        return jdbc.queryForList("SELECT quantity, work_order_id, created_by FROM part_transaction WHERE part_id = ? "
                        + "ORDER BY id", part.getId()).stream()
                .map(row -> row.get("quantity") + "/" + orDash(row.get("work_order_id")) + "/"
                        + orDash(row.get("created_by")))
                .collect(Collectors.toList());
    }

    private static String orDash(Object value) {
        return value == null ? "-" : value.toString();
    }

    private String status(PurchaseOrder purchaseOrder) {
        return ApprovalStatus.values()[jdbc.queryForObject("SELECT status FROM purchase_order WHERE id = ?",
                Integer.class, purchaseOrder.getId())].name();
    }

    private Response call(MockHttpServletRequestBuilder request, User user) {
        return api.call(request, api.login(user));
    }

    private Response send(MockHttpServletRequestBuilder request, String body, User user) {
        return call(request.contentType(MediaType.APPLICATION_JSON).content(body), user);
    }

    private JsonNode json(Response response) {
        try {
            return objectMapper.readTree(response.body());
        } catch (Exception e) {
            throw new AssertionError("not JSON: " + response, e);
        }
    }

    /**
     * Adds {@code part} to {@code workOrder} through the bulk replacement, which consumes one, and returns the line.
     */
    private Long addByReplacement(WorkOrder workOrder, Part part, User user) {
        Response response = send(patch("/part-quantities/work-order/" + workOrder.getId()), "[" + part.getId() + "]",
                user);
        assertEquals(200, response.status(), response.toString());
        return json(response).get(0).get("id").asLong();
    }

    private Response changeLine(Long line, double quantity, User user) {
        return send(patch("/part-quantities/" + line), "{\"quantity\":" + quantity + "}", user);
    }

    // =====================================================================================================

    @Nested
    class StockRoutes {

        @Test
        void partCreatedWithQuantity_stockSet_noMovement() {
            // CA-PPB-081; before P-16b: no movement
            CommonData data = data();
            Response response = send(post("/parts"), "{\"name\":\"" + TestRecords.unique("P-16b part")
                    + "\",\"quantity\":5}", data.o1().admin());

            assertEquals(200, response.status(), response.toString());
            Long id = json(response).get("id").asLong();
            assertEquals(5, jdbc.queryForObject("SELECT quantity FROM part WHERE id = ?", Double.class, id));
            // P-16b: an entry of 5 with origin CREATION
            assertEquals(1, count("SELECT count(*) FROM part_transaction WHERE part_id = ?", id));
        }

        @Test
        void partPatchedWithAnotherQuantity_stockSet_withAnAdjustment() {
            // CA-PPB-083; before P-16b: no movement
            CommonData data = data();
            Part part = part(data.o1().company(), 9);

            Response response = send(patch("/parts/" + part.getId()), "{\"name\":\"" + part.getName()
                    + "\",\"quantity\":7}", data.o1().admin());

            assertEquals(200, response.status(), response.toString());
            assertEquals(7, stock(part));
            assertEquals(List.of("-2.0/-/" + data.o1().admin().getId()), movements(part));
        }

        @Test
        void partPatchedWithoutQuantity_stockSetToZero_withAnAdjustment() {
            // the quantity of PartPatchDTO is a primitive: a body without it sets the stock to 0 (D-P16b-11: kept);
            // before P-16b: no movement
            CommonData data = data();
            Part part = part(data.o1().company(), 9);

            Response response = send(patch("/parts/" + part.getId()), "{\"name\":\"" + part.getName() + "\"}",
                    data.o1().admin());

            assertEquals(200, response.status(), response.toString());
            assertEquals(0, stock(part));
            assertEquals(List.of("-9.0/-/" + data.o1().admin().getId()), movements(part));
        }

        @Test
        void restocked_stockAdded_negativeMovementWithoutWorkOrder_byTheUser() {
            // before P-16b: the restock was stored negative; P-16b (D-1): an entry is positive
            CommonData data = data();
            User admin = data.o1().admin();
            Part part = part(data.o1().company(), 1);

            Response response = send(post("/parts/" + part.getId() + "/restock"),
                    "{\"quantity\":2,\"description\":\"P-16b restock\"}", admin);

            assertEquals(200, response.status(), response.toString());
            assertEquals(3, stock(part));
            assertEquals(List.of("2.0/-/" + admin.getId()), movements(part));
            assertEquals("P-16b restock", jdbc.queryForObject("SELECT description FROM part_transaction "
                    + "WHERE part_id = ?", String.class, part.getId()));
        }

        @Test
        void addedByReplacement_oneConsumed_positiveMovementWithTheWorkOrder_byTheUser() {
            // before P-16b: the consumption was stored positive; P-16b (D-1): an exit is negative
            CommonData data = data();
            User admin = data.o1().admin();
            Part part = part(data.o1().company(), 5);
            WorkOrder workOrder = records.workOrder(data.o1().company(), null, Status.OPEN, false);

            addByReplacement(workOrder, part, admin);

            assertEquals(4, stock(part));
            assertEquals(List.of("-1.0/" + workOrder.getId() + "/" + admin.getId()), movements(part));
        }

        @Test
        void lineCreatedByPost_consumed() {
            // before P-16b: stock kept, no movement; P-16b (D-P16b-6): the line consumes its quantity
            CommonData data = data();
            Part part = part(data.o1().company(), 5);
            WorkOrder workOrder = records.workOrder(data.o1().company(), null, Status.OPEN, false);

            Response response = send(post("/part-quantities"), "{\"part\":{\"id\":" + part.getId()
                    + "},\"workOrder\":{\"id\":" + workOrder.getId() + "},\"quantity\":3}", data.o1().admin());

            assertEquals(200, response.status(), response.toString());
            assertEquals(2, stock(part));
            assertEquals(List.of("-3.0/" + workOrder.getId() + "/" + data.o1().admin().getId()), movements(part));
        }

        @Test
        void moreThanTheStockConsumed_406_noLine_noMovement() {
            CommonData data = data();
            Part part = part(data.o1().company(), 0);
            WorkOrder workOrder = records.workOrder(data.o1().company(), null, Status.OPEN, false);

            Response response = send(patch("/part-quantities/work-order/" + workOrder.getId()),
                    "[" + part.getId() + "]", data.o1().admin());

            assertEquals(406, response.status(), response.toString());
            assertTrue(response.body().contains("The quantity should not be negative"), response.toString());
            assertEquals(0, stock(part));
            assertEquals(0, count("SELECT count(*) FROM part_quantity WHERE part_id = ?", part.getId()));
            assertEquals(List.of(), movements(part));
        }

        @Test
        void nonStockPartAddedByReplacement_lineCreated_stockKept_noMovement() {
            CommonData data = data();
            Part part = nonStockPart(data.o1().company(), 5);
            WorkOrder workOrder = records.workOrder(data.o1().company(), null, Status.OPEN, false);

            addByReplacement(workOrder, part, data.o1().admin());

            assertEquals(5, stock(part));
            assertEquals(List.of(), movements(part));
        }
    }

    @Nested
    class ConsumptionReduction {

        @Test
        void lineIncreasedThenReduced_newExitThenAReversal() {
            // CA-PPB-015; before P-16b: the reduction edited the latest movement (to 0); now a reversal of 2
            CommonData data = data();
            User admin = data.o1().admin();
            Part part = part(data.o1().company(), 10);
            WorkOrder workOrder = records.workOrder(data.o1().company(), null, Status.OPEN, false);
            Long line = addByReplacement(workOrder, part, admin);

            assertEquals(200, changeLine(line, 3, admin).status());
            assertEquals(7, stock(part));
            assertEquals(List.of("-1.0/" + workOrder.getId() + "/" + admin.getId(),
                    "-2.0/" + workOrder.getId() + "/" + admin.getId()), movements(part));

            Response response = changeLine(line, 1, admin);

            assertEquals(200, response.status(), response.toString());
            assertEquals(9, stock(part));
            assertEquals(List.of("-1.0/" + workOrder.getId() + "/" + admin.getId(),
                    "-2.0/" + workOrder.getId() + "/" + admin.getId(),
                    "2.0/" + workOrder.getId() + "/" + admin.getId()), movements(part));
        }

        @Test
        void lineChangedToTheSameQuantity_nothingWritten() {
            // before P-16b: the latest movement was deleted (stock 7, sum 9)
            CommonData data = data();
            User admin = data.o1().admin();
            Part part = part(data.o1().company(), 10);
            WorkOrder workOrder = records.workOrder(data.o1().company(), null, Status.OPEN, false);
            Long line = addByReplacement(workOrder, part, admin);
            assertEquals(200, changeLine(line, 3, admin).status());

            Response response = changeLine(line, 3, admin);

            assertEquals(200, response.status(), response.toString());
            assertEquals(7, stock(part));
            assertEquals(List.of("-1.0/" + workOrder.getId() + "/" + admin.getId(),
                    "-2.0/" + workOrder.getId() + "/" + admin.getId()), movements(part));
        }

        @Test
        void lineDeleted_stockRestored_withAReversalOfTheLine() {
            // CA-PPB-044 (individual route); before P-16b: only the latest movement was deleted (+2), the +1 stayed
            CommonData data = data();
            User admin = data.o1().admin();
            Part part = part(data.o1().company(), 10);
            WorkOrder workOrder = records.workOrder(data.o1().company(), null, Status.OPEN, false);
            Long line = addByReplacement(workOrder, part, admin);
            assertEquals(200, changeLine(line, 3, admin).status());

            Response response = call(delete("/part-quantities/" + line), admin);

            assertEquals(200, response.status(), response.toString());
            assertEquals(10, stock(part));
            assertEquals(List.of("-1.0/" + workOrder.getId() + "/" + admin.getId(),
                    "-2.0/" + workOrder.getId() + "/" + admin.getId(),
                    "3.0/" + workOrder.getId() + "/" + admin.getId()), movements(part));
        }

        @Test
        void lineRemovedByReplacement_stockRestored_withAReversal() {
            // CA-PPB-044 (bulk route); before P-16b: no restock, stock 9
            CommonData data = data();
            User admin = data.o1().admin();
            Part part = part(data.o1().company(), 10);
            WorkOrder workOrder = records.workOrder(data.o1().company(), null, Status.OPEN, false);
            addByReplacement(workOrder, part, admin);

            Response response = send(patch("/part-quantities/work-order/" + workOrder.getId()), "[]", admin);

            assertEquals(200, response.status(), response.toString());
            assertEquals(0, count("SELECT count(*) FROM part_quantity WHERE part_id = ?", part.getId()));
            assertEquals(10, stock(part));
            assertEquals(List.of("-1.0/" + workOrder.getId() + "/" + admin.getId(),
                    "1.0/" + workOrder.getId() + "/" + admin.getId()), movements(part));
        }

        @Test
        void lineWithoutMovementDeleted_200_reversalOfTheLine() {
            // a line created without consumption before P-16b (demo data, POST): before P-16b, 500 and nothing
            // changed; now it gives back its quantity like any other
            CommonData data = data();
            Part part = part(data.o1().company(), 5);
            WorkOrder workOrder = records.workOrder(data.o1().company(), null, Status.OPEN, false);
            PartQuantity line = line(part, workOrder, null, 3);

            Response response = call(delete("/part-quantities/" + line.getId()), data.o1().admin());

            assertEquals(200, response.status(), response.toString());
            assertEquals(8, stock(part));
            assertEquals(0, count("SELECT count(*) FROM part_quantity WHERE id = ?", line.getId()));
        }
    }

    @Nested
    class PurchaseOrderApproval {

        @Test
        void nonStockPartApprovedDirectly_stockKept_noMovement() {
            // CA-PPB-127; before P-16b: nonStock ignored, stock 2
            CommonData data = data();
            Part part = nonStockPart(data.o1().company(), 0);
            PurchaseOrder purchaseOrder = purchaseOrder(data.o1().company());
            line(part, null, purchaseOrder, 2);

            Response response = call(patch("/purchase-orders/" + purchaseOrder.getId() + "/respond")
                    .param("approved", "true"), data.o1().admin());

            assertEquals(200, response.status(), response.toString());
            assertEquals(0, stock(part));
            assertEquals(List.of(), movements(part));
        }

        @Test
        void approvedByAWorkflowOnUpdate_stockAdded_thenNotAnsweredAgain() {
            // CA-PPB-128 (D-13); before P-16b: the workflow approved without adding the stock (0, no movement)
            CommonData data = data();
            User admin = data.o1().admin();
            Part part = part(data.o1().company(), 0);
            PurchaseOrder purchaseOrder = purchaseOrder(data.o1().company());
            line(part, null, purchaseOrder, 2);
            approvingWorkflow(data.o1().company(), WFMainCondition.PURCHASE_ORDER_UPDATED);

            Response response = send(patch("/purchase-orders/" + purchaseOrder.getId()), "{\"name\":\""
                    + purchaseOrder.getName() + "\"}", admin);

            assertEquals(200, response.status(), response.toString());
            assertEquals("APPROVED", status(purchaseOrder));
            assertEquals(2, stock(part));
            assertEquals(List.of("2.0/-/" + admin.getId()), movements(part));
            Response answer = call(patch("/purchase-orders/" + purchaseOrder.getId() + "/respond")
                    .param("approved", "true"), admin);
            assertEquals(409, answer.status(), answer.toString());
        }

        @Test
        void createdWithoutLinesAndApprovedByAWorkflow_linesAddedAfterwards_409() {
            // the web creates the purchase order first and sends its lines afterwards: with a workflow that
            // approves on creation the lines are rejected (P-16, PURCHASE_ORDER_CLOSED)
            CommonData data = data();
            User admin = data.o1().admin();
            Part part = part(data.o1().company(), 0);
            approvingWorkflow(data.o1().company(), WFMainCondition.PURCHASE_ORDER_CREATED);
            Response created = send(post("/purchase-orders"), "{\"name\":\"" + TestRecords.unique("P-16b PO")
                    + "\"}", admin);
            assertEquals(200, created.status(), created.toString());
            long id = json(created).get("id").asLong();

            Response lines = send(patch("/part-quantities/purchase-order/" + id), "[{\"part\":{\"id\":"
                    + part.getId() + "},\"quantity\":2}]", admin);

            assertEquals(409, lines.status(), lines.toString());
            assertTrue(lines.body().contains("PURCHASE_ORDER_CLOSED"), lines.toString());
            assertEquals(0, stock(part));
        }
    }

    @Nested
    class Import {

        private PartImportDTO row(Part part, double quantity, String nonStock, String category) {
            return PartImportDTO.builder().id(part == null ? null : part.getId())
                    .name(part == null ? TestRecords.unique("P-16b imported") : part.getName())
                    .quantity(quantity).nonStock(nonStock).category(category).build();
        }

        @Test
        void importedQuantities_stockSet_importMovementByWhoImports_importedPartWithoutCreator() {
            // CA-PPB-084; before P-16b: no movement. The import runs without an authenticated user (asynchronous):
            // the part has no creator, the movement has the user passed down (C-1)
            CommonData data = data();
            Company company = data.o1().company();
            Part p8 = part(company, 7);
            Part p9 = part(company, 3);
            PartImportDTO newRow = row(null, 4, null, null);

            importService.importParts(List.of(row(p8, 12, null, null), row(p9, 3, null, null), newRow), company,
                    data.o1().admin());

            assertEquals(12, stock(p8));
            assertEquals(3, stock(p9));
            assertEquals(List.of("5.0/-/" + data.o1().admin().getId()), movements(p8));
            assertEquals(List.of(), movements(p9));
            Map<String, Object> imported = jdbc.queryForMap("SELECT id, quantity, created_by FROM part "
                    + "WHERE company_id = ? AND name = ?", company.getId(), newRow.getName());
            assertEquals(4.0, ((Number) imported.get("quantity")).doubleValue());
            assertNull(imported.get("created_by"));
            assertEquals(1, count("SELECT count(*) FROM part_transaction WHERE part_id = ?", imported.get("id")));
        }

        @Test
        void nonStockReadFromItsOwnColumn() {
            // A-10; before P-16b: the import read nonStock from the category column
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
        void nonStockPartImported_stockKept() {
            // CA-PPB-127 (import); before P-16b: nonStock ignored (and read from the category), stock 10
            CommonData data = data();
            Part part = nonStockPart(data.o1().company(), 0);

            importService.importParts(List.of(row(part, 10, "true", null)), data.o1().company(), data.o1().admin());

            assertEquals(0, stock(part));
            assertEquals(List.of(), movements(part));
        }
    }

    @Nested
    class DemoPurge {

        @Test
        void partNotDemoSuppliedByAnApprovedDemoPurchaseOrder_200_purchaseOrderPurged_stockCompensated() {
            // CA-PPB-086; before P-16b: the purge kept the demo purchase order and its 3 stayed in the stock
            CommonData data = data();
            User admin = data.o1().admin();
            Part part = part(data.o1().company(), 0);
            PurchaseOrder purchaseOrder = purchaseOrder(data.o1().company());
            PartQuantity line = line(part, null, purchaseOrder, 3);
            assertEquals(200, call(patch("/purchase-orders/" + purchaseOrder.getId() + "/respond")
                    .param("approved", "true"), admin).status());
            jdbc.update("UPDATE purchase_order SET is_demo = true WHERE id = ?", purchaseOrder.getId());
            jdbc.update("UPDATE part_quantity SET is_demo = true WHERE id = ?", line.getId());

            Response response = call(delete("/demo/demo-data"), admin);

            assertEquals(200, response.status(), response.toString());
            assertEquals(0, count("SELECT count(*) FROM purchase_order WHERE id = ?", purchaseOrder.getId()));
            assertEquals(0, count("SELECT count(*) FROM part_quantity WHERE id = ?", line.getId()));
            assertEquals(0, stock(part));
            assertEquals(List.of("3.0/-/" + admin.getId(), "-3.0/-/" + admin.getId()), movements(part));
        }

        @Test
        void partNotDemoConsumedOnADemoWorkOrder_200_movementKept_consumptionCompensated() {
            // D-3; before P-16b: the movement fell with the work order (CASCADE) and the stock stayed at 4
            CommonData data = data();
            User admin = data.o1().admin();
            Part part = part(data.o1().company(), 5);
            WorkOrder workOrder = records.workOrder(data.o1().company(), null, Status.OPEN, true);
            addByReplacement(workOrder, part, admin);

            Response response = call(delete("/demo/demo-data"), admin);

            assertEquals(200, response.status(), response.toString());
            assertEquals(0, count("SELECT count(*) FROM work_order WHERE id = ?", workOrder.getId()));
            assertEquals(5, stock(part));
            assertEquals(List.of("-1.0/-/" + admin.getId(), "1.0/-/" + admin.getId()), movements(part));
        }
    }

    @Nested
    class PartQuantityPermissions {

        @Test
        void technicianWithoutEditOnTheWorkOrder_postGetPatch_200() {
            // E-15 (TD-P12-01); before P-16b: no permission check
            CommonData data = data();
            Part part = part(data.o1().company(), 10);
            WorkOrder workOrder = records.workOrder(data.o1().company(), null, Status.OPEN, false);
            User technician = data.o1().technician();

            Response created = send(post("/part-quantities"), "{\"part\":{\"id\":" + part.getId()
                    + "},\"workOrder\":{\"id\":" + workOrder.getId() + "},\"quantity\":1}", technician);
            assertEquals(200, created.status(), created.toString());
            long line = json(created).get("id").asLong();

            Response read = call(get("/part-quantities/" + line), technician);
            assertEquals(200, read.status(), read.toString());

            Response changed = changeLine(line, 2, technician);
            assertEquals(200, changed.status(), changed.toString());
        }

        @Test
        void otherOrganization_postGetPatch_403() {
            CommonData data = data();
            Part part = part(data.o1().company(), 10);
            WorkOrder workOrder = records.workOrder(data.o1().company(), null, Status.OPEN, false);
            PartQuantity line = line(part, workOrder, null, 1);
            User other = data.o2().admin();

            assertEquals(403, send(post("/part-quantities"), "{\"part\":{\"id\":" + part.getId()
                    + "},\"workOrder\":{\"id\":" + workOrder.getId() + "},\"quantity\":1}", other).status());
            assertEquals(403, call(get("/part-quantities/" + line.getId()), other).status());
            assertEquals(403, changeLine(line.getId(), 2, other).status());
        }
    }

    @Nested
    class Schema {

        @Test
        void ledgerTable_columnsAndForeignKeys() {
            // E-11, E-12; before P-16b: no origin, purchase order nor demo flag, work_order_id CASCADE without index
            List<String> columns = jdbc.queryForList("SELECT column_name || ' ' || data_type || ' ' || is_nullable "
                    + "FROM information_schema.columns WHERE table_schema = current_schema() "
                    + "AND table_name = 'part_transaction' ORDER BY column_name", String.class);
            assertEquals(List.of("company_id bigint NO", "created_at timestamp without time zone NO",
                    "created_by bigint YES", "description text YES", "id bigint NO", "is_demo boolean NO",
                    "origin character varying NO", "part_id bigint NO", "purchase_order_id bigint YES",
                    "quantity double precision NO", "updated_at timestamp without time zone NO",
                    "updated_by bigint YES", "work_order_id bigint YES"), columns);
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
            // Hibernate pooled optimizer: a value of the sequence is the top of a block of 50 identifiers
            assertEquals(50L, count("SELECT increment_by FROM pg_sequences "
                    + "WHERE sequencename = 'part_transaction_seq'"));
        }
    }

    /**
     * One fixed set of facts: part A (cost 10, no unit, category PC) consumed on work order W (category WC, asset
     * X): one added by the replacement, raised to 3, lowered to 2; then restocked by 5. Stock 10 - 2 + 5 = 13.
     */
    @Nested
    class Readers {

        private record Facts(CommonData data, Part part, WorkOrder workOrder, Asset asset, Long partCategory,
                             Long workOrderCategory) {
        }

        private Facts facts() {
            CommonData data = data();
            Company company = data.o1().company();
            User admin = data.o1().admin();
            PartCategory pc = new PartCategory();
            pc.setName(TestRecords.unique("P-16b PC"));
            pc.setCompanySettings(company.getCompanySettings());
            Long partCategory = partCategoryRepository.saveAndFlush(pc).getId();
            WorkOrderCategory wc = new WorkOrderCategory();
            wc.setName(TestRecords.unique("P-16b WC"));
            wc.setCompanySettings(company.getCompanySettings());
            Long workOrderCategory = workOrderCategoryRepository.saveAndFlush(wc).getId();
            Part part = part(company, 10);
            jdbc.update("UPDATE part SET cost = 10, category_id = ? WHERE id = ?", partCategory, part.getId());
            Asset asset = records.asset(company);
            WorkOrder workOrder = records.workOrder(company, asset, Status.OPEN, false);
            jdbc.update("UPDATE work_order SET category_id = ? WHERE id = ?", workOrderCategory, workOrder.getId());
            Long line = addByReplacement(workOrder, part, admin);
            assertEquals(200, changeLine(line, 3, admin).status());
            assertEquals(200, changeLine(line, 2, admin).status());
            assertEquals(200, send(post("/parts/" + part.getId() + "/restock"),
                    "{\"quantity\":5,\"description\":\"P-16b restock\"}", admin).status());
            assertEquals(13, stock(part));
            return new Facts(data, part, workOrder, asset, partCategory, workOrderCategory);
        }

        private JsonNode analytics(String path, User user) {
            long now = System.currentTimeMillis();
            String range = "{\"start\":" + (now - 86_400_000L) + ",\"end\":" + (now + 86_400_000L) + "}";
            Response response = send(post("/analytics/parts/consumptions/" + path), range, user);
            assertEquals(200, response.status(), response.toString());
            return json(response);
        }

        @Test
        void analytics_countTheNetConsumption() {
            Facts facts = facts();
            User admin = facts.data().o1().admin();

            JsonNode overview = analytics("overview", admin);
            assertEquals(2, overview.get("consumedCount").asInt());
            assertEquals(20.0, overview.get("totalConsumptionCost").asDouble());

            JsonNode pareto = analytics("pareto", admin);
            assertEquals(1, pareto.size());
            assertEquals(facts.part().getId(), pareto.get(0).get("id").asLong());
            assertEquals(20.0, pareto.get(0).get("cost").asDouble());

            JsonNode assets = analytics("assets", admin);
            assertEquals(1, assets.size());
            assertEquals(facts.asset().getId(), assets.get(0).get("id").asLong());
            assertEquals(20.0, assets.get(0).get("cost").asDouble());

            JsonNode byPartCategory = analytics("parts-category", admin);
            assertEquals(20.0, costOf(byPartCategory, facts.partCategory()));

            JsonNode byWorkOrderCategory = analytics("work-order-category", admin);
            assertEquals(20.0, costOf(byWorkOrderCategory, facts.workOrderCategory()));

            JsonNode byDate = analytics("date", admin);
            double total = 0;
            for (JsonNode point : byDate) total += point.get("cost").asDouble();
            assertEquals(20.0, total);
        }

        private double costOf(JsonNode costs, Long id) {
            for (JsonNode cost : costs) {
                if (cost.get("id").asLong() == id) return cost.get("cost").asDouble();
            }
            throw new AssertionError("no cost for " + id + " in " + costs);
        }

        @Test
        void export_showsTheStoredSign() {
            // before P-16b: the export inverted the stored sign; P-16b (D-1) stores it as it is shown
            Facts facts = facts();
            User admin = facts.data().o1().admin();

            Response response = call(get("/export/part-transactions").param("uuid", "p16b-characterization"),
                    admin);
            assertEquals(200, response.status(), response.toString());
            String path = (String) storageDouble.awaitCalls("generateSignedUrl", 1, Duration.ofSeconds(20)).get(0)
                    .arguments().get(0);
            String csv = new String(storageDouble.download(path), StandardCharsets.UTF_8);
            List<String> lines = csv.lines().toList();
            String[] headers = lines.get(0).split(",");

            // P-16b (D-4): four columns added at the end; the first nine are as before
            assertEquals(List.of("ID", "Part ID", "Part", "Quantity", "Cost", "Work order ID", "Work order Title",
                    "Description", "Date created", "Origin", "User", "Purchase order ID", "Purchase order"),
                    List.of(headers), lines.get(0));
            List<String> rows = lines.subList(1, lines.size()).stream()
                    .map(row -> {
                        String[] cells = row.split(",", -1);
                        return cells[3] + "|" + cells[4] + "|" + cells[5] + "|" + cells[7];
                    }).sorted().toList();
            // before P-16b: the reduction edited the second movement, so the export showed -1 twice; P-16b (PPB-04):
            // the second exit stays -2 and the reduction is its own entry of +1. The net is the same (-2)
            assertEquals(List.of("-1.0|-10.0|" + facts.workOrder().getId() + "|",
                    "-2.0|-20.0|" + facts.workOrder().getId() + "|", "1.0|10.0|" + facts.workOrder().getId() + "|",
                    "5.0|50.0||P-16b restock"), rows, csv);
        }
    }
}
