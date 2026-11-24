package com.grash.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grash.integration.support.FaultInjector;
import com.grash.integration.support.FaultInjector.Fault;
import com.grash.integration.support.FaultInjector.Operation;
import com.grash.integration.support.TestApi;
import com.grash.integration.support.TestApi.Response;
import com.grash.integration.support.TestOrganizations;
import com.grash.integration.support.TestOrganizations.CommonData;
import com.grash.integration.support.TestRecords;
import com.grash.model.Company;
import com.grash.model.Part;
import com.grash.model.PartCategory;
import com.grash.model.PartQuantity;
import com.grash.model.PartTransaction;
import com.grash.model.PurchaseOrder;
import com.grash.model.User;
import com.grash.model.WorkOrder;
import com.grash.model.enums.ApprovalStatus;
import com.grash.model.enums.Status;
import com.grash.repository.PartCategoryRepository;
import com.grash.repository.PartQuantityRepository;
import com.grash.repository.PartTransactionRepository;
import com.grash.repository.PurchaseOrderRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;

/**
 * P-16: deletion of parts, part categories, purchase order categories, vendors and sets of parts, and the closed
 * state of a purchase order (PPB-02, 05, 08, 14, 15, 17, 21 to 23, 25 to 27; CA-PPB-007, 008, 009, 020, 038, 069,
 * 070, 085, 103, 108, 124).
 */
@AutoConfigureMockMvc
class InventoryPurchasingDeletionIntegrationTest extends AbstractIntegrationTest {

    static final String DELETED_BODY = "{\"success\":true,\"message\":\"Deleted successfully\"}";

    final TestOrganizations organizations;
    final TestRecords records;
    final TestApi api;
    final ObjectMapper objectMapper;
    final JdbcTemplate jdbc;
    final PartCategoryRepository partCategoryRepository;
    final PartQuantityRepository partQuantityRepository;
    final PartTransactionRepository partTransactionRepository;
    final PurchaseOrderRepository purchaseOrderRepository;
    final FaultInjector faults;
    private final List<CommonData> created = new ArrayList<>();

    @Autowired
    InventoryPurchasingDeletionIntegrationTest(TestOrganizations organizations, TestRecords records,
                                              MockMvc mockMvc, ObjectMapper objectMapper, JdbcTemplate jdbc,
                                              PartCategoryRepository partCategoryRepository,
                                              PartQuantityRepository partQuantityRepository,
                                              PartTransactionRepository partTransactionRepository,
                                              PurchaseOrderRepository purchaseOrderRepository) {
        this.organizations = organizations;
        this.records = records;
        this.api = new TestApi(mockMvc, objectMapper);
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
        this.partCategoryRepository = partCategoryRepository;
        this.partQuantityRepository = partQuantityRepository;
        this.partTransactionRepository = partTransactionRepository;
        this.purchaseOrderRepository = purchaseOrderRepository;
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

    Part part(Company company, double quantity) {
        Part part = records.part(company, null);
        jdbc.update("UPDATE part SET quantity = ? WHERE id = ?", quantity, part.getId());
        return part;
    }

    PartCategory partCategory(Company company) {
        PartCategory category = new PartCategory();
        category.setName(TestRecords.unique("P-16 part category"));
        category.setCompanySettings(company.getCompanySettings());
        return partCategoryRepository.saveAndFlush(category);
    }

    PurchaseOrder purchaseOrder(Company company, ApprovalStatus status) {
        PurchaseOrder purchaseOrder = new PurchaseOrder();
        purchaseOrder.setName(TestRecords.unique("P-16 PO"));
        purchaseOrder.setStatus(status);
        purchaseOrder.setCompany(company);
        return purchaseOrderRepository.saveAndFlush(purchaseOrder);
    }

    PartQuantity line(Part part, WorkOrder workOrder, PurchaseOrder purchaseOrder, double quantity) {
        PartQuantity line = new PartQuantity(part, workOrder, purchaseOrder, quantity);
        line.setCompany(part.getCompany());
        return partQuantityRepository.saveAndFlush(line);
    }

    PartTransaction movement(Part part, WorkOrder workOrder, double quantity) {
        PartTransaction movement = new PartTransaction(part, workOrder, quantity);
        movement.setCompany(part.getCompany());
        return partTransactionRepository.saveAndFlush(movement);
    }

    // --- queries ---

    long count(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, Long.class, arguments);
    }

    long rows(String table, Long id) {
        return count("SELECT count(*) FROM " + table + " WHERE id = ?", id);
    }

    String row(String table, Long id) {
        return jdbc.queryForObject("SELECT row_to_json(t)::text FROM " + table + " t WHERE id = ?", String.class, id);
    }

    long auditRecords(CommonData data) {
        return count("SELECT count(*) FROM audit_log WHERE company_id = ?", data.o1().company().getId());
    }

    Map<String, Object> onlyAuditRecord(CommonData data) {
        return jdbc.queryForMap("SELECT entity_type, entity_id, entity_folio, entity_name, action, source, "
                + "cascade_rows::text AS cascade_rows FROM audit_log WHERE company_id = ?",
                data.o1().company().getId());
    }

    /**
     * The ON DELETE rule (a NO ACTION, c CASCADE, n SET NULL) of the foreign key on {@code table.column}, and whether
     * the column leads a valid, non-partial index.
     */
    String key(String table, String column) {
        return jdbc.queryForObject("""
                SELECT c.confdeltype::text || ' ' || (EXISTS (SELECT 1 FROM pg_index i WHERE i.indrelid = c.conrelid
                       AND i.indkey[0] = c.conkey[1] AND i.indisvalid AND i.indpred IS NULL))::text
                FROM pg_constraint c JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = c.conkey[1]
                WHERE c.contype = 'f' AND c.conrelid = ?::regclass AND a.attname = ?""", String.class, table, column);
    }

    void disable(User user) {
        jdbc.update("UPDATE own_user SET enabled = false WHERE id = ?", user.getId());
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

    /**
     * 409 with {@code code}, {@code success = false}, a message and exactly {@code types} as blocking types (none if
     * empty), without SQL or constraint names.
     */
    void assertConflict(Response response, String code, String... types) {
        assertEquals(409, response.status(), response.toString());
        JsonNode body = json(response);
        TreeSet<String> blocking = new TreeSet<>();
        if (body.has("blockingTypes")) body.get("blockingTypes").forEach(type -> blocking.add(type.asText()));
        assertAll(
                () -> assertFalse(body.get("success").asBoolean()),
                () -> assertEquals(code, body.get("code").asText()),
                () -> assertFalse(body.get("message").asText().isBlank()),
                () -> assertEquals(new TreeSet<>(List.of(types)), blocking, response.toString()),
                () -> assertFalse(response.body().toLowerCase().contains("constraint")
                        || response.body().contains("fk") || response.body().contains("SQL"), response.toString()));
    }

    // =====================================================================================================
    // Block 1: part category
    // =====================================================================================================

    @Nested
    class PartCategoryDeletion {

        private Response deleteCategory(PartCategory category, User user) {
            return call(delete("/part-categories/" + category.getId()), user);
        }

        @Test
        void ca007_usedByAPartWithAMovement_409PartCategoryInUse_partAndMovementUnchanged() {
            CommonData data = data();
            Company company = data.o1().company();
            PartCategory k1 = partCategory(company);
            Part p1 = part(company, 5);
            jdbc.update("UPDATE part SET category_id = ? WHERE id = ?", k1.getId(), p1.getId());
            WorkOrder workOrder = records.workOrder(company, null, Status.OPEN, false);
            PartTransaction movement = movement(p1, workOrder, 1);
            String partBefore = row("part", p1.getId());
            String movementBefore = row("part_transaction", movement.getId());

            Response response = deleteCategory(k1, data.o1().admin());

            assertConflict(response, "PART_CATEGORY_IN_USE", "PART");
            assertAll(
                    () -> assertEquals(1, rows("part_category", k1.getId())),
                    () -> assertEquals(partBefore, row("part", p1.getId())),
                    () -> assertEquals(movementBefore, row("part_transaction", movement.getId())),
                    () -> assertEquals(0, auditRecords(data)),
                    () -> assertTrue(webhookDouble.calls().isEmpty()));
        }

        @Test
        void ca008_unused_200_deleted_recorded() {
            CommonData data = data();
            PartCategory k2 = partCategory(data.o1().company());

            Response response = deleteCategory(k2, data.o1().admin());

            assertEquals(200, response.status(), response.toString());
            assertEquals(DELETED_BODY, response.body());
            assertEquals(0, rows("part_category", k2.getId()));
            Map<String, Object> record = onlyAuditRecord(data);
            assertAll(
                    () -> assertEquals("PART_CATEGORY", record.get("entity_type")),
                    () -> assertEquals(k2.getId(), record.get("entity_id")),
                    () -> assertEquals(k2.getName(), record.get("entity_name")),
                    () -> assertNull(record.get("entity_folio")),
                    () -> assertEquals("DELETE", record.get("action")),
                    () -> assertEquals("{}", record.get("cascade_rows")));
        }

        @Test
        void partAssignedBetweenCheckAndDeletion_409WithPart_never500() {
            CommonData data = data();
            Company company = data.o1().company();
            PartCategory category = partCategory(company);
            Part part = part(company, 0);

            Response response;
            try (Fault fault = faults.runBefore("part_category", Operation.DELETE,
                    "UPDATE part SET category_id = " + category.getId() + " WHERE id = " + part.getId())) {
                response = deleteCategory(category, data.o1().admin());
                assertEquals(1, fault.hits());
            }

            assertConflict(response, "PART_CATEGORY_IN_USE", "PART");
            assertEquals(1, rows("part", part.getId()));
            assertEquals(0, auditRecords(data));
        }

        @Test
        void otherOrganization_inUse_403_never409() {
            CommonData data = data();
            Company company = data.o1().company();
            PartCategory category = partCategory(company);
            Part part = part(company, 0);
            jdbc.update("UPDATE part SET category_id = ? WHERE id = ?", category.getId(), part.getId());

            Response response = deleteCategory(category, data.o2().admin());

            assertEquals(403, response.status(), response.toString());
            assertFalse(response.body().contains("IN_USE"), response.toString());
            assertEquals(1, rows("part_category", category.getId()));
        }

        @Test
        void schema_partCategoryIdNoActionIndexed() {
            assertEquals("a true", key("part", "category_id"));
        }
    }
}
