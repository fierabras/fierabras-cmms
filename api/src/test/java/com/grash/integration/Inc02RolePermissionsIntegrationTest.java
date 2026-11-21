package com.grash.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.grash.integration.support.TestOrganizations;
import com.grash.model.*;
import com.grash.model.abstracts.WorkOrderBase;
import com.grash.model.enums.DateFormat;
import com.grash.model.enums.FileType;
import com.grash.model.enums.NotificationType;
import com.grash.model.enums.TimeStatus;
import com.grash.model.enums.PermissionEntity;
import com.grash.model.enums.PlanFeatures;
import com.grash.model.enums.RoleCode;
import com.grash.model.enums.RoleType;
import com.grash.model.enums.Status;
import com.grash.repository.*;
import com.grash.dto.ReportConfig;
import com.grash.dto.imports.WorkOrderImportDTO;
import com.grash.exception.CustomException;
import com.grash.service.CompanyService;
import com.grash.service.ImportService;
import com.grash.service.RequestService;
import com.grash.service.RoleService;
import com.grash.service.WorkOrderService;
import com.grash.utils.Consts;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * INC-02 contract tests (criteria C1-C12) for roles, permissions and authorizations.
 * <p>
 * The tests go through the real chain (JWT filter, controller, service, JPA, database) using tokens obtained from
 * {@code POST /auth/signin}. They are intentionally not {@code @Transactional}: every request commits, as in
 * production, so that caches and ON DELETE rules behave as they do at runtime. Each test creates its own companies
 * and {@link #cleanUp()} deletes them afterwards.
 * <p>
 * The assertions encode the INC-02 contract, not the current behavior.
 */
@AutoConfigureMockMvc
class Inc02RolePermissionsIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "Inc02-Passw0rd!";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private RoleService roleService;
    @Autowired
    private CompanyService companyService;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private CompanyRepository companyRepository;
    @Autowired
    private CompanySettingsRepository companySettingsRepository;
    @Autowired
    private GeneralPreferencesRepository generalPreferencesRepository;
    @Autowired
    private CurrencyRepository currencyRepository;
    @Autowired
    private SubscriptionRepository subscriptionRepository;
    @Autowired
    private SubscriptionPlanRepository subscriptionPlanRepository;
    @Autowired
    private RequestRepository requestRepository;
    @Autowired
    private WorkOrderRepository workOrderRepository;
    @Autowired
    private SuperAccountRelationRepository superAccountRelationRepository;
    @Autowired
    private ImportService importService;
    @Autowired
    private LocationRepository locationRepository;
    @Autowired
    private AssetRepository assetRepository;
    @Autowired
    private PartRepository partRepository;
    @Autowired
    private PartQuantityRepository partQuantityRepository;
    @Autowired
    private FileRepository fileRepository;
    @Autowired
    private TaskRepository taskRepository;
    @Autowired
    private TaskBaseRepository taskBaseRepository;
    @Autowired
    private LaborRepository laborRepository;
    @Autowired
    private AdditionalCostRepository additionalCostRepository;
    @Autowired
    private CommentRepository commentRepository;
    @Autowired
    private RequestPortalRepository requestPortalRepository;
    @Autowired
    private RequestService requestService;
    @Autowired
    private PreventiveMaintenanceRepository preventiveMaintenanceRepository;
    @Autowired
    private RelationRepository relationRepository;
    @Autowired
    private WorkOrderService workOrderService;

    private final List<Long> createdCompanyIds = new ArrayList<>();
    private final List<Long> createdPlanIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        // Other integration tests count users globally, so nothing committed here may survive the test.
        for (Long companyId : createdCompanyIds) {
            List<Long> settingsIds = jdbcTemplate.queryForList(
                    "SELECT company_settings_id FROM company WHERE id = ?", Long.class, companyId);
            if (settingsIds.isEmpty()) {
                continue; // the test itself deleted the company
            }
            Long settingsId = settingsIds.get(0);
            // ON DELETE CASCADE from company removes its settings, roles, users, requests and work orders.
            TestOrganizations.deleteWorkOrderRevisions(jdbcTemplate, companyId);
            jdbcTemplate.update("DELETE FROM company WHERE id = ?", companyId);
            assertEquals(0, count("SELECT count(*) FROM own_user WHERE company_id = ?", companyId),
                    "cleanup left users of company " + companyId);
            assertEquals(0, count("SELECT count(*) FROM role WHERE company_settings_id = ?", settingsId),
                    "cleanup left roles of company " + companyId);
        }
        // ON DELETE CASCADE from subscription_plan removes the subscription.
        createdPlanIds.forEach(id -> jdbcTemplate.update("DELETE FROM subscription_plan WHERE id = ?", id));
        createdCompanyIds.clear();
        createdPlanIds.clear();
    }

    private long count(String sql, Long id) {
        Long result = jdbcTemplate.queryForObject(sql, Long.class, id);
        return result == null ? 0 : result;
    }

    // =====================================================================================================
    // Persistence
    // =====================================================================================================

    @Nested
    @DisplayName("INC-02 persistence")
    class PersistenceTests {

        @Test
        @DisplayName("INC-02 C1: a custom role created with the UI payload is read back with identical permissions")
        void createdRole_readBack_hasIdenticalPermissions() throws Exception {
            Tenant tenant = newTenant("C1");
            String adminToken = login(tenant.admin());
            Perms sent = new Perms(
                    set(PermissionEntity.WORK_ORDERS, PermissionEntity.ASSETS, PermissionEntity.REQUESTS),
                    set(PermissionEntity.WORK_ORDERS),
                    set(PermissionEntity.REQUESTS, PermissionEntity.WORK_ORDERS),
                    set(PermissionEntity.ASSETS),
                    set(PermissionEntity.REQUESTS));

            HttpResult created = call(post("/roles").contentType(MediaType.APPLICATION_JSON)
                    .content(json(uiCreatePayload(tenant.company(), "C1 role", sent))), adminToken);
            assertTrue(created.is2xx(), "POST /roles should succeed: " + created);
            long roleId = objectMapper.readTree(created.body()).get("id").asLong();

            HttpResult readBack = call(get("/roles/" + roleId), adminToken);

            assertTrue(readBack.is2xx(), "GET /roles/{id} should succeed: " + readBack);
            assertEquals(sent, readPerms(objectMapper.readTree(readBack.body())));
        }

        @Test
        @DisplayName("INC-02 C2: permissions added and removed with the UI edit payload are kept exactly")
        void editedRole_readBack_keepsExactChanges() throws Exception {
            Tenant tenant = newTenant("C2");
            String adminToken = login(tenant.admin());
            Role role = createCustomRole(tenant.company(), "C2 role", new Perms(
                    set(PermissionEntity.WORK_ORDERS, PermissionEntity.ASSETS),
                    set(PermissionEntity.WORK_ORDERS),
                    set(PermissionEntity.WORK_ORDERS),
                    set(PermissionEntity.WORK_ORDERS),
                    set(PermissionEntity.REQUESTS)));
            Perms edited = new Perms(
                    set(PermissionEntity.WORK_ORDERS, PermissionEntity.LOCATIONS), // +LOCATIONS -ASSETS
                    set(),                                                         // -WORK_ORDERS
                    set(PermissionEntity.WORK_ORDERS, PermissionEntity.REQUESTS),  // +REQUESTS
                    set(PermissionEntity.ASSETS),                                  // -WORK_ORDERS +ASSETS
                    set());                                                        // -REQUESTS

            HttpResult patched = patchRoleLikeUi(adminToken, tenant.company(), role.getId(), edited);
            assertTrue(patched.is2xx(), "PATCH /roles/{id} should succeed: " + patched);

            HttpResult readBack = call(get("/roles/" + role.getId()), adminToken);

            assertTrue(readBack.is2xx(), "GET /roles/{id} should succeed: " + readBack);
            assertEquals(edited, readPerms(objectMapper.readTree(readBack.body())));
        }
    }

    // =====================================================================================================
    // T-13: PATCH /roles/{id} semantics (Codex A-12)
    // =====================================================================================================

    @Nested
    @DisplayName("INC-02 role PATCH semantics")
    class RolePatchSemanticsTests {

        private final Perms initial = new Perms(set(PermissionEntity.WORK_ORDERS, PermissionEntity.ASSETS),
                set(PermissionEntity.WORK_ORDERS), set(PermissionEntity.REQUESTS), set(PermissionEntity.ASSETS),
                set(PermissionEntity.LOCATIONS));

        @Test
        @DisplayName("INC-02 C2 (T-13): permission sets omitted from the PATCH keep their current value")
        void omittedSets_keepCurrentValue() throws Exception {
            Tenant tenant = newTenant("T13-omitted");
            Role role = createCustomRole(tenant.company(), "T13 role", initial);
            ObjectNode body = objectMapper.createObjectNode().put("name", "T13 renamed")
                    .put("version", role.getVersion());

            HttpResult result = call(patch("/roles/" + role.getId()).contentType(MediaType.APPLICATION_JSON)
                    .content(json(body)), login(tenant.admin()));

            assertAll(
                    () -> assertTrue(result.is2xx(), "PATCH /roles/{id}: " + result),
                    () -> assertEquals("T13 renamed", snapshot(role.getId()).name()),
                    () -> assertEquals(initial, permsInDb(role.getId()), "omitted sets must not change"));
        }

        @Test
        @DisplayName("INC-02 C2 (T-13): a null permission set keeps its value, an empty one clears it, the others are replaced")
        void nullKeeps_emptyClears_valuesReplace() throws Exception {
            Tenant tenant = newTenant("T13-null");
            Role role = createCustomRole(tenant.company(), "T13 role", initial);
            ObjectNode body = objectMapper.createObjectNode().put("name", "T13 role").put("version", role.getVersion());
            body.putNull("viewPermissions");
            body.putArray("viewOtherPermissions");
            body.putArray("createPermissions").add(PermissionEntity.WORK_ORDERS.name());
            body.putNull("editOtherPermissions");
            body.putArray("deleteOtherPermissions");

            HttpResult result = call(patch("/roles/" + role.getId()).contentType(MediaType.APPLICATION_JSON)
                    .content(json(body)), login(tenant.admin()));

            assertAll(
                    () -> assertTrue(result.is2xx(), "PATCH /roles/{id}: " + result),
                    () -> assertEquals(new Perms(initial.view(), set(), set(PermissionEntity.WORK_ORDERS),
                            initial.editOther(), set()), permsInDb(role.getId())));
        }

        @Test
        @DisplayName("INC-02 C2 (D-14): a PATCH without the role version gets 400 and the role is unchanged")
        void missingVersion_badRequest_roleUnchanged() throws Exception {
            Tenant tenant = newTenant("D14-no-version");
            Role role = createCustomRole(tenant.company(), "D14 role", initial);
            ObjectNode body = objectMapper.createObjectNode().put("name", "D14 renamed");
            putPerms(body, new Perms(set(), set(), set(), set(), set()));

            HttpResult result = call(patch("/roles/" + role.getId()).contentType(MediaType.APPLICATION_JSON)
                    .content(json(body)), login(tenant.admin()));

            assertAll(
                    () -> assertEquals(400, result.status(), "PATCH /roles/{id} without version: " + result),
                    () -> assertEquals("D14 role", snapshot(role.getId()).name(), "name must not change"),
                    () -> assertEquals(initial, permsInDb(role.getId()), "permissions must not change"));
        }

        @Test
        @DisplayName("INC-02 C2 (T-13): two editors from the same snapshot: the second save must not silently drop the first change")
        void concurrentEditors_secondSaveDoesNotDropFirstChange() throws Exception {
            Tenant tenant = newTenant("T13-concurrent");
            Role role = createCustomRole(tenant.company(), "T13 role", initial);
            String tokenA = login(tenant.admin());
            String tokenB = login(createUser(tenant.company(), roleService.findDefaultRoleWithCode(RoleCode.ADMIN)
                    .orElseThrow()));
            // Both editors open the edit dialog: the UI keeps the role as read by GET.
            ObjectNode snapshotA = (ObjectNode) objectMapper.readTree(call(get("/roles/" + role.getId()), tokenA).body());
            ObjectNode snapshotB = (ObjectNode) objectMapper.readTree(call(get("/roles/" + role.getId()), tokenB).body());
            Perms withSettings = new Perms(set(PermissionEntity.WORK_ORDERS, PermissionEntity.ASSETS,
                    PermissionEntity.SETTINGS), initial.viewOther(), initial.create(), initial.editOther(),
                    initial.deleteOther());
            Perms withMeters = new Perms(set(PermissionEntity.WORK_ORDERS, PermissionEntity.ASSETS,
                    PermissionEntity.METERS), initial.viewOther(), initial.create(), initial.editOther(),
                    initial.deleteOther());
            putPerms(snapshotA, withSettings);
            putPerms(snapshotB, withMeters);

            HttpResult first = call(patch("/roles/" + role.getId()).contentType(MediaType.APPLICATION_JSON)
                    .content(json(snapshotA)), tokenA);
            HttpResult second = call(patch("/roles/" + role.getId()).contentType(MediaType.APPLICATION_JSON)
                    .content(json(snapshotB)), tokenB);

            assertAll(
                    () -> assertTrue(first.is2xx(), "first PATCH: " + first),
                    () -> assertEquals(409, second.status(), "stale PATCH must be rejected: " + second),
                    () -> assertEquals(withSettings, permsInDb(role.getId()), "the first change must be kept"));
        }
    }

    // =====================================================================================================
    // Authorization
    // =====================================================================================================

    @Nested
    @DisplayName("INC-02 authorization")
    class AuthorizationTests {

        private final Perms requestCreator = new Perms(
                set(PermissionEntity.REQUESTS), set(), set(PermissionEntity.REQUESTS), set(), set());
        private final Perms requestViewer = new Perms(
                set(PermissionEntity.REQUESTS), set(), set(), set(), set());
        // P-1 resolved by T-09: reviewing requests takes the rule in force plus create on work orders, as in the UI.
        private final Perms requestReviewer = new Perms(
                set(PermissionEntity.REQUESTS, PermissionEntity.SETTINGS), set(),
                set(PermissionEntity.WORK_ORDERS), set(), set());
        private final Perms settingsWithoutWorkOrders = new Perms(
                set(PermissionEntity.REQUESTS, PermissionEntity.SETTINGS), set(), set(), set(), set());
        private final Perms workOrderEditor = new Perms(
                set(PermissionEntity.WORK_ORDERS), set(PermissionEntity.WORK_ORDERS), set(),
                set(PermissionEntity.WORK_ORDERS), set());
        private final Perms workOrderViewer = new Perms(
                set(PermissionEntity.WORK_ORDERS), set(PermissionEntity.WORK_ORDERS), set(), set(), set());

        @Test
        @DisplayName("INC-02 C3: a user whose role grants create on requests creates a request (2xx)")
        void grantedPermission_operationSucceeds() throws Exception {
            Tenant tenant = newTenant("C3");
            User user = createUser(tenant.company(), createCustomRole(tenant.company(), "C3 role", requestCreator));

            HttpResult result = createRequestViaApi(login(user), "C3 request");

            assertTrue(result.is2xx(), "POST /requests should succeed: " + result);
        }

        @Test
        @DisplayName("INC-02 C4: a user whose role does not grant create on requests gets 403 calling the API directly")
        void missingPermission_directApiCall_forbidden() throws Exception {
            Tenant tenant = newTenant("C4a");
            User user = createUser(tenant.company(), createCustomRole(tenant.company(), "C4a role", requestViewer));

            HttpResult result = createRequestViaApi(login(user), "C4 request");

            assertEquals(403, result.status(), "POST /requests: " + result);
        }

        @Test
        @DisplayName("INC-02 C4: a user without the settings permission gets 403 editing a role and the role is unchanged")
        void missingSettingsPermission_patchRole_forbidden() throws Exception {
            Tenant tenant = newTenant("C4b");
            Role ownRole = createCustomRole(tenant.company(), "C4b role", requestViewer);
            User user = createUser(tenant.company(), ownRole);
            Perms everything = new Perms(all(), all(), all(), all(), all());

            HttpResult result = patchRoleLikeUi(login(user), tenant.company(), ownRole.getId(), everything);

            assertAll(
                    () -> assertEquals(403, result.status(), "PATCH /roles/{id}: " + result),
                    () -> assertEquals(requestViewer, permsInDb(ownRole.getId()), "role permissions must not change"));
        }

        @Test
        @DisplayName("INC-02 C5: after a permission is removed and the user logs in again, the operation is rejected")
        void removedPermission_newLogin_rejectedImmediately() throws Exception {
            Tenant tenant = newTenant("C5");
            Role role = createCustomRole(tenant.company(), "C5 role", requestCreator);
            User user = createUser(tenant.company(), role);

            HttpResult before = createRequestViaApi(login(user), "C5 request before");
            assertTrue(before.is2xx(), "precondition, POST /requests before revocation: " + before);
            revoke(tenant, role, requestViewer);

            HttpResult after = createRequestViaApi(login(user), "C5 request after");

            assertEquals(403, after.status(), "POST /requests after revocation and new login: " + after);
        }

        // ----- C6: approve / reject a request and complete a work order (backend rule, see P-1) -----

        @Test
        @DisplayName("INC-02 C6: a user whose role grants view on settings approves a request (2xx)")
        void approveRequest_granted_succeeds() throws Exception {
            Tenant tenant = newTenant("C6-approve-ok");
            User reviewer = createUser(tenant.company(), createCustomRole(tenant.company(), "C6 reviewer", requestReviewer));
            Request request = createRequest(tenant.company(), tenant.admin(), null);

            HttpResult result = approve(login(reviewer), request.getId());

            Long workOrderId = workOrderIdOf(request.getId());
            assertAll(
                    () -> assertTrue(result.is2xx(), "PATCH /requests/{id}/approve: " + result),
                    () -> assertNotNull(workOrderId, "the request must get a work order"),
                    () -> assertEquals(request.getId(), inTx(() -> {
                        Request parent = workOrderRepository.findById(workOrderId).orElseThrow().getParentRequest();
                        return parent == null ? null : parent.getId();
                    }), "the work order must point to the request"));
        }

        @Test
        @DisplayName("INC-02 C6: a user whose role does not grant view on settings gets 403 approving a request")
        void approveRequest_notGranted_forbidden() throws Exception {
            Tenant tenant = newTenant("C6-approve-403");
            User viewer = createUser(tenant.company(), createCustomRole(tenant.company(), "C6 viewer", requestViewer));
            Request request = createRequest(tenant.company(), tenant.admin(), null);

            HttpResult result = approve(login(viewer), request.getId());

            assertAll(
                    () -> assertEquals(403, result.status(), "PATCH /requests/{id}/approve: " + result),
                    () -> assertNull(workOrderIdOf(request.getId()), "request must stay unapproved"));
        }

        @Test
        @DisplayName("INC-02 C6: after view on settings is removed and the user logs in again, approving is rejected")
        void approveRequest_revoked_rejectedAfterNewLogin() throws Exception {
            Tenant tenant = newTenant("C6-approve-revoked");
            Role role = createCustomRole(tenant.company(), "C6 reviewer", requestReviewer);
            User reviewer = createUser(tenant.company(), role);
            Request first = createRequest(tenant.company(), tenant.admin(), null);
            Request second = createRequest(tenant.company(), tenant.admin(), null);

            HttpResult before = approve(login(reviewer), first.getId());
            assertTrue(before.is2xx(), "precondition, approve before revocation: " + before);
            revoke(tenant, role, requestViewer);

            HttpResult after = approve(login(reviewer), second.getId());

            assertAll(
                    () -> assertEquals(403, after.status(), "approve after revocation and new login: " + after),
                    () -> assertNull(workOrderIdOf(second.getId()), "request must stay unapproved"));
        }

        @Test
        @DisplayName("INC-02 C6: a user whose role grants view on settings rejects a request (2xx)")
        void rejectRequest_granted_succeeds() throws Exception {
            Tenant tenant = newTenant("C6-reject-ok");
            User reviewer = createUser(tenant.company(), createCustomRole(tenant.company(), "C6 reviewer", requestReviewer));
            Request request = createRequest(tenant.company(), tenant.admin(), null);

            HttpResult result = reject(login(reviewer), request.getId());

            assertAll(
                    () -> assertTrue(result.is2xx(), "PATCH /requests/{id}/cancel: " + result),
                    () -> assertTrue(isCancelled(request.getId()), "request must be rejected"),
                    () -> assertEquals("INC-02 rejection", inTx(() -> requestRepository.findById(request.getId())
                            .orElseThrow().getCancellationReason()), "the reason sent must be kept"),
                    () -> assertNull(workOrderIdOf(request.getId()), "rejected request must get no work order"));
        }

        @Test
        @DisplayName("INC-02 C6: a user whose role does not grant view on settings gets 403 rejecting a request")
        void rejectRequest_notGranted_forbidden() throws Exception {
            Tenant tenant = newTenant("C6-reject-403");
            User viewer = createUser(tenant.company(), createCustomRole(tenant.company(), "C6 viewer", requestViewer));
            Request request = createRequest(tenant.company(), tenant.admin(), null);

            HttpResult result = reject(login(viewer), request.getId());

            assertAll(
                    () -> assertEquals(403, result.status(), "PATCH /requests/{id}/cancel: " + result),
                    () -> assertFalse(isCancelled(request.getId()), "request must stay pending"));
        }

        @Test
        @DisplayName("INC-02 C6: after view on settings is removed and the user logs in again, rejecting is rejected")
        void rejectRequest_revoked_rejectedAfterNewLogin() throws Exception {
            Tenant tenant = newTenant("C6-reject-revoked");
            Role role = createCustomRole(tenant.company(), "C6 reviewer", requestReviewer);
            User reviewer = createUser(tenant.company(), role);
            Request first = createRequest(tenant.company(), tenant.admin(), null);
            Request second = createRequest(tenant.company(), tenant.admin(), null);

            HttpResult before = reject(login(reviewer), first.getId());
            assertTrue(before.is2xx(), "precondition, reject before revocation: " + before);
            revoke(tenant, role, requestViewer);

            HttpResult after = reject(login(reviewer), second.getId());

            assertAll(
                    () -> assertEquals(403, after.status(), "reject after revocation and new login: " + after),
                    () -> assertFalse(isCancelled(second.getId()), "request must stay pending"));
        }

        // ----- RF-07: CA-06 (grant) and CA-05 (only an approval links a work order to a request) -----

        @Test
        @DisplayName("RF-07 CA-06: after view on settings is granted and the user logs in again, approving succeeds")
        void approveRequest_granted_succeedsAfterNewLogin() throws Exception {
            Tenant tenant = newTenant("RF07-approve-granted");
            Perms withoutSettings = new Perms(set(PermissionEntity.REQUESTS), set(),
                    set(PermissionEntity.WORK_ORDERS), set(), set());
            Role role = createCustomRole(tenant.company(), "RF07 reviewer", withoutSettings);
            User reviewer = createUser(tenant.company(), role);
            Request first = createRequest(tenant.company(), tenant.admin(), null);
            Request second = createRequest(tenant.company(), tenant.admin(), null);

            HttpResult before = approve(login(reviewer), first.getId());
            assertEquals(403, before.status(), "precondition, approve before the grant: " + before);
            grant(tenant, role, requestReviewer);

            HttpResult after = approve(login(reviewer), second.getId());

            assertAll(
                    () -> assertTrue(after.is2xx(), "approve after the grant and new login: " + after),
                    () -> assertNull(workOrderIdOf(first.getId()), "the first request must stay unapproved"),
                    () -> assertNotNull(workOrderIdOf(second.getId()), "the second request must get a work order"));
        }

        private final Perms workOrderCreator = new Perms(
                set(PermissionEntity.WORK_ORDERS, PermissionEntity.REQUESTS), set(PermissionEntity.WORK_ORDERS),
                set(PermissionEntity.WORK_ORDERS), set(PermissionEntity.WORK_ORDERS), set());

        @Test
        @DisplayName("RF-07 CA-05: parentRequest sent when creating a work order is ignored and the request stays pending")
        void workOrderCreate_parentRequestInPayload_ignored() throws Exception {
            Tenant tenant = newTenant("RF07-wo-create");
            String token = login(createUser(tenant.company(),
                    createCustomRole(tenant.company(), "RF07 creator", workOrderCreator)));
            Request request = createRequest(tenant.company(), tenant.admin(), null);
            ObjectNode body = objectMapper.createObjectNode().put("title", "RF07 direct work order");
            body.putObject("parentRequest").put("id", request.getId());

            HttpResult created = call(post("/work-orders").contentType(MediaType.APPLICATION_JSON).content(json(body)),
                    token);
            assertTrue(created.is2xx(), "POST /work-orders: " + created);
            long workOrderId = objectMapper.readTree(created.body()).get("id").asLong();
            HttpResult read = call(get("/work-orders/" + workOrderId), token);

            assertAll(
                    () -> assertTrue(read.is2xx(), "GET /work-orders/{id}: " + read),
                    () -> assertTrue(!objectMapper.readTree(read.body()).hasNonNull("parentRequest"),
                            "the work order must have no parent request: " + read),
                    () -> assertEquals(0, count("SELECT count(*) FROM work_order WHERE parent_request_id = ?",
                            request.getId()), "no work order may point to the request"),
                    () -> assertNull(workOrderIdOf(request.getId()), "request must stay unapproved"),
                    () -> assertFalse(isCancelled(request.getId()), "request must stay pending"));
        }

        @Test
        @DisplayName("RF-07 CA-05: parentRequest sent when editing a work order is ignored and the request stays pending")
        void workOrderPatch_parentRequestInPayload_ignored() throws Exception {
            Tenant tenant = newTenant("RF07-wo-patch");
            String token = login(createUser(tenant.company(),
                    createCustomRole(tenant.company(), "RF07 editor", workOrderCreator)));
            Request request = createRequest(tenant.company(), tenant.admin(), null);
            WorkOrder workOrder = createWorkOrder(tenant.company(), tenant.admin(), null);
            ObjectNode body = objectMapper.createObjectNode().put("title", "RF07 edited work order");
            body.putObject("parentRequest").put("id", request.getId());

            HttpResult patched = call(patch("/work-orders/" + workOrder.getId()).contentType(MediaType.APPLICATION_JSON)
                    .content(json(body)), token);
            HttpResult read = call(get("/work-orders/" + workOrder.getId()), token);

            assertAll(
                    () -> assertTrue(patched.is2xx(), "PATCH /work-orders/{id}: " + patched),
                    () -> assertTrue(read.is2xx(), "GET /work-orders/{id}: " + read),
                    () -> assertTrue(!objectMapper.readTree(read.body()).hasNonNull("parentRequest"),
                            "the work order must have no parent request: " + read),
                    () -> assertEquals(0, count("SELECT count(*) FROM work_order WHERE parent_request_id = ?",
                            request.getId()), "no work order may point to the request"),
                    () -> assertNull(workOrderIdOf(request.getId()), "request must stay unapproved"),
                    () -> assertFalse(isCancelled(request.getId()), "request must stay pending"));
        }

        // ----- T-09: the backend requires exactly what the UI requires (RequestDetails.tsx) -----

        @Test
        @DisplayName("INC-02 C6 (T-09): view on settings without create on work orders gets 403 approving and the request stays pending")
        void approveRequest_withoutCreateWorkOrders_forbidden() throws Exception {
            Tenant tenant = newTenant("T09-approve-no-wo");
            User user = createUser(tenant.company(), createCustomRole(tenant.company(), "T09 role", settingsWithoutWorkOrders));
            Request request = createRequest(tenant.company(), tenant.admin(), null);

            HttpResult result = approve(login(user), request.getId());

            assertAll(
                    () -> assertEquals(403, result.status(), "PATCH /requests/{id}/approve: " + result),
                    () -> assertNull(workOrderIdOf(request.getId()), "request must stay unapproved"));
        }

        @Test
        @DisplayName("INC-02 C6 (T-09): view on settings without create on work orders gets 403 rejecting and the request stays pending")
        void rejectRequest_withoutCreateWorkOrders_forbidden() throws Exception {
            Tenant tenant = newTenant("T09-reject-no-wo");
            User user = createUser(tenant.company(), createCustomRole(tenant.company(), "T09 role", settingsWithoutWorkOrders));
            Request request = createRequest(tenant.company(), tenant.admin(), null);

            HttpResult result = reject(login(user), request.getId());

            assertAll(
                    () -> assertEquals(403, result.status(), "PATCH /requests/{id}/cancel: " + result),
                    () -> assertFalse(isCancelled(request.getId()), "request must stay pending"));
        }

        @Test
        @DisplayName("INC-02 C6 (T-09): a super account user gets 403 reviewing a request, as the UI hides the actions")
        void reviewRequest_superAccountUser_forbidden() throws Exception {
            Tenant tenant = newTenant("T09-super");
            User reviewer = createUser(tenant.company(), createCustomRole(tenant.company(), "T09 reviewer", requestReviewer));
            User child = createUser(tenant.company(), createCustomRole(tenant.company(), "T09 child", requestViewer));
            SuperAccountRelation relation = superAccountRelationRepository.save(new SuperAccountRelation(null, reviewer, child));
            Request request = createRequest(tenant.company(), tenant.admin(), null);
            try {
                String token = login(reviewer);
                HttpResult approved = approve(token, request.getId());
                HttpResult rejected = reject(token, request.getId());

                assertAll(
                        () -> assertEquals(403, approved.status(), "PATCH /requests/{id}/approve: " + approved),
                        () -> assertEquals(403, rejected.status(), "PATCH /requests/{id}/cancel: " + rejected),
                        () -> assertNull(workOrderIdOf(request.getId()), "request must stay unapproved"),
                        () -> assertFalse(isCancelled(request.getId()), "request must stay pending"));
            } finally {
                superAccountRelationRepository.deleteById(relation.getId());
            }
        }

        @Test
        @DisplayName("INC-02 C6 (T-09): a rejected request cannot be approved (4xx) and gets no work order")
        void approveRequest_alreadyRejected_rejected() throws Exception {
            Tenant tenant = newTenant("T09-approve-cancelled");
            User reviewer = createUser(tenant.company(), createCustomRole(tenant.company(), "T09 reviewer", requestReviewer));
            Request request = createRequest(tenant.company(), tenant.admin(), null);
            String token = login(reviewer);
            HttpResult rejected = reject(token, request.getId());
            assertTrue(rejected.is2xx(), "precondition, reject: " + rejected);

            HttpResult result = approve(token, request.getId());

            assertAll(
                    () -> assertEquals(406, result.status(), "PATCH /requests/{id}/approve on a rejected request: " + result),
                    () -> assertNull(workOrderIdOf(request.getId()), "rejected request must get no work order"));
        }

        @Test
        @DisplayName("INC-02 C6 (T-09): a rejected request cannot be rejected again (4xx) and keeps its reason")
        void rejectRequest_alreadyRejected_rejected() throws Exception {
            Tenant tenant = newTenant("T09-reject-cancelled");
            User reviewer = createUser(tenant.company(), createCustomRole(tenant.company(), "T09 reviewer", requestReviewer));
            Request request = createRequest(tenant.company(), tenant.admin(), null);
            String token = login(reviewer);
            HttpResult rejected = reject(token, request.getId());
            assertTrue(rejected.is2xx(), "precondition, reject: " + rejected);

            HttpResult result = call(patch("/requests/" + request.getId() + "/cancel").param("reason", "second reason"),
                    token);

            assertAll(
                    () -> assertEquals(406, result.status(), "PATCH /requests/{id}/cancel on a rejected request: " + result),
                    () -> assertEquals("INC-02 rejection", inTx(() -> requestRepository.findById(request.getId())
                            .orElseThrow().getCancellationReason()), "the first reason must be kept"));
        }

        @Test
        @DisplayName("RF-07 (TD-RF07-02): an approved request cannot be approved again (406) and keeps its only work order")
        void approveRequest_alreadyApproved_notAcceptable() throws Exception {
            Tenant tenant = newTenant("RF07-approve-approved");
            User reviewer = createUser(tenant.company(), createCustomRole(tenant.company(), "RF07 reviewer", requestReviewer));
            Request request = createRequest(tenant.company(), tenant.admin(), null);
            String token = login(reviewer);
            HttpResult approved = approve(token, request.getId());
            assertTrue(approved.is2xx(), "precondition, approve: " + approved);
            Long workOrderId = workOrderIdOf(request.getId());
            assertNotNull(workOrderId, "precondition, the request got a work order");

            HttpResult result = approve(token, request.getId());

            assertAll(
                    () -> assertEquals(406, result.status(), "PATCH /requests/{id}/approve on an approved request: " + result),
                    () -> assertEquals(workOrderId, workOrderIdOf(request.getId()), "the work order must not change"),
                    () -> assertEquals(1, count("SELECT count(*) FROM work_order WHERE parent_request_id = ?",
                            request.getId()), "one work order for the request"));
        }

        @Test
        @DisplayName("RF-07 (TD-RF07-02): an approved request cannot be rejected (406) and keeps its work order")
        void rejectRequest_alreadyApproved_notAcceptable() throws Exception {
            Tenant tenant = newTenant("RF07-reject-approved");
            User reviewer = createUser(tenant.company(), createCustomRole(tenant.company(), "RF07 reviewer", requestReviewer));
            Request request = createRequest(tenant.company(), tenant.admin(), null);
            String token = login(reviewer);
            HttpResult approved = approve(token, request.getId());
            assertTrue(approved.is2xx(), "precondition, approve: " + approved);
            Long workOrderId = workOrderIdOf(request.getId());
            assertNotNull(workOrderId, "precondition, the request got a work order");

            HttpResult result = reject(token, request.getId());

            assertAll(
                    () -> assertEquals(406, result.status(), "PATCH /requests/{id}/cancel on an approved request: " + result),
                    () -> assertFalse(isCancelled(request.getId()), "request must not be rejected"),
                    () -> assertEquals(workOrderId, workOrderIdOf(request.getId()), "the work order must be kept"));
        }

        // ----- R-07: two concurrent reviews of the same request, only one wins -----

        @Test
        @DisplayName("INC-02 R-07: two concurrent approvals of a pending request create one work order, the other gets 406")
        void concurrentApprovals_onlyOneWins() throws Exception {
            Tenant tenant = newTenant("R07-approve");
            String first = login(createUser(tenant.company(),
                    createCustomRole(tenant.company(), "R07 reviewer A", requestReviewer)));
            String second = login(createUser(tenant.company(),
                    createCustomRole(tenant.company(), "R07 reviewer B", requestReviewer)));
            for (int round = 0; round < 5; round++) {
                Request request = createRequest(tenant.company(), tenant.admin(), null);

                List<HttpResult> results = concurrently(() -> approve(first, request.getId()),
                        () -> approve(second, request.getId()));

                int currentRound = round;
                assertAll("round " + round,
                        () -> assertEquals(1, results.stream().filter(HttpResult::is2xx).count(),
                                "round " + currentRound + ", exactly one approval wins: " + results),
                        () -> assertEquals(1, results.stream().filter(result -> result.status() == 406).count(),
                                "round " + currentRound + ", the other one gets 406: " + results),
                        () -> assertEquals(1, count("SELECT count(*) FROM work_order WHERE parent_request_id = ?",
                                request.getId()), "round " + currentRound + ", one work order for the request"));
            }
        }

        @Test
        @DisplayName("INC-02 R-07: a concurrent approval and rejection of a pending request leave it either approved or rejected")
        void concurrentApprovalAndRejection_onlyOneWins() throws Exception {
            Tenant tenant = newTenant("R07-mixed");
            String approver = login(createUser(tenant.company(),
                    createCustomRole(tenant.company(), "R07 approver", requestReviewer)));
            String rejecter = login(createUser(tenant.company(),
                    createCustomRole(tenant.company(), "R07 rejecter", requestReviewer)));
            for (int round = 0; round < 5; round++) {
                Request request = createRequest(tenant.company(), tenant.admin(), null);

                List<HttpResult> results = concurrently(() -> approve(approver, request.getId()),
                        () -> reject(rejecter, request.getId()));

                int currentRound = round;
                boolean approved = workOrderIdOf(request.getId()) != null;
                assertAll("round " + round,
                        () -> assertEquals(1, results.stream().filter(HttpResult::is2xx).count(),
                                "round " + currentRound + ", exactly one review wins: " + results),
                        () -> assertNotEquals(approved, isCancelled(request.getId()),
                                "round " + currentRound + ", approved or rejected, not both"),
                        () -> assertEquals(approved ? 1 : 0, count(
                                "SELECT count(*) FROM work_order WHERE parent_request_id = ?", request.getId()),
                                "round " + currentRound + ", a work order only if approved"));
            }
        }

        private List<HttpResult> concurrently(Callable<HttpResult> a, Callable<HttpResult> b) throws Exception {
            CyclicBarrier start = new CyclicBarrier(2);
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                Future<HttpResult> first = executor.submit(() -> {
                    start.await();
                    return a.call();
                });
                Future<HttpResult> second = executor.submit(() -> {
                    start.await();
                    return b.call();
                });
                return List.of(first.get(60, TimeUnit.SECONDS), second.get(60, TimeUnit.SECONDS));
            } finally {
                executor.shutdownNow();
            }
        }

        // ----- T-10: the generic PATCH never changes the review state of a request -----

        @Test
        @DisplayName("INC-02 C6 (T-10): the generic PATCH cannot reject a pending request")
        void patchRequest_cancelledTrue_requestStaysPending() throws Exception {
            Tenant tenant = newTenant("T10-reject");
            Request request = createRequest(tenant.company(), tenant.admin(), null);
            ObjectNode body = objectMapper.createObjectNode().put("title", "T10 edited").put("cancelled", true);

            HttpResult result = call(patch("/requests/" + request.getId()).contentType(MediaType.APPLICATION_JSON)
                    .content(json(body)), login(tenant.admin()));

            assertAll(
                    () -> assertTrue(result.is2xx(), "PATCH /requests/{id}: " + result),
                    () -> assertFalse(isCancelled(request.getId()), "request must stay pending"));
        }

        @Test
        @DisplayName("INC-02 C6 (T-10): the generic PATCH cannot reopen a rejected request")
        void patchRequest_onRejectedRequest_staysRejected() throws Exception {
            Tenant tenant = newTenant("T10-reopen");
            Request request = createRequest(tenant.company(), tenant.admin(), null);
            String token = login(tenant.admin());
            HttpResult rejected = reject(token, request.getId());
            assertTrue(rejected.is2xx(), "precondition, reject: " + rejected);
            // Same body a client editing the title sends: the primitive cancelled flag defaults to false.
            ObjectNode body = objectMapper.createObjectNode().put("title", "T10 edited");

            HttpResult result = call(patch("/requests/" + request.getId()).contentType(MediaType.APPLICATION_JSON)
                    .content(json(body)), token);

            assertAll(
                    () -> assertTrue(result.is2xx(), "PATCH /requests/{id}: " + result),
                    () -> assertTrue(isCancelled(request.getId()), "request must stay rejected"),
                    () -> assertEquals("INC-02 rejection", inTx(() -> requestRepository.findById(request.getId())
                            .orElseThrow().getCancellationReason()), "the rejection reason must be kept"));
        }

        @Test
        @DisplayName("INC-02 C6: a user whose role grants edit-other on work orders completes a work order (2xx)")
        void completeWorkOrder_granted_succeeds() throws Exception {
            Tenant tenant = newTenant("C6-complete-ok");
            User editor = createUser(tenant.company(), createCustomRole(tenant.company(), "C6 editor", workOrderEditor));
            WorkOrder workOrder = createWorkOrder(tenant.company(), tenant.admin(), null);

            HttpResult result = complete(login(editor), workOrder.getId());

            assertTrue(result.is2xx(), "PATCH /work-orders/{id}/change-status: " + result);
        }

        @Test
        @DisplayName("INC-02 C6: a user whose role does not grant edit-other on work orders gets 403 completing one")
        void completeWorkOrder_notGranted_forbidden() throws Exception {
            Tenant tenant = newTenant("C6-complete-403");
            User viewer = createUser(tenant.company(), createCustomRole(tenant.company(), "C6 viewer", workOrderViewer));
            WorkOrder workOrder = createWorkOrder(tenant.company(), tenant.admin(), null);

            HttpResult result = complete(login(viewer), workOrder.getId());

            assertAll(
                    () -> assertEquals(403, result.status(), "PATCH /work-orders/{id}/change-status: " + result),
                    () -> assertEquals(Status.OPEN, statusOf(workOrder.getId()), "work order must stay OPEN"));
        }

        @Test
        @DisplayName("INC-02 C6: after edit-other on work orders is removed and the user logs in again, completing is rejected")
        void completeWorkOrder_revoked_rejectedAfterNewLogin() throws Exception {
            Tenant tenant = newTenant("C6-complete-revoked");
            Role role = createCustomRole(tenant.company(), "C6 editor", workOrderEditor);
            User editor = createUser(tenant.company(), role);
            WorkOrder first = createWorkOrder(tenant.company(), tenant.admin(), null);
            WorkOrder second = createWorkOrder(tenant.company(), tenant.admin(), null);

            HttpResult before = complete(login(editor), first.getId());
            assertTrue(before.is2xx(), "precondition, complete before revocation: " + before);
            revoke(tenant, role, workOrderViewer);

            HttpResult after = complete(login(editor), second.getId());

            assertAll(
                    () -> assertEquals(403, after.status(), "complete after revocation and new login: " + after),
                    () -> assertEquals(Status.OPEN, statusOf(second.getId()), "work order must stay OPEN"));
        }

        // ----- C10: no one grants permissions they do not hold -----

        @Test
        @DisplayName("INC-02 C10: creating a role with a permission the requester lacks returns 403 and persists nothing")
        void createRoleWithUnheldPermission_forbidden_nothingPersisted() throws Exception {
            Tenant tenant = newTenant("C10-create");
            Perms roleManager = new Perms(set(PermissionEntity.SETTINGS), set(), set(), set(), set());
            User manager = createUser(tenant.company(), createCustomRole(tenant.company(), "C10 manager", roleManager));
            Perms escalated = new Perms(set(), set(), set(), set(), set(PermissionEntity.WORK_ORDERS));

            HttpResult result = call(post("/roles").contentType(MediaType.APPLICATION_JSON)
                    .content(json(uiCreatePayload(tenant.company(), "C10 escalated role", escalated))), login(manager));

            assertAll(
                    () -> assertEquals(403, result.status(), "POST /roles: " + result),
                    () -> assertFalse(roleNamesOf(tenant.company()).contains("C10 escalated role"),
                            "no role must be persisted"));
        }

        @Test
        @DisplayName("INC-02 C10: editing a role to add a permission the requester lacks returns 403 and persists nothing")
        void editRoleWithUnheldPermission_forbidden_nothingPersisted() throws Exception {
            Tenant tenant = newTenant("C10-edit");
            Perms roleManager = new Perms(set(PermissionEntity.SETTINGS), set(), set(), set(), set());
            User manager = createUser(tenant.company(), createCustomRole(tenant.company(), "C10 manager", roleManager));
            Perms original = new Perms(set(PermissionEntity.WORK_ORDERS), set(), set(), set(), set());
            Role target = createCustomRole(tenant.company(), "C10 target", original);
            Perms escalated = new Perms(set(PermissionEntity.WORK_ORDERS), set(), set(),
                    set(PermissionEntity.WORK_ORDERS), set());

            HttpResult result = patchRoleLikeUi(login(manager), tenant.company(), target.getId(), escalated);

            assertAll(
                    () -> assertEquals(403, result.status(), "PATCH /roles/{id}: " + result),
                    () -> assertEquals(original, permsInDb(target.getId()), "role permissions must not change"));
        }

        @Test
        @DisplayName("INC-02 C10 (T-03): creating and editing a role within the requester's permissions succeeds")
        void createAndEditRoleWithinHeldPermissions_succeeds() throws Exception {
            Tenant tenant = newTenant("C10-within");
            Perms roleManager = new Perms(set(PermissionEntity.SETTINGS, PermissionEntity.WORK_ORDERS), set(),
                    set(PermissionEntity.WORK_ORDERS), set(), set());
            String token = login(createUser(tenant.company(),
                    createCustomRole(tenant.company(), "C10 manager", roleManager)));
            Perms granted = new Perms(set(PermissionEntity.WORK_ORDERS), set(), set(PermissionEntity.WORK_ORDERS),
                    set(), set());

            HttpResult created = call(post("/roles").contentType(MediaType.APPLICATION_JSON)
                    .content(json(uiCreatePayload(tenant.company(), "C10 granted role", granted))), token);
            assertTrue(created.is2xx(), "POST /roles: " + created);
            long roleId = objectMapper.readTree(created.body()).get("id").asLong();
            Perms reduced = new Perms(set(PermissionEntity.WORK_ORDERS), set(), set(), set(), set());

            HttpResult patched = patchRoleLikeUi(token, tenant.company(), roleId, reduced);

            assertAll(
                    () -> assertTrue(patched.is2xx(), "PATCH /roles/{id}: " + patched),
                    () -> assertEquals(reduced, permsInDb(roleId), "role permissions after the edit"));
        }

        // ----- C11: no one changes their own role -----

        @Test
        @DisplayName("INC-02 C11: a user changing their own role gets 4xx and keeps the role")
        void changeOwnRole_rejected_roleUnchanged() throws Exception {
            Tenant tenant = newTenant("C11");
            Long adminRoleId = roleIdOfUser(tenant.admin().getId());
            Role other = createCustomRole(tenant.company(), "C11 other role", requestViewer);

            HttpResult result = call(patch("/users/" + tenant.admin().getId() + "/role")
                    .param("role", String.valueOf(other.getId())), login(tenant.admin()));

            assertAll(
                    () -> assertTrue(result.is4xx(), "PATCH /users/{self}/role: " + result),
                    () -> assertEquals(adminRoleId, roleIdOfUser(tenant.admin().getId()), "own role must not change"));
        }

        @Test
        @DisplayName("INC-02 C10 (D-6): a manager downgrading a role superior to their own gets 403 and the role is unchanged")
        void lesserManagerDowngradesSuperiorRole_forbidden_roleUnchanged() throws Exception {
            Tenant tenant = newTenant("D6-downgrade");
            Perms roleManager = new Perms(set(PermissionEntity.SETTINGS, PermissionEntity.WORK_ORDERS), set(), set(),
                    set(), set());
            User manager = createUser(tenant.company(), createCustomRole(tenant.company(), "D6 manager", roleManager));
            Perms superiorPerms = new Perms(set(PermissionEntity.WORK_ORDERS), set(), set(),
                    set(PermissionEntity.WORK_ORDERS), set(PermissionEntity.WORK_ORDERS));
            Role superior = createCustomRole(tenant.company(), "D6 superior", superiorPerms);
            // Every set sent is within the manager's role: only the current role is superior.
            Perms downgraded = new Perms(set(PermissionEntity.WORK_ORDERS), set(), set(), set(), set());

            HttpResult result = patchRoleLikeUi(login(manager), tenant.company(), superior.getId(), downgraded);

            assertAll(
                    () -> assertEquals(403, result.status(), "PATCH /roles/{superiorId}: " + result),
                    () -> assertEquals(superiorPerms, permsInDb(superior.getId()), "superior role must not change"));
        }

        @Test
        @DisplayName("INC-02 C10 (D-11): a manager deleting a role superior to their own gets 403 and the role still exists")
        void lesserManagerDeletesSuperiorRole_forbidden_roleKept() throws Exception {
            Tenant tenant = newTenant("D11-delete");
            Perms roleManager = new Perms(set(PermissionEntity.SETTINGS, PermissionEntity.WORK_ORDERS), set(), set(),
                    set(), set());
            User manager = createUser(tenant.company(), createCustomRole(tenant.company(), "D11 manager", roleManager));
            Role superior = createCustomRole(tenant.company(), "D11 superior", new Perms(set(PermissionEntity.WORK_ORDERS),
                    set(), set(), set(PermissionEntity.WORK_ORDERS), set()));

            HttpResult result = call(delete("/roles/" + superior.getId()), login(manager));

            assertAll(
                    () -> assertEquals(403, result.status(), "DELETE /roles/{superiorId}: " + result),
                    () -> assertTrue(roleRepository.existsById(superior.getId()), "superior role must still exist"));
        }

        @Test
        @DisplayName("INC-02 C9 (D-11): a manager deletes an unused role within their own permissions (2xx)")
        void managerDeletesRoleWithinOwnPermissions_succeeds() throws Exception {
            Tenant tenant = newTenant("D11-delete-ok");
            Perms roleManager = new Perms(set(PermissionEntity.SETTINGS, PermissionEntity.WORK_ORDERS), set(), set(),
                    set(), set());
            User manager = createUser(tenant.company(), createCustomRole(tenant.company(), "D11 manager", roleManager));
            Role lesser = createCustomRole(tenant.company(), "D11 lesser", new Perms(set(PermissionEntity.WORK_ORDERS),
                    set(), set(), set(), set()));

            HttpResult result = call(delete("/roles/" + lesser.getId()), login(manager));

            assertAll(
                    () -> assertTrue(result.is2xx(), "DELETE /roles/{lesserId}: " + result),
                    () -> assertFalse(roleRepository.existsById(lesser.getId()), "role must be gone"));
        }

        // ----- T-04: role assignment and invitation only with roles the requester could grant -----

        private final Perms peopleManager = new Perms(
                set(PermissionEntity.PEOPLE_AND_TEAMS), set(), set(PermissionEntity.PEOPLE_AND_TEAMS),
                set(PermissionEntity.PEOPLE_AND_TEAMS), set());
        private final Perms peopleViewer = new Perms(set(PermissionEntity.PEOPLE_AND_TEAMS), set(), set(), set(), set());

        @Test
        @DisplayName("INC-02 C10 (T-04): assigning a role with permissions the requester lacks returns 403 and the role is unchanged")
        void assignRoleWithUnheldPermissions_forbidden_roleUnchanged() throws Exception {
            Tenant tenant = newTenant("T04-assign-403");
            User manager = createUser(tenant.company(), createCustomRole(tenant.company(), "T04 manager", peopleManager));
            Role viewerRole = createCustomRole(tenant.company(), "T04 viewer", peopleViewer);
            User target = createUser(tenant.company(), viewerRole);
            Long adminRoleId = roleIdOfUser(tenant.admin().getId());

            HttpResult result = call(patch("/users/" + target.getId() + "/role")
                    .param("role", String.valueOf(adminRoleId)), login(manager));

            assertAll(
                    () -> assertEquals(403, result.status(), "PATCH /users/{id}/role: " + result),
                    () -> assertEquals(viewerRole.getId(), roleIdOfUser(target.getId()), "target role must not change"));
        }

        @Test
        @DisplayName("INC-02 C10 (T-04): assigning a role within the requester's permissions succeeds")
        void assignRoleWithinHeldPermissions_succeeds() throws Exception {
            Tenant tenant = newTenant("T04-assign-ok");
            User manager = createUser(tenant.company(), createCustomRole(tenant.company(), "T04 manager", peopleManager));
            Role viewerRole = createCustomRole(tenant.company(), "T04 viewer", peopleViewer);
            // D-21: the current role of the target is within the manager's too.
            User target = createUser(tenant.company(), createCustomRole(tenant.company(), "T04 other",
                    new Perms(set(PermissionEntity.PEOPLE_AND_TEAMS), set(), set(PermissionEntity.PEOPLE_AND_TEAMS),
                            set(), set())));

            HttpResult result = call(patch("/users/" + target.getId() + "/role")
                    .param("role", String.valueOf(viewerRole.getId())), login(manager));

            assertAll(
                    () -> assertTrue(result.is2xx(), "PATCH /users/{id}/role: " + result),
                    () -> assertEquals(viewerRole.getId(), roleIdOfUser(target.getId()), "target role must change"));
        }

        @Test
        @DisplayName("INC-02 C10 (T-04): inviting with a role whose permissions the requester lacks returns 403 and invites nobody")
        void inviteWithUnheldPermissions_forbidden_nothingPersisted() throws Exception {
            Tenant tenant = newTenant("T04-invite-403");
            User manager = createUser(tenant.company(), createCustomRole(tenant.company(), "T04 manager", peopleManager));
            Long adminRoleId = roleIdOfUser(tenant.admin().getId());
            String email = "inc02-invite-" + UUID.randomUUID() + "@example.com";
            try {
                HttpResult result = invite(login(manager), adminRoleId, email);

                assertAll(
                        () -> assertEquals(403, result.status(), "POST /users/invite: " + result),
                        () -> assertEquals(0, invitationsOf(email), "no invitation must be persisted"));
            } finally {
                jdbcTemplate.update("DELETE FROM user_invitation WHERE email = ?", email);
            }
        }

        @Test
        @DisplayName("INC-02 C10 (T-04): inviting with a role within the requester's permissions succeeds")
        void inviteWithinHeldPermissions_succeeds() throws Exception {
            Tenant tenant = newTenant("T04-invite-ok");
            User manager = createUser(tenant.company(), createCustomRole(tenant.company(), "T04 manager", peopleManager));
            Role viewerRole = createCustomRole(tenant.company(), "T04 viewer", peopleViewer);
            String email = "inc02-invite-" + UUID.randomUUID() + "@example.com";
            try {
                HttpResult result = invite(login(manager), viewerRole.getId(), email);

                assertAll(
                        () -> assertTrue(result.is2xx(), "POST /users/invite: " + result),
                        () -> assertEquals(1, invitationsOf(email), "the invitation must be persisted"));
            } finally {
                jdbcTemplate.update("DELETE FROM user_invitation WHERE email = ?", email);
            }
        }

        private HttpResult invite(String token, Long roleId, String email) throws Exception {
            ObjectNode body = objectMapper.createObjectNode().put("disableSendingEmail", true);
            body.putObject("role").put("id", roleId);
            body.putArray("emails").add(email);
            return call(post("/users/invite").contentType(MediaType.APPLICATION_JSON).content(json(body)), token);
        }

        private long invitationsOf(String email) {
            Long result = jdbcTemplate.queryForObject("SELECT count(*) FROM user_invitation WHERE email = ?",
                    Long.class, email);
            return result == null ? 0 : result;
        }

        // ----- C12: multi-company isolation -----

        @Test
        @DisplayName("INC-02 C12: a user of company A cannot read a role of company B (4xx) nor see it in the list")
        void otherCompanyRole_read_rejected() throws Exception {
            Tenant tenantA = newTenant("C12-read-A");
            Tenant tenantB = newTenant("C12-read-B");
            Role roleB = createCustomRole(tenantB.company(), "C12 role B", requestViewer);
            String tokenA = login(tenantA.admin());

            HttpResult single = call(get("/roles/" + roleB.getId()), tokenA);
            HttpResult list = call(get("/roles"), tokenA);

            assertAll(
                    () -> assertTrue(single.is4xx(), "GET /roles/{idB}: " + single),
                    () -> assertTrue(list.is2xx(), "GET /roles: " + list),
                    () -> assertFalse(idsIn(list.body()).contains(roleB.getId()), "GET /roles must not list role B"));
        }

        @Test
        @DisplayName("INC-02 C12: a user of company A cannot edit a role of company B (4xx) and the role is unchanged")
        void otherCompanyRole_edit_rejected() throws Exception {
            Tenant tenantA = newTenant("C12-edit-A");
            Tenant tenantB = newTenant("C12-edit-B");
            Role roleB = createCustomRole(tenantB.company(), "C12 role B", requestViewer);
            Perms everything = new Perms(all(), all(), all(), all(), all());

            HttpResult result = patchRoleLikeUi(login(tenantA.admin()), tenantA.company(), roleB.getId(), everything);

            assertAll(
                    () -> assertTrue(result.is4xx(), "PATCH /roles/{idB}: " + result),
                    () -> assertEquals(requestViewer, permsInDb(roleB.getId()), "role B must not change"));
        }

        @Test
        @DisplayName("INC-02 C12: a user of company A cannot delete a role of company B (4xx) and the role still exists")
        void otherCompanyRole_delete_rejected() throws Exception {
            Tenant tenantA = newTenant("C12-delete-A");
            Tenant tenantB = newTenant("C12-delete-B");
            Role roleB = createCustomRole(tenantB.company(), "C12 role B", requestViewer);

            HttpResult result = call(delete("/roles/" + roleB.getId()), login(tenantA.admin()));

            assertAll(
                    () -> assertTrue(result.is4xx(), "DELETE /roles/{idB}: " + result),
                    () -> assertTrue(roleRepository.existsById(roleB.getId()), "role B must still exist"));
        }
    }

    // =====================================================================================================
    // R-01 (D-21): nobody manages a user whose current role is superior to their own, nor the company owner
    // =====================================================================================================

    @Nested
    @DisplayName("INC-02 user management (D-21)")
    class UserManagementTests {

        private final Perms peopleManager = new Perms(
                set(PermissionEntity.PEOPLE_AND_TEAMS, PermissionEntity.SETTINGS), set(),
                set(PermissionEntity.PEOPLE_AND_TEAMS), set(PermissionEntity.PEOPLE_AND_TEAMS), set());
        private final Perms lesser = new Perms(set(PermissionEntity.PEOPLE_AND_TEAMS), set(), set(), set(), set());
        // Superior to peopleManager only in its delete set.
        private final Perms superior = new Perms(set(PermissionEntity.PEOPLE_AND_TEAMS), set(), set(), set(),
                set(PermissionEntity.WORK_ORDERS));

        private record Scenario(Tenant tenant, User manager, Role lesserRole) {
        }

        private Scenario scenario(String label) {
            Tenant tenant = newTenant(label);
            User manager = createUser(tenant.company(), createCustomRole(tenant.company(), label + " manager",
                    peopleManager));
            return new Scenario(tenant, manager, createCustomRole(tenant.company(), label + " lesser", lesser));
        }

        private User owner(Scenario scenario) {
            User owner = createUser(scenario.tenant().company(), scenario.lesserRole());
            owner.setOwnsCompany(true);
            return userRepository.save(owner);
        }

        @Test
        @DisplayName("INC-02 D-21: changing the role of a user whose current role is superior returns 403 and keeps it")
        void patchRole_currentRoleSuperior_forbidden() throws Exception {
            Scenario s = scenario("D21-role-superior");
            Role superiorRole = createCustomRole(s.tenant().company(), "D21 superior", superior);
            User target = createUser(s.tenant().company(), superiorRole);

            // The resulting role is within the manager's: only the current one is superior.
            HttpResult result = call(patch("/users/" + target.getId() + "/role")
                    .param("role", String.valueOf(s.lesserRole().getId())), login(s.manager()));

            assertAll(
                    () -> assertEquals(403, result.status(), "PATCH /users/{id}/role: " + result),
                    () -> assertEquals(superiorRole.getId(), roleIdOfUser(target.getId()), "role must not change"));
        }

        @Test
        @DisplayName("INC-02 D-21: changing the role of the company owner returns 403 and keeps it")
        void patchRole_owner_forbidden() throws Exception {
            Scenario s = scenario("D21-role-owner");
            User owner = owner(s);
            Role other = createCustomRole(s.tenant().company(), "D21 other", lesser);

            HttpResult result = call(patch("/users/" + owner.getId() + "/role")
                    .param("role", String.valueOf(other.getId())), login(s.manager()));

            assertAll(
                    () -> assertEquals(403, result.status(), "PATCH /users/{ownerId}/role: " + result),
                    () -> assertEquals(s.lesserRole().getId(), roleIdOfUser(owner.getId()), "role must not change"));
        }

        @Test
        @DisplayName("INC-02 D-21: changing the password of a superior user returns 403 and keeps it")
        void patchPassword_superiorUser_forbidden() throws Exception {
            Scenario s = scenario("D21-password-superior");
            User target = createUser(s.tenant().company(), createCustomRole(s.tenant().company(), "D21 superior",
                    superior));

            HttpResult result = changePassword(login(s.manager()), target);

            assertAll(
                    () -> assertEquals(403, result.status(), "PATCH /users/{id}: " + result),
                    () -> assertTrue(passwordUnchanged(target), "password must not change"));
        }

        @Test
        @DisplayName("INC-02 D-21: changing the password of the company owner returns 403 and keeps it")
        void patchPassword_owner_forbidden() throws Exception {
            Scenario s = scenario("D21-password-owner");
            User owner = owner(s);

            HttpResult result = changePassword(login(s.manager()), owner);

            assertAll(
                    () -> assertEquals(403, result.status(), "PATCH /users/{ownerId}: " + result),
                    () -> assertTrue(passwordUnchanged(owner), "password must not change"));
        }

        @Test
        @DisplayName("INC-02 D-21: changing the password of a lesser user succeeds")
        void patchPassword_lesserUser_succeeds() throws Exception {
            Scenario s = scenario("D21-password-ok");
            User target = createUser(s.tenant().company(), s.lesserRole());

            HttpResult result = changePassword(login(s.manager()), target);

            assertAll(
                    () -> assertUserResponse(result, target, "PATCH /users/{id}"),
                    () -> assertFalse(passwordUnchanged(target), "password must change"));
        }

        @Test
        @DisplayName("INC-02 D-21: disabling a superior user or the company owner returns 403 and keeps them enabled")
        void disable_superiorOrOwner_forbidden() throws Exception {
            Scenario s = scenario("D21-disable");
            User target = createUser(s.tenant().company(), createCustomRole(s.tenant().company(), "D21 superior",
                    superior));
            User owner = owner(s);
            String token = login(s.manager());

            HttpResult superiorResult = call(patch("/users/" + target.getId() + "/disable"), token);
            HttpResult ownerResult = call(patch("/users/" + owner.getId() + "/disable"), token);

            assertAll(
                    () -> assertEquals(403, superiorResult.status(), "PATCH /users/{id}/disable: " + superiorResult),
                    () -> assertEquals(403, ownerResult.status(), "PATCH /users/{ownerId}/disable: " + ownerResult),
                    () -> assertTrue(isEnabled(target), "superior user must stay enabled"),
                    () -> assertTrue(isEnabled(owner), "owner must stay enabled"));
        }

        @Test
        @DisplayName("INC-02 D-21: disabling a lesser user succeeds")
        void disable_lesserUser_succeeds() throws Exception {
            Scenario s = scenario("D21-disable-ok");
            User target = createUser(s.tenant().company(), s.lesserRole());

            HttpResult result = call(patch("/users/" + target.getId() + "/disable"), login(s.manager()));

            assertAll(
                    () -> assertUserResponse(result, target, "PATCH /users/{id}/disable"),
                    () -> assertFalse(isEnabled(target), "user must be disabled"));
        }

        @Test
        @DisplayName("INC-02 D-21: deleting a superior user or the company owner returns 403 and keeps them")
        void softDelete_superiorOrOwner_forbidden() throws Exception {
            Scenario s = scenario("D21-delete");
            User target = createUser(s.tenant().company(), createCustomRole(s.tenant().company(), "D21 superior",
                    superior));
            User owner = owner(s);
            String token = login(s.manager());

            HttpResult superiorResult = call(patch("/users/soft-delete/" + target.getId()), token);
            HttpResult ownerResult = call(patch("/users/soft-delete/" + owner.getId()), token);

            assertAll(
                    () -> assertEquals(403, superiorResult.status(), "PATCH /users/soft-delete/{id}: " + superiorResult),
                    () -> assertEquals(403, ownerResult.status(), "PATCH /users/soft-delete/{ownerId}: " + ownerResult),
                    () -> assertTrue(isEnabled(target), "superior user must be kept"),
                    () -> assertTrue(isEnabled(owner), "owner must be kept"));
        }

        @Test
        @DisplayName("INC-02 D-21: deleting a lesser user succeeds")
        void softDelete_lesserUser_succeeds() throws Exception {
            Scenario s = scenario("D21-delete-ok");
            User target = createUser(s.tenant().company(), s.lesserRole());

            HttpResult result = call(patch("/users/soft-delete/" + target.getId()), login(s.manager()));

            assertAll(
                    () -> assertUserResponse(result, target, "PATCH /users/soft-delete/{id}"),
                    () -> assertFalse(isEnabled(target), "user must be deleted"));
        }

        @Test
        @DisplayName("INC-02 D-23: enabling a superior user or the company owner returns 403 and keeps them disabled")
        void enable_superiorOrOwner_forbidden() throws Exception {
            Scenario s = scenario("D23-enable");
            User target = disabled(createUser(s.tenant().company(), createCustomRole(s.tenant().company(),
                    "D23 superior", superior)));
            User owner = disabled(owner(s));
            String token = login(s.manager());

            HttpResult superiorResult = call(patch("/users/" + target.getId() + "/enable"), token);
            HttpResult ownerResult = call(patch("/users/" + owner.getId() + "/enable"), token);

            assertAll(
                    () -> assertEquals(403, superiorResult.status(), "PATCH /users/{id}/enable: " + superiorResult),
                    () -> assertEquals(403, ownerResult.status(), "PATCH /users/{ownerId}/enable: " + ownerResult),
                    () -> assertFalse(isEnabled(target), "superior user must stay disabled"),
                    () -> assertFalse(isEnabled(owner), "owner must stay disabled"));
        }

        @Test
        @DisplayName("INC-02 D-23: enabling a lesser user succeeds")
        void enable_lesserUser_succeeds() throws Exception {
            Scenario s = scenario("D23-enable-ok");
            User target = disabled(createUser(s.tenant().company(), s.lesserRole()));

            HttpResult result = call(patch("/users/" + target.getId() + "/enable"), login(s.manager()));

            assertAll(
                    () -> assertUserResponse(result, target, "PATCH /users/{id}/enable"),
                    () -> assertTrue(isEnabled(target), "user must be enabled"));
        }

        @Test
        @DisplayName("INC-02 R-09: a people manager without the settings permission lists the roles of their company only")
        void listRoles_peopleManagerWithoutSettings_ownCompanyOnly() throws Exception {
            Tenant tenant = newTenant("R09-roles");
            Tenant other = newTenant("R09-roles-other");
            Role otherRole = createCustomRole(other.company(), "R09 other company", lesser);
            String manager = login(createUser(tenant.company(), createCustomRole(tenant.company(), "R09 manager",
                    new Perms(set(PermissionEntity.PEOPLE_AND_TEAMS), set(), set(),
                            set(PermissionEntity.PEOPLE_AND_TEAMS), set()))));
            String viewer = login(createUser(tenant.company(), createCustomRole(tenant.company(), "R09 viewer",
                    lesser)));

            HttpResult managerList = call(get("/roles"), manager);
            HttpResult viewerList = call(get("/roles"), viewer);

            assertTrue(managerList.is2xx(), "GET /roles by a people manager: " + managerList);
            Set<String> names = new HashSet<>();
            objectMapper.readTree(managerList.body()).forEach(role -> names.add(role.path("name").asText()));
            assertAll(
                    () -> assertTrue(names.containsAll(Set.of("R09 manager", "R09 viewer")), "own roles: " + names),
                    () -> assertFalse(idsIn(managerList.body()).contains(otherRole.getId()),
                            "roles of another company must not be listed"),
                    () -> assertEquals(403, viewerList.status(), "GET /roles without settings nor people management: "
                            + viewerList));
        }

        @Test
        @DisplayName("INC-02 D-24: a user who invites people without the settings permission lists the roles of their company only")
        void listRoles_inviterWithoutSettings_ownCompanyOnly() throws Exception {
            Tenant tenant = newTenant("D24-roles");
            Tenant other = newTenant("D24-roles-other");
            Role otherRole = createCustomRole(other.company(), "D24 other company", lesser);
            Role ownRole = createCustomRole(tenant.company(), "D24 own", lesser);
            String inviter = login(createUser(tenant.company(), createCustomRole(tenant.company(), "D24 inviter",
                    new Perms(set(PermissionEntity.PEOPLE_AND_TEAMS), set(), set(PermissionEntity.PEOPLE_AND_TEAMS),
                            set(), set()))));

            HttpResult result = call(get("/roles"), inviter);

            assertTrue(result.is2xx(), "GET /roles by a user who invites people: " + result);
            Set<Long> ids = idsIn(result.body());
            assertAll(
                    () -> assertTrue(ids.contains(ownRole.getId()), "own roles must be listed"),
                    () -> assertFalse(ids.contains(otherRole.getId()), "roles of another company must not be listed"));
        }

        @Test
        @DisplayName("INC-02 R-08: PATCH /user-settings/{id} cannot change the settings of another user through the body id")
        void patchUserSettings_otherIdInBody_otherUserUnchanged() throws Exception {
            Scenario s = scenario("R08-settings");
            User other = createUser(s.tenant().company(), s.lesserRole());
            Long ownId = inTx(() -> userRepository.findById(s.manager().getId()).orElseThrow().getUserSettings().getId());
            Long otherId = inTx(() -> userRepository.findById(other.getId()).orElseThrow().getUserSettings().getId());
            ObjectNode body = objectMapper.createObjectNode().put("id", otherId).put("emailNotified", false);

            HttpResult result = call(patch("/user-settings/" + ownId).contentType(MediaType.APPLICATION_JSON)
                    .content(json(body)), login(s.manager()));

            assertAll(
                    () -> assertTrue(result.is2xx(), "PATCH /user-settings/{ownId}: " + result),
                    () -> assertTrue(emailNotified(otherId), "the settings of the other user must not change"),
                    () -> assertFalse(emailNotified(ownId), "the own settings change"));
        }

        private boolean emailNotified(Long userSettingsId) {
            Boolean result = jdbcTemplate.queryForObject("SELECT email_notified FROM user_settings WHERE id = ?",
                    Boolean.class, userSettingsId);
            return Boolean.TRUE.equals(result);
        }

        private HttpResult changePassword(String token, User target) throws Exception {
            // Same body the web UI sends (People.tsx): the whole user plus the new password.
            ObjectNode body = objectMapper.createObjectNode().put("firstName", target.getFirstName())
                    .put("lastName", target.getLastName()).put("newPassword", "D21-New-Passw0rd!");
            return call(patch("/users/" + target.getId()).contentType(MediaType.APPLICATION_JSON).content(json(body)),
                    token);
        }

        /**
         * INC-02 (E-06, F-09): an authorized change answers 200 with the user, including its lazy role, as in
         * production.
         */
        private void assertUserResponse(HttpResult result, User target, String endpoint) throws Exception {
            assertEquals(200, result.status(), endpoint + ": " + result);
            JsonNode user = objectMapper.readTree(result.body());
            assertEquals(target.getId().longValue(), user.path("id").asLong(), endpoint + " returns the user");
            assertEquals(roleIdOfUser(target.getId()).longValue(), user.path("role").path("id").asLong(),
                    endpoint + " returns the role of the user");
        }

        private boolean passwordUnchanged(User user) {
            return passwordEncoder.matches(PASSWORD, inTx(() -> userRepository.findById(user.getId()).orElseThrow()
                    .getPassword()));
        }

        private boolean isEnabled(User user) {
            return inTx(() -> userRepository.findById(user.getId()).orElseThrow().isEnabled());
        }

        private User disabled(User user) {
            User saved = userRepository.findById(user.getId()).orElseThrow();
            saved.setEnabled(false);
            saved.setEnabledInSubscription(false);
            return userRepository.save(saved);
        }
    }

    // =====================================================================================================
    // R-10: role errors carry a stable code so the UI tells them apart
    // =====================================================================================================

    @Nested
    @DisplayName("INC-02 role error codes (R-10)")
    class RoleErrorCodeTests {

        @Test
        @DisplayName("INC-02 R-10: each rejected role operation answers with the code of its rule")
        void roleErrors_haveDistinctCodes() throws Exception {
            Tenant tenant = newTenant("R10-codes");
            Tenant other = newTenant("R10-codes-other");
            String manager = login(createUser(tenant.company(), createCustomRole(tenant.company(), "R10 manager",
                    new Perms(set(PermissionEntity.SETTINGS, PermissionEntity.WORK_ORDERS), set(), set(), set(),
                            set()))));
            String withoutSettings = login(createUser(tenant.company(), createCustomRole(tenant.company(),
                    "R10 no settings", new Perms(set(PermissionEntity.WORK_ORDERS), set(), set(), set(), set()))));
            Perms lesser = new Perms(set(PermissionEntity.WORK_ORDERS), set(), set(), set(), set());
            Role superior = createCustomRole(tenant.company(), "R10 superior",
                    new Perms(set(PermissionEntity.WORK_ORDERS), set(), set(), set(PermissionEntity.WORK_ORDERS), set()));
            Role assigned = createCustomRole(tenant.company(), "R10 assigned", lesser);
            createUser(tenant.company(), assigned);
            Role otherCompanyRole = createCustomRole(other.company(), "R10 other company", lesser);
            Role edited = createCustomRole(tenant.company(), "R10 edited", lesser);
            Long defaultRoleId = roleIdOfUser(tenant.admin().getId());

            HttpResult noSettings = call(delete("/roles/" + superior.getId()), withoutSettings);
            HttpResult defaultRole = call(delete("/roles/" + defaultRoleId), manager);
            HttpResult otherCompany = call(delete("/roles/" + otherCompanyRole.getId()), manager);
            HttpResult superiorRole = call(delete("/roles/" + superior.getId()), manager);
            HttpResult inUse = call(delete("/roles/" + assigned.getId()), manager);
            ObjectNode staleRead = (ObjectNode) objectMapper.readTree(call(get("/roles/" + edited.getId()), manager)
                    .body());
            assertTrue(patchRoleLikeUi(manager, tenant.company(), edited.getId(), lesser).is2xx(),
                    "precondition, the role changes after it was read");
            HttpResult stale = call(patch("/roles/" + edited.getId()).contentType(MediaType.APPLICATION_JSON)
                    .content(json(staleRead)), manager);

            assertAll(
                    () -> assertError(noSettings, 403, "SETTINGS_REQUIRED"),
                    () -> assertError(defaultRole, 403, "ROLE_DEFAULT"),
                    () -> assertError(otherCompany, 403, "ROLE_NOT_IN_COMPANY"),
                    () -> assertError(superiorRole, 403, "ROLE_SUPERIOR"),
                    () -> assertError(inUse, 409, "ROLE_IN_USE"),
                    () -> assertError(stale, 409, "ROLE_VERSION_STALE"));
        }

        private void assertError(HttpResult result, int status, String code) throws Exception {
            assertEquals(status, result.status(), code + ": " + result);
            assertEquals(code, objectMapper.readTree(result.body()).path("code").asText(), code + ": " + result);
        }
    }

    // =====================================================================================================
    // R-02: a new request always starts pending; its review state belongs to the server
    // =====================================================================================================

    @Nested
    @DisplayName("INC-02 request creation (R-02)")
    class RequestCreationTests {

        private final Perms requestCreator = new Perms(
                set(PermissionEntity.REQUESTS), set(), set(PermissionEntity.REQUESTS), set(), set());

        @Test
        @DisplayName("INC-02 R-02: POST /requests ignores the id and the review state sent and creates a pending request")
        void create_withReviewState_startsPending() throws Exception {
            Tenant tenant = newTenant("R02-create");
            User creator = createUser(tenant.company(), createCustomRole(tenant.company(), "R02 creator",
                    requestCreator));
            Request existing = createRequest(tenant.company(), tenant.admin(), null);

            HttpResult result = call(post("/requests").contentType(MediaType.APPLICATION_JSON)
                    .content(json(forgedRequest(tenant, existing, "R02 forged"))), login(creator));

            assertCreatedPending(result, existing);
        }

        @Test
        @DisplayName("INC-02 R-02: the request portal ignores the id and the review state sent and creates a pending request")
        void createFromPortal_withReviewState_startsPending() throws Exception {
            Tenant tenant = newTenant("R02-portal");
            Request existing = createRequest(tenant.company(), tenant.admin(), null);
            SecurityContextHolder.clearContext();
            RequestPortal portal = new RequestPortal();
            portal.setTitle("R02 portal");
            portal.setUuid(UUID.randomUUID().toString());
            portal.setCompany(tenant.company());
            portal = requestPortalRepository.save(portal);
            try {
                // The test profile requires a reCAPTCHA token on the portal endpoint, so the body the endpoint
                // deserializes goes straight to the service, where the rule lives.
                Request forged = objectMapper.treeToValue(forgedRequest(tenant, existing, "R02 portal forged"),
                        Request.class);
                RequestPortal savedPortal = portal;
                Request created = inTx(() -> requestService.create(forged, tenant.company(), savedPortal));
                SecurityContextHolder.clearContext();

                assertCreatedPending(new HttpResult(200, json(Map.of("id", created.getId()))), existing);
            } finally {
                // request_portal.company_id has no ON DELETE CASCADE; request.request_portal_id is SET NULL.
                jdbcTemplate.update("DELETE FROM request_portal WHERE id = ?", portal.getId());
            }
        }

        @Test
        @DisplayName("INC-02 R-06: the pending request counter follows the detail view of requests")
        void pendingCount_followsDetailView() throws Exception {
            Tenant tenant = newTenant("R06-pending");
            User creator = createUser(tenant.company(), createCustomRole(tenant.company(), "R06 creator",
                    requestCreator));
            createRequest(tenant.company(), tenant.admin(), null);
            createRequest(tenant.company(), tenant.admin(), null);
            createRequest(tenant.company(), creator, null);

            HttpResult own = call(get("/requests/pending"), login(creator));
            HttpResult all = call(get("/requests/pending"), login(tenant.admin()));

            assertAll(
                    () -> assertEquals("1", objectMapper.readTree(own.body()).path("message").asText(),
                            "without view on other requests, only the own pending request: " + own),
                    () -> assertEquals("3", objectMapper.readTree(all.body()).path("message").asText(),
                            "with view on other requests, every pending request: " + all));
        }

        private ObjectNode forgedRequest(Tenant tenant, Request existing, String title) {
            WorkOrder workOrder = createWorkOrder(tenant.company(), tenant.admin(), null);
            ObjectNode body = objectMapper.createObjectNode()
                    .put("title", title)
                    .put("id", existing.getId())
                    .put("cancelled", true)
                    .put("cancellationReason", "R02 forged rejection");
            body.putObject("workOrder").put("id", workOrder.getId());
            return body;
        }

        private void assertCreatedPending(HttpResult result, Request existing) throws Exception {
            assertTrue(result.is2xx(), "request creation: " + result);
            long id = objectMapper.readTree(result.body()).get("id").asLong();
            assertAll(
                    () -> assertNotEquals(existing.getId(), id, "the id sent must be ignored"),
                    () -> assertFalse(isCancelled(id), "new request must not be rejected"),
                    () -> assertNull(inTx(() -> requestRepository.findById(id).orElseThrow().getCancellationReason()),
                            "new request must have no rejection reason"),
                    () -> assertNull(workOrderIdOf(id), "new request must not be approved"),
                    () -> assertEquals(existing.getTitle(), inTx(() -> requestRepository.findById(existing.getId())
                            .orElseThrow().getTitle()), "the existing request must not change"),
                    () -> assertFalse(isCancelled(existing.getId()), "the existing request must stay pending"));
        }
    }

    // =====================================================================================================
    // T-11: every path that leaves a work order COMPLETE follows the change-status rule (Codex A-09)
    // =====================================================================================================

    @Nested
    @DisplayName("INC-02 work order closing paths")
    class WorkOrderClosingTests {

        private final Perms creatorWithoutEditOther = new Perms(
                set(PermissionEntity.WORK_ORDERS), set(), set(PermissionEntity.WORK_ORDERS), set(), set());
        private final Perms creatorWithEditOther = new Perms(
                set(PermissionEntity.WORK_ORDERS), set(PermissionEntity.WORK_ORDERS), set(PermissionEntity.WORK_ORDERS),
                set(PermissionEntity.WORK_ORDERS), set());

        @Test
        @DisplayName("INC-02 C6 (T-11): POST /work-orders as COMPLETE gets closing data from the server, not from the client")
        void createComplete_closingDataSetByServer() throws Exception {
            Tenant tenant = newTenant("T11-post-complete");
            User other = createUser(tenant.company(), createCustomRole(tenant.company(), "T11 other",
                    creatorWithoutEditOther));
            Date before = new Date();
            ObjectNode body = objectMapper.createObjectNode().put("title", "T11 complete").put("status", "COMPLETE")
                    .put("completedOn", "2020-01-01T00:00:00.000+00:00");
            body.putObject("completedBy").put("id", other.getId());

            HttpResult result = call(post("/work-orders").contentType(MediaType.APPLICATION_JSON).content(json(body)),
                    login(tenant.admin()));

            assertTrue(result.is2xx(), "POST /work-orders: " + result);
            long id = objectMapper.readTree(result.body()).get("id").asLong();
            ClosingData closing = closingDataOf(id);
            assertAll(
                    () -> assertEquals(Status.COMPLETE, closing.status()),
                    () -> assertEquals(tenant.admin().getId(), closing.completedById(), "completedBy must be the requester"),
                    () -> assertNotNull(closing.completedOn(), "completedOn must be set by the server"),
                    () -> assertFalse(closing.completedOn().before(new Date(before.getTime() - 60_000)),
                            "completedOn must be the server time, not the client one: " + closing.completedOn()));
        }

        @Test
        @DisplayName("INC-02 C6 (T-11): POST /work-orders not COMPLETE ignores client closing data")
        void createOpen_clientClosingDataIgnored() throws Exception {
            Tenant tenant = newTenant("T11-post-open");
            ObjectNode body = objectMapper.createObjectNode().put("title", "T11 open")
                    .put("completedOn", "2020-01-01T00:00:00.000+00:00");
            body.putObject("completedBy").put("id", tenant.admin().getId());

            HttpResult result = call(post("/work-orders").contentType(MediaType.APPLICATION_JSON).content(json(body)),
                    login(tenant.admin()));

            assertTrue(result.is2xx(), "POST /work-orders: " + result);
            ClosingData closing = closingDataOf(objectMapper.readTree(result.body()).get("id").asLong());
            assertAll(
                    () -> assertEquals(Status.OPEN, closing.status()),
                    () -> assertNull(closing.completedById(), "completedBy must stay empty"),
                    () -> assertNull(closing.completedOn(), "completedOn must stay empty"));
        }

        @Test
        @DisplayName("INC-02 C6 (T-11): the generic PATCH cannot write closing data")
        void patch_closingDataIgnored() throws Exception {
            Tenant tenant = newTenant("T11-patch");
            WorkOrder workOrder = createWorkOrder(tenant.company(), tenant.admin(), null);
            ObjectNode body = objectMapper.createObjectNode().put("title", "T11 edited")
                    .put("completedOn", "2020-01-01T00:00:00.000+00:00");
            body.putObject("completedBy").put("id", tenant.admin().getId());

            HttpResult result = call(patch("/work-orders/" + workOrder.getId()).contentType(MediaType.APPLICATION_JSON)
                    .content(json(body)), login(tenant.admin()));

            ClosingData closing = closingDataOf(workOrder.getId());
            assertAll(
                    () -> assertTrue(result.is2xx(), "PATCH /work-orders/{id}: " + result),
                    () -> assertNull(closing.completedById(), "completedBy must stay empty"),
                    () -> assertNull(closing.completedOn(), "completedOn must stay empty"));
        }

        @Test
        @DisplayName("INC-02 C6 (T-11): importing a COMPLETE work order without edit-other on work orders is rejected and imports nothing")
        void importComplete_withoutEditOther_forbidden_nothingImported() {
            Tenant tenant = newTenant("T11-import-403");
            User importer = createUser(tenant.company(),
                    createCustomRole(tenant.company(), "T11 importer", creatorWithoutEditOther));
            WorkOrderImportDTO open = WorkOrderImportDTO.builder().title("T11 import open").status("Open").build();
            WorkOrderImportDTO complete = WorkOrderImportDTO.builder().title("T11 import complete").status("Complete")
                    .completedByEmail(tenant.admin().getEmail()).build();

            CustomException ex = assertThrows(CustomException.class,
                    () -> importService.importWorkOrders(List.of(open, complete), tenant.company(), importer));

            assertAll(
                    () -> assertEquals(HttpStatus.FORBIDDEN, ex.getHttpStatus()),
                    () -> assertEquals(0, count("SELECT count(*) FROM work_order WHERE company_id = ?",
                            tenant.company().getId()), "no work order must be imported"));
        }

        @Test
        @DisplayName("INC-02 C6 (T-11): importing a COMPLETE work order with edit-other keeps the historical closing user")
        void importComplete_withEditOther_keepsHistoricalCompletedBy() {
            Tenant tenant = newTenant("T11-import-ok");
            User importer = createUser(tenant.company(),
                    createCustomRole(tenant.company(), "T11 importer", creatorWithEditOther));
            WorkOrderImportDTO complete = WorkOrderImportDTO.builder().title("T11 import complete").status("Complete")
                    .completedByEmail(tenant.admin().getEmail()).build();

            importService.importWorkOrders(List.of(complete), tenant.company(), importer);

            Long id = jdbcTemplate.queryForObject("SELECT id FROM work_order WHERE company_id = ?", Long.class,
                    tenant.company().getId());
            ClosingData closing = closingDataOf(id);
            assertAll(
                    () -> assertEquals(Status.COMPLETE, closing.status()),
                    () -> assertEquals(tenant.admin().getId(), closing.completedById(),
                            "historical import keeps the closing user from the file"));
        }

        @Test
        @DisplayName("INC-02 C6 (T-14): importing a COMPLETE work order keeps the closing date of the file")
        void importComplete_keepsCompletedOnFromFile() {
            Tenant tenant = newTenant("T14-import-date");
            User importer = createUser(tenant.company(),
                    createCustomRole(tenant.company(), "T14 importer", creatorWithEditOther));
            double excelDate = 45000.5; // 15/03/2023 12:00 UTC, as an Excel serial date
            WorkOrderImportDTO complete = WorkOrderImportDTO.builder().title("T14 import complete").status("Complete")
                    .completedByEmail(tenant.admin().getEmail()).completedOn(excelDate).build();

            importService.importWorkOrders(List.of(complete), tenant.company(), importer);

            Long id = jdbcTemplate.queryForObject("SELECT id FROM work_order WHERE company_id = ?", Long.class,
                    tenant.company().getId());
            Date expected = new Date(Math.round((excelDate - 25569) * 24 * 60 * 60 * 1000));
            assertEquals(expected.getTime(), closingDataOf(id).completedOn().getTime(),
                    "completedOn must be the date of the file");
        }

        @Test
        @DisplayName("INC-02 C6 (T-11): importing an update of a work order the importer cannot edit is rejected and the work order is unchanged")
        void importUpdate_notEditableByImporter_forbidden_unchanged() {
            Tenant tenant = newTenant("T11-import-update");
            User importer = createUser(tenant.company(),
                    createCustomRole(tenant.company(), "T11 importer", creatorWithoutEditOther));
            WorkOrder existing = createWorkOrder(tenant.company(), tenant.admin(), null);
            WorkOrderImportDTO update = WorkOrderImportDTO.builder().id(existing.getId()).title("T11 overwritten")
                    .status("Open").build();

            CustomException ex = assertThrows(CustomException.class,
                    () -> importService.importWorkOrders(List.of(update), tenant.company(), importer));

            assertAll(
                    () -> assertEquals(HttpStatus.FORBIDDEN, ex.getHttpStatus()),
                    () -> assertEquals(existing.getTitle(), jdbcTemplate.queryForObject(
                            "SELECT title FROM work_order WHERE id = ?", String.class, existing.getId()),
                            "work order must not change"));
        }

    }

    private ClosingData closingDataOf(Long workOrderId) {
        return inTx(() -> {
            WorkOrder workOrder = workOrderRepository.findById(workOrderId).orElseThrow();
            return new ClosingData(workOrder.getStatus(),
                    workOrder.getCompletedBy() == null ? null : workOrder.getCompletedBy().getId(),
                    workOrder.getCompletedOn());
        });
    }

    private record ClosingData(Status status, Long completedById, Date completedOn) {
    }

    // =====================================================================================================
    // T-15: closing requirements of the web UI (WorkOrderDetails.tsx canComplete and signature) on the server
    // =====================================================================================================

    @Nested
    @DisplayName("INC-02 work order closing requirements")
    class WorkOrderClosingRequirementsTests {

        private static final String FILES = "Files are required on Work Order Completion";
        private static final String TASKS = "Tasks must be completed";
        private static final String TIME = "You must log time";
        private static final String PARTS = "No Part has been used in this Work Order";
        private static final String COST = "No Cost information provided in this Work Order";
        private static final String SIGNATURE = "Signature is required";

        @Test
        @DisplayName("INC-02 C6 (T-15): change-status to COMPLETE requires files when completeFiles is REQUIRED")
        void changeStatus_requiredFiles() throws Exception {
            Tenant tenant = newTenant("T15-files");
            requireOnCompletion(tenant.company(), "completeFiles");
            WorkOrder workOrder = createWorkOrder(tenant.company(), tenant.admin(), null);
            String token = login(tenant.admin());

            assertRejected(complete(token, workOrder.getId()), workOrder, FILES);
            attachFile(tenant.company(), tenant.admin(), workOrder);
            assertCompleted(complete(token, workOrder.getId()), workOrder);
        }

        @Test
        @DisplayName("INC-02 C6 (T-15): change-status to COMPLETE requires every task done when completeTasks is REQUIRED")
        void changeStatus_requiredTasks() throws Exception {
            Tenant tenant = newTenant("T15-tasks");
            requireOnCompletion(tenant.company(), "completeTasks");
            WorkOrder workOrder = createWorkOrder(tenant.company(), tenant.admin(), null);
            Task task = addTask(tenant.company(), workOrder);
            String token = login(tenant.admin());

            assertRejected(complete(token, workOrder.getId()), workOrder, TASKS);
            inTx(() -> {
                Task saved = taskRepository.findById(task.getId()).orElseThrow();
                saved.setValue("COMPLETE");
                return taskRepository.save(saved);
            });
            assertCompleted(complete(token, workOrder.getId()), workOrder);
        }

        @Test
        @DisplayName("INC-02 C6 (T-15): change-status to COMPLETE requires logged time when completeTime is REQUIRED")
        void changeStatus_requiredTime() throws Exception {
            Tenant tenant = newTenant("T15-time");
            requireOnCompletion(tenant.company(), "completeTime");
            WorkOrder workOrder = createWorkOrder(tenant.company(), tenant.admin(), null);
            String token = login(tenant.admin());

            assertRejected(complete(token, workOrder.getId()), workOrder, TIME);
            Labor labor = new Labor(tenant.admin(), 0, new Date(), workOrder, false, TimeStatus.STOPPED);
            labor.setDuration(600);
            labor.setCompany(tenant.company());
            laborRepository.save(labor);
            assertCompleted(complete(token, workOrder.getId()), workOrder);
        }

        @Test
        @DisplayName("INC-02 C6 (T-15): change-status to COMPLETE requires a used part when completeParts is REQUIRED")
        void changeStatus_requiredParts() throws Exception {
            Tenant tenant = newTenant("T15-parts");
            requireOnCompletion(tenant.company(), "completeParts");
            WorkOrder workOrder = createWorkOrder(tenant.company(), tenant.admin(), null);
            String token = login(tenant.admin());

            assertRejected(complete(token, workOrder.getId()), workOrder, PARTS);
            Part part = new Part();
            part.setName("T15 part");
            part.setCompany(tenant.company());
            PartQuantity quantity = new PartQuantity(partRepository.save(part), workOrder, null, 1);
            quantity.setCompany(tenant.company());
            partQuantityRepository.save(quantity);
            assertCompleted(complete(token, workOrder.getId()), workOrder);
        }

        @Test
        @DisplayName("INC-02 C6 (T-15): change-status to COMPLETE requires a cost when completeCost is REQUIRED")
        void changeStatus_requiredCost() throws Exception {
            Tenant tenant = newTenant("T15-cost");
            requireOnCompletion(tenant.company(), "completeCost");
            WorkOrder workOrder = createWorkOrder(tenant.company(), tenant.admin(), null);
            String token = login(tenant.admin());

            assertRejected(complete(token, workOrder.getId()), workOrder, COST);
            AdditionalCost cost = new AdditionalCost();
            cost.setDescription("T15 cost");
            cost.setCost(10);
            cost.setWorkOrder(workOrder);
            additionalCostRepository.save(cost);
            assertCompleted(complete(token, workOrder.getId()), workOrder);
        }

        @Test
        @DisplayName("INC-02 C6 (T-15): change-status to COMPLETE requires a signature when the work order requires one")
        void changeStatus_requiredSignature() throws Exception {
            Tenant tenant = newTenant("T15-signature");
            WorkOrder workOrder = createWorkOrder(tenant.company(), tenant.admin(), null);
            inTx(() -> {
                WorkOrder saved = workOrderRepository.findById(workOrder.getId()).orElseThrow();
                saved.setRequiredSignature(true);
                return workOrderRepository.save(saved);
            });
            String token = login(tenant.admin());

            assertRejected(complete(token, workOrder.getId()), workOrder, SIGNATURE);
            ObjectNode signed = objectMapper.createObjectNode().put("status", Status.COMPLETE.name())
                    .put("signature", "data:image/png;base64,iVBORw0KGgo=");
            assertCompleted(call(patch("/work-orders/" + workOrder.getId() + "/change-status")
                    .contentType(MediaType.APPLICATION_JSON).content(json(signed)), token), workOrder);
        }

        @Test
        @DisplayName("INC-02 C6 (T-15): POST /work-orders as COMPLETE is rejected for each closing requirement it cannot meet")
        void createComplete_requirementsChecked() throws Exception {
            Tenant tenant = newTenant("T15-post");
            Map<String, String> requirements = new LinkedHashMap<>();
            requirements.put("completeFiles", FILES);
            requirements.put("completeTime", TIME);
            requirements.put("completeParts", PARTS);
            requirements.put("completeCost", COST);
            List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
            for (Map.Entry<String, String> requirement : requirements.entrySet()) {
                requireOnCompletion(tenant.company(), requirement.getKey());
                HttpResult result = postWorkOrder(login(tenant.admin()), "T15 " + requirement.getKey(), Status.COMPLETE,
                        false, null);
                checks.add(() -> assertEquals(406, result.status(), requirement.getKey() + ": " + result));
                checks.add(() -> assertTrue(result.body().contains(requirement.getValue()),
                        requirement.getKey() + " message: " + result.body()));
                setOnCompletion(tenant.company(), requirement.getKey(), 0);
            }
            String token = login(tenant.admin());
            HttpResult unsigned = postWorkOrder(token, "T15 unsigned", Status.COMPLETE, true, null);
            checks.add(() -> assertEquals(406, unsigned.status(), "requiredSignature without signature: " + unsigned));
            checks.add(() -> assertTrue(unsigned.body().contains(SIGNATURE), "signature message: " + unsigned.body()));
            HttpResult signed = postWorkOrder(token, "T15 signed", Status.COMPLETE, true,
                    "data:image/png;base64,iVBORw0KGgo=");
            checks.add(() -> assertTrue(signed.is2xx(), "requiredSignature with signature: " + signed));
            checks.add(() -> assertEquals(0, count("SELECT count(*) FROM work_order WHERE company_id = ? AND title <> " +
                    "'T15 signed'", tenant.company().getId()), "no rejected work order may be persisted"));
            assertAll(checks);
        }

        @Test
        @DisplayName("INC-02 C6 (T-15): POST /work-orders as COMPLETE with completeTasks REQUIRED and no task succeeds, as in the UI")
        void createComplete_requiredTasksWithoutTasks_succeeds() throws Exception {
            Tenant tenant = newTenant("T15-post-tasks");
            requireOnCompletion(tenant.company(), "completeTasks");

            HttpResult result = postWorkOrder(login(tenant.admin()), "T15 no tasks", Status.COMPLETE, false, null);

            assertTrue(result.is2xx(), "a work order without tasks has no pending task: " + result);
        }

        @Test
        @DisplayName("INC-02 C6 (T-15): the generic PATCH cannot move a work order to COMPLETE")
        void patch_cannotComplete() throws Exception {
            Tenant tenant = newTenant("T15-patch");
            requireOnCompletion(tenant.company(), "completeFiles");
            WorkOrder workOrder = createWorkOrder(tenant.company(), tenant.admin(), null);
            ObjectNode body = objectMapper.createObjectNode().put("title", "T15 edited").put("status", "COMPLETE");

            HttpResult result = call(patch("/work-orders/" + workOrder.getId()).contentType(MediaType.APPLICATION_JSON)
                    .content(json(body)), login(tenant.admin()));

            assertAll(
                    () -> assertTrue(result.is2xx(), "PATCH /work-orders/{id}: " + result),
                    () -> assertEquals(Status.OPEN, statusOf(workOrder.getId()), "status must not change"));
        }

        @Test
        @DisplayName("INC-02 C6 (T-15): approved exception, a historical COMPLETE import does not check closing requirements")
        void importComplete_historicalException() {
            Tenant tenant = newTenant("T15-import");
            for (String field : List.of("completeFiles", "completeTasks", "completeTime", "completeParts", "completeCost"))
                requireOnCompletion(tenant.company(), field);
            User importer = createUser(tenant.company(), createCustomRole(tenant.company(), "T15 importer", new Perms(
                    set(PermissionEntity.WORK_ORDERS), set(PermissionEntity.WORK_ORDERS),
                    set(PermissionEntity.WORK_ORDERS), set(PermissionEntity.WORK_ORDERS), set())));
            WorkOrderImportDTO historical = WorkOrderImportDTO.builder().title("T15 historical").status("Complete")
                    .requiredSignature("Yes").completedByEmail(tenant.admin().getEmail()).build();

            importService.importWorkOrders(List.of(historical), tenant.company(), importer);

            Long id = jdbcTemplate.queryForObject("SELECT id FROM work_order WHERE company_id = ?", Long.class,
                    tenant.company().getId());
            assertEquals(Status.COMPLETE, statusOf(id), "historical import keeps COMPLETE");
        }

        // ----- R-03 (D-20): updating an existing work order to COMPLETE through the import follows D-19 -----

        private User importer(Tenant tenant) {
            return createUser(tenant.company(), createCustomRole(tenant.company(), "R03 importer", new Perms(
                    set(PermissionEntity.WORK_ORDERS), set(PermissionEntity.WORK_ORDERS),
                    set(PermissionEntity.WORK_ORDERS), set(PermissionEntity.WORK_ORDERS), set())));
        }

        @Test
        @DisplayName("INC-02 R-03 (D-20): an import row updating an open work order to COMPLETE without its requirements is rejected")
        void importUpdateToComplete_requirementMissing_rejected() {
            Tenant tenant = newTenant("R03-import-missing");
            requireOnCompletion(tenant.company(), "completeFiles");
            User importer = importer(tenant);
            WorkOrder existing = createWorkOrder(tenant.company(), tenant.admin(), null);
            WorkOrderImportDTO row = WorkOrderImportDTO.builder().id(existing.getId()).title("R03 update")
                    .status("Complete").completedByEmail(tenant.admin().getEmail()).completedOn(45000.5).build();

            CustomException ex = assertThrows(CustomException.class,
                    () -> importService.importWorkOrders(List.of(row), tenant.company(), importer));

            assertAll(
                    () -> assertEquals(HttpStatus.NOT_ACCEPTABLE, ex.getHttpStatus()),
                    () -> assertEquals(FILES, ex.getMessage()),
                    () -> assertEquals(Status.OPEN, statusOf(existing.getId()), "work order must stay OPEN"));
        }

        @Test
        @DisplayName("INC-02 R-03 (D-20): an import row updating an open work order to COMPLETE records the importer and keeps the date of the file")
        void importUpdateToComplete_requirementsMet_importerAndFileDate() {
            Tenant tenant = newTenant("R03-import-ok");
            User importer = importer(tenant);
            WorkOrder existing = createWorkOrder(tenant.company(), tenant.admin(), null);
            double excelDate = 45000.5;
            WorkOrderImportDTO row = WorkOrderImportDTO.builder().id(existing.getId()).title("R03 update")
                    .status("Complete").completedByEmail(tenant.admin().getEmail()).completedOn(excelDate).build();

            importService.importWorkOrders(List.of(row), tenant.company(), importer);

            ClosingData closing = closingDataOf(existing.getId());
            Date expected = new Date(Math.round((excelDate - 25569) * 24 * 60 * 60 * 1000));
            assertAll(
                    () -> assertEquals(Status.COMPLETE, closing.status()),
                    () -> assertEquals(importer.getId(), closing.completedById(), "the server records who closed it"),
                    () -> assertEquals(expected.getTime(), closing.completedOn().getTime(),
                            "the closing date of the file is kept (D-15)"));
        }

        @Test
        @DisplayName("INC-02 R-03: an import row on a work order already COMPLETE keeps its closing data")
        void importUpdate_alreadyComplete_keepsClosingData() {
            Tenant tenant = newTenant("R03-import-complete");
            User importer = importer(tenant);
            WorkOrder existing = createWorkOrder(tenant.company(), tenant.admin(), null);
            Date closedOn = new Date(1_700_000_000_000L);
            inTx(() -> {
                WorkOrder workOrder = workOrderRepository.findById(existing.getId()).orElseThrow();
                workOrder.setStatus(Status.COMPLETE);
                workOrder.setCompletedBy(tenant.admin());
                workOrder.setCompletedOn(closedOn);
                return workOrderRepository.save(workOrder);
            });
            WorkOrderImportDTO row = WorkOrderImportDTO.builder().id(existing.getId()).title("R03 update")
                    .status("Complete").completedByEmail(importer.getEmail()).completedOn(45000.5).build();

            importService.importWorkOrders(List.of(row), tenant.company(), importer);

            ClosingData closing = closingDataOf(existing.getId());
            assertAll(
                    () -> assertEquals(Status.COMPLETE, closing.status()),
                    () -> assertEquals(tenant.admin().getId(), closing.completedById(), "closing user must not change"),
                    () -> assertEquals(closedOn.getTime(), closing.completedOn().getTime(),
                            "closing date must not change"));
        }

        // ----- E-01 (F-01): the import closes against the persisted signature requirement -----

        private WorkOrder workOrderRequiringSignature(Tenant tenant) {
            WorkOrder workOrder = createWorkOrder(tenant.company(), tenant.admin(), null);
            return inTx(() -> {
                WorkOrder saved = workOrderRepository.findById(workOrder.getId()).orElseThrow();
                saved.setRequiredSignature(true);
                return workOrderRepository.save(saved);
            });
        }

        private boolean requiresSignature(Long workOrderId) {
            return inTx(() -> workOrderRepository.findById(workOrderId).orElseThrow().isRequiredSignature());
        }

        @Test
        @DisplayName("INC-02 E-01 (F-01): an import row closing a work order that requires a signature, without the column, is rejected")
        void importUpdateToComplete_signatureRequired_columnOmitted_rejected() {
            Tenant tenant = newTenant("E01-omitted");
            User importer = importer(tenant);
            WorkOrder existing = workOrderRequiringSignature(tenant);
            WorkOrderImportDTO row = WorkOrderImportDTO.builder().id(existing.getId()).title("E01 update")
                    .status("Complete").completedOn(45000.5).build();

            CustomException ex = assertThrows(CustomException.class,
                    () -> importService.importWorkOrders(List.of(row), tenant.company(), importer));

            assertAll(
                    () -> assertEquals(HttpStatus.NOT_ACCEPTABLE, ex.getHttpStatus()),
                    () -> assertEquals(SIGNATURE, ex.getMessage()),
                    () -> assertEquals(Status.OPEN, statusOf(existing.getId()), "work order must stay OPEN"),
                    () -> assertTrue(requiresSignature(existing.getId()), "the requirement must be kept"));
        }

        @Test
        @DisplayName("INC-02 E-01 (F-01): an import row cannot lift the signature requirement of the work order it closes")
        void importUpdateToComplete_signatureRequired_columnNo_rejected() {
            Tenant tenant = newTenant("E01-no");
            User importer = importer(tenant);
            WorkOrder existing = workOrderRequiringSignature(tenant);
            WorkOrderImportDTO row = WorkOrderImportDTO.builder().id(existing.getId()).title("E01 update")
                    .status("Complete").requiredSignature("No").completedOn(45000.5).build();

            CustomException ex = assertThrows(CustomException.class,
                    () -> importService.importWorkOrders(List.of(row), tenant.company(), importer));

            assertAll(
                    () -> assertEquals(SIGNATURE, ex.getMessage()),
                    () -> assertEquals(Status.OPEN, statusOf(existing.getId()), "work order must stay OPEN"));
        }

        @Test
        @DisplayName("INC-02 E-01 (F-01): an import row without the column keeps the signature requirement")
        void importUpdate_columnOmitted_keepsSignatureRequirement() {
            Tenant tenant = newTenant("E01-keep");
            User importer = importer(tenant);
            WorkOrder existing = workOrderRequiringSignature(tenant);
            WorkOrderImportDTO row = WorkOrderImportDTO.builder().id(existing.getId()).title("E01 update")
                    .status("Open").build();

            importService.importWorkOrders(List.of(row), tenant.company(), importer);

            assertTrue(requiresSignature(existing.getId()), "an omitted column must not change the requirement");
        }

        // ----- E-07 (F-10, D-31): closing an existing work order through the import takes a valid date -----

        @Test
        @DisplayName("INC-02 E-07 (D-31): an import row closing an existing work order without a valid date rejects the import")
        void importUpdateToComplete_withoutValidDate_rejected_nothingImported() {
            Tenant tenant = newTenant("E07-date");
            User importer = importer(tenant);
            WorkOrder existing = createWorkOrder(tenant.company(), tenant.admin(), null);
            List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
            for (Double completedOn : Arrays.asList(null, -1.0, Double.NaN)) {
                WorkOrderImportDTO newRow = WorkOrderImportDTO.builder().title("E07 new").status("Open").build();
                WorkOrderImportDTO closingRow = WorkOrderImportDTO.builder().id(existing.getId()).title("E07 update")
                        .status("Complete").completedOn(completedOn).build();

                CustomException ex = assertThrows(CustomException.class,
                        () -> importService.importWorkOrders(List.of(newRow, closingRow), tenant.company(), importer),
                        "completedOn " + completedOn);

                checks.add(() -> assertEquals(HttpStatus.NOT_ACCEPTABLE, ex.getHttpStatus(), "completedOn " + completedOn));
                checks.add(() -> assertEquals(Status.OPEN, statusOf(existing.getId()), "completedOn " + completedOn));
                checks.add(() -> assertEquals(1, count("SELECT count(*) FROM work_order WHERE company_id = ?",
                        tenant.company().getId()), "nothing may be imported, completedOn " + completedOn));
            }
            assertAll(checks);
        }

        // ----- R-04: a work order already COMPLETE keeps its closing evidence and gets no closing effects again -----

        @Test
        @DisplayName("INC-02 R-04: COMPLETE again keeps the signature, the closing user and the closing date")
        void changeStatus_alreadyComplete_keepsClosingEvidence() throws Exception {
            Tenant tenant = newTenant("R04-evidence");
            User editor = importer(tenant);
            WorkOrder workOrder = createWorkOrder(tenant.company(), tenant.admin(), null);
            inTx(() -> {
                WorkOrder saved = workOrderRepository.findById(workOrder.getId()).orElseThrow();
                saved.setRequiredSignature(true);
                return workOrderRepository.save(saved);
            });
            ObjectNode signed = objectMapper.createObjectNode().put("status", Status.COMPLETE.name())
                    .put("signature", "R04 signature");
            HttpResult first = call(patch("/work-orders/" + workOrder.getId() + "/change-status")
                    .contentType(MediaType.APPLICATION_JSON).content(json(signed)), login(editor));
            assertTrue(first.is2xx(), "precondition, first completion: " + first);
            ClosingData before = closingDataOf(workOrder.getId());

            // Same state again, by another user and without the required signature.
            HttpResult second = complete(login(tenant.admin()), workOrder.getId());

            ClosingData after = closingDataOf(workOrder.getId());
            assertAll(
                    () -> assertTrue(second.is2xx(), "change-status to COMPLETE again: " + second),
                    () -> assertEquals(before, after, "closing user and date must not change"),
                    () -> assertEquals(editor.getId(), after.completedById()),
                    () -> assertEquals("R04 signature", inTx(() -> workOrderRepository.findById(workOrder.getId())
                            .orElseThrow().getSignature()), "the signature must be kept"));
        }

        @Test
        @DisplayName("INC-02 R-04: COMPLETE again does not run the closing side effects again")
        void changeStatus_alreadyComplete_noClosingEffectsAgain() throws Exception {
            Tenant tenant = newTenant("R04-effects");
            // Closing a work order puts its asset back in operation and notifies the users of the asset.
            SecurityContextHolder.clearContext();
            Location location = new Location();
            location.setName("R04 location");
            location.setCompany(tenant.company());
            Asset asset = new Asset();
            asset.setName("R04 asset");
            asset.setCompany(tenant.company());
            asset.setLocation(locationRepository.save(location));
            asset.setPrimaryUser(tenant.admin());
            Asset savedAsset = assetRepository.save(asset);
            WorkOrder workOrder = createWorkOrder(tenant.company(), tenant.admin(), null);
            inTx(() -> {
                WorkOrder saved = workOrderRepository.findById(workOrder.getId()).orElseThrow();
                saved.setAsset(savedAsset);
                return workOrderRepository.save(saved);
            });
            String assetNotifications = "SELECT count(*) FROM notification WHERE notification_type = "
                    + NotificationType.ASSET.ordinal() + " AND resource_id = ?";
            String token = login(tenant.admin());
            HttpResult first = complete(token, workOrder.getId());
            assertTrue(first.is2xx(), "precondition, first completion: " + first);
            long notifications = count(assetNotifications, savedAsset.getId());
            assertTrue(notifications > 0, "precondition, the users of the asset are notified it is operational");

            HttpResult second = complete(token, workOrder.getId());

            assertAll(
                    () -> assertTrue(second.is2xx(), "change-status to COMPLETE again: " + second),
                    () -> assertEquals(notifications, count(assetNotifications, savedAsset.getId()),
                            "the closing side effects must not run again"));
        }

        @Test
        @DisplayName("INC-02 E-02 (F-02): COMPLETE again keeps the duration and the cost of the stopped timers")
        void changeStatus_alreadyComplete_keepsLaborDurationAndCost() throws Exception {
            Tenant tenant = newTenant("E02-labor");
            WorkOrder workOrder = createWorkOrder(tenant.company(), tenant.admin(), null);
            // A timer running for an hour, at 3600 per hour
            Labor timer = new Labor(tenant.admin(), 3600, new Date(System.currentTimeMillis() - 3_600_000L), workOrder,
                    true, TimeStatus.RUNNING);
            timer.setCompany(tenant.company());
            Long timerId = laborRepository.save(timer).getId();
            String token = login(tenant.admin());
            HttpResult first = complete(token, workOrder.getId());
            assertTrue(first.is2xx(), "precondition, first completion: " + first);
            Labor stopped = laborRepository.findById(timerId).orElseThrow();
            assertEquals(TimeStatus.STOPPED, stopped.getStatus(), "precondition, closing stops the running timer");
            assertTrue(stopped.getDuration() >= 3600, "precondition, the hour is logged: " + stopped.getDuration());

            HttpResult second = complete(token, workOrder.getId());

            Labor after = laborRepository.findById(timerId).orElseThrow();
            assertAll(
                    () -> assertTrue(second.is2xx(), "change-status to COMPLETE again: " + second),
                    () -> assertEquals(stopped.getDuration(), after.getDuration(), "the duration must not change"),
                    () -> assertEquals(stopped.getCost(), after.getCost(), "the cost must not change"));
        }

        private void requireOnCompletion(Company company, String fieldName) {
            setOnCompletion(company, fieldName, 1); // FieldType.REQUIRED
        }

        private void setOnCompletion(Company company, String fieldName, int fieldType) {
            int updated = jdbcTemplate.update("UPDATE field_configuration SET field_type = ? WHERE field_name = ? AND " +
                            "work_order_configuration_id = (SELECT work_order_configuration_id FROM company_settings " +
                            "WHERE id = ?)", fieldType, fieldName, company.getCompanySettings().getId());
            assertEquals(1, updated, "field configuration " + fieldName);
        }

        private HttpResult postWorkOrder(String token, String title, Status status, boolean requiredSignature,
                                         String signature) throws Exception {
            ObjectNode body = objectMapper.createObjectNode().put("title", title).put("status", status.name())
                    .put("requiredSignature", requiredSignature);
            if (signature != null) body.put("signature", signature);
            return call(post("/work-orders").contentType(MediaType.APPLICATION_JSON).content(json(body)), token);
        }

        private void assertRejected(HttpResult result, WorkOrder workOrder, String message) {
            assertAll(
                    () -> assertEquals(406, result.status(), "change-status to COMPLETE: " + result),
                    () -> assertTrue(result.body().contains(message), "message: " + result.body()),
                    () -> assertEquals(Status.OPEN, statusOf(workOrder.getId()), "work order must stay OPEN"));
        }

        private void assertCompleted(HttpResult result, WorkOrder workOrder) {
            assertAll(
                    () -> assertTrue(result.is2xx(), "change-status to COMPLETE once the requirement is met: " + result),
                    () -> assertEquals(Status.COMPLETE, statusOf(workOrder.getId()), "work order must be COMPLETE"));
        }

        /**
         * The UI counts the files of the work order and of its comments. A comment with a file is used here: a file on
         * the work order itself cannot be serialized in this test environment (no storage bucket for signed URLs).
         */
        private void attachFile(Company company, User user, WorkOrder workOrder) {
            File file = new File("t15.txt", "t15/t15.txt", FileType.OTHER, null, false);
            file.setCompany(company);
            Comment comment = new Comment();
            comment.setWorkOrder(workOrder);
            comment.setUser(user);
            comment.setContent("T15 evidence");
            comment.setCompany(company);
            comment.setFiles(new ArrayList<>(List.of(fileRepository.save(file))));
            commentRepository.save(comment);
        }

        private Task addTask(Company company, WorkOrder workOrder) {
            TaskBase base = new TaskBase();
            base.setLabel("T15 task");
            base.setCompany(company);
            Task task = new Task(taskBaseRepository.save(base), workOrder, null, null);
            task.setCompany(company);
            return taskRepository.save(task);
        }
    }

    // =====================================================================================================
    // E-05 (F-06): the review of a request runs its external effects after the commit, and bounds its lock
    // =====================================================================================================

    @Nested
    @DisplayName("INC-02 request review effects and lock")
    class RequestReviewEffectsTests {

        @Test
        @DisplayName("INC-02 E-05 (F-06): an approval rolled back at commit emits no notification")
        void approve_rolledBackAtCommit_noEffects() throws Exception {
            assertRolledBackWithoutEffects("E05-approve", true);
        }

        @Test
        @DisplayName("INC-02 E-05 (F-06): a rejection rolled back at commit emits no notification")
        void reject_rolledBackAtCommit_noEffects() throws Exception {
            assertRolledBackWithoutEffects("E05-reject", false);
        }

        @Test
        @DisplayName("INC-02 E-05 (F-06): a committed approval still notifies the requester")
        void approve_committed_notifiesRequester() throws Exception {
            Tenant tenant = newTenant("E05-commit");
            User requester = createUser(tenant.company(), createCustomRole(tenant.company(), "E05 requester",
                    new Perms(set(PermissionEntity.REQUESTS), set(), set(PermissionEntity.REQUESTS), set(), set())));
            Request request = createRequest(tenant.company(), requester, null);

            HttpResult result = approve(login(tenant.admin()), request.getId());

            assertTrue(result.is2xx(), "approve: " + result);
            assertTrue(awaitNotifications(requester, 1, 10_000) >= 1, "the requester is notified after the commit");
        }

        @Test
        @DisplayName("INC-02 E-05 (F-06): a review waits a bounded time for the lock of a request under review")
        void approve_requestLocked_waitIsBounded() throws Exception {
            Tenant tenant = newTenant("E05-lock");
            Request request = createRequest(tenant.company(), tenant.admin(), null);
            String token = login(tenant.admin());
            CountDownLatch locked = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<?> holder = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                    jdbcTemplate.queryForList("SELECT id FROM request WHERE id = ? FOR UPDATE", request.getId());
                    locked.countDown();
                    try {
                        release.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }));
                assertTrue(locked.await(10, TimeUnit.SECONDS), "precondition, another review holds the lock");

                long start = System.nanoTime();
                HttpResult result = approve(token, request.getId());
                long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                release.countDown();
                holder.get(30, TimeUnit.SECONDS);

                assertAll(
                        () -> assertEquals(409, result.status(), "approve while locked: " + result),
                        () -> assertTrue(result.body().contains("REQUEST_UNDER_REVIEW"), "code: " + result.body()),
                        () -> assertTrue(waitedMs < 10_000, "the wait must be bounded, waited " + waitedMs + " ms"),
                        () -> assertNull(workOrderIdOf(request.getId()), "no work order may be created"));
            } finally {
                release.countDown();
                executor.shutdownNow();
            }
        }

        /**
         * A deferred constraint trigger fails the COMMIT of the review of this request only, after the controller
         * has run every statement, so the transaction rolls back where it can no longer be avoided.
         */
        private void assertRolledBackWithoutEffects(String label, boolean approve) throws Exception {
            Tenant tenant = newTenant(label);
            User requester = createUser(tenant.company(), createCustomRole(tenant.company(), label + " requester",
                    new Perms(set(PermissionEntity.REQUESTS), set(), set(PermissionEntity.REQUESTS), set(), set())));
            Request request = createRequest(tenant.company(), requester, null);
            String trigger = "inc02_e05_" + request.getId();
            jdbcTemplate.execute("CREATE OR REPLACE FUNCTION " + trigger + "() RETURNS trigger AS $$ BEGIN " +
                    "RAISE EXCEPTION 'INC-02 E-05 forced rollback'; END $$ LANGUAGE plpgsql");
            jdbcTemplate.execute("CREATE CONSTRAINT TRIGGER " + trigger + " AFTER UPDATE ON request " +
                    "DEFERRABLE INITIALLY DEFERRED FOR EACH ROW WHEN (NEW.id = " + request.getId() + ") " +
                    "EXECUTE FUNCTION " + trigger + "()");
            try {
                String token = login(tenant.admin());
                HttpResult result = approve ? approve(token, request.getId()) : reject(token, request.getId());

                assertAll(
                        () -> assertFalse(result.is2xx(), "the review must fail at commit: " + result),
                        () -> assertNull(workOrderIdOf(request.getId()), "no work order may be kept"),
                        () -> assertFalse(isCancelled(request.getId()), "the request must stay pending"),
                        // The notifications are asynchronous: wait long enough for one dispatched before the commit
                        () -> assertEquals(0, awaitNotifications(requester, 1, 3_000),
                                "no notification may be emitted for a review rolled back"));
            } finally {
                jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + trigger + " ON request");
                jdbcTemplate.execute("DROP FUNCTION IF EXISTS " + trigger + "()");
            }
        }

        /**
         * Waits until the user has at least {@code expected} notifications or the time runs out; returns the count.
         */
        private long awaitNotifications(User user, long expected, long timeoutMs) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMs;
            long found;
            do {
                found = count("SELECT count(*) FROM notification WHERE user_id = ?", user.getId());
                if (found >= expected) return found;
                Thread.sleep(200);
            } while (System.currentTimeMillis() < deadline);
            return found;
        }
    }

    // =====================================================================================================
    // T-12: work order collections apply the view rule of the work order detail (Codex A-10)
    // =====================================================================================================

    @Nested
    @DisplayName("INC-02 work order reads")
    class WorkOrderReadTests {

        private final Perms withoutWorkOrderView = new Perms(
                set(PermissionEntity.ASSETS, PermissionEntity.LOCATIONS, PermissionEntity.PARTS_AND_MULTIPARTS,
                        PermissionEntity.REQUESTS), set(), set(PermissionEntity.REQUESTS), set(), set());
        private final Perms ownWorkOrdersOnly = new Perms(
                set(PermissionEntity.WORK_ORDERS, PermissionEntity.ASSETS, PermissionEntity.LOCATIONS,
                        PermissionEntity.PARTS_AND_MULTIPARTS), set(), set(), set(), set());

        private record Linked(Location location, Asset asset, Part part) {
        }

        @Test
        @DisplayName("INC-02 C4 (T-12): without view on work orders, search and the asset, location and part reads return none")
        void withoutWorkOrderView_collectionsReturnNone() throws Exception {
            Tenant tenant = newTenant("T12-none");
            Linked linked = linkedEntities(tenant.company());
            linkedWorkOrder(tenant.company(), tenant.admin(), linked, null);
            String token = login(createUser(tenant.company(),
                    createCustomRole(tenant.company(), "T12 no view", withoutWorkOrderView)));

            assertEquals(Set.of(), visibleIds(token, linked), "no work order must be visible");
        }

        @Test
        @DisplayName("INC-02 C4 (T-12): with view on work orders but not on others, collections return only their own work orders")
        void withoutViewOther_collectionsReturnOwnOnly() throws Exception {
            Tenant tenant = newTenant("T12-own");
            Linked linked = linkedEntities(tenant.company());
            User user = createUser(tenant.company(), createCustomRole(tenant.company(), "T12 own", ownWorkOrdersOnly));
            linkedWorkOrder(tenant.company(), tenant.admin(), linked, null);
            WorkOrder own = linkedWorkOrder(tenant.company(), user, linked, null);

            assertEquals(Set.of(own.getId()), visibleIds(login(user), linked), "only the own work order");
        }

        @Test
        @DisplayName("INC-02 C3 (T-12): without view on work orders, the work order of an own request stays visible, as in the detail")
        void ownRequestWorkOrder_staysVisible() throws Exception {
            Tenant tenant = newTenant("T12-request");
            Linked linked = linkedEntities(tenant.company());
            User requester = createUser(tenant.company(),
                    createCustomRole(tenant.company(), "T12 requester", withoutWorkOrderView));
            linkedWorkOrder(tenant.company(), tenant.admin(), linked, null);
            WorkOrder fromRequest = linkedWorkOrder(tenant.company(), tenant.admin(), linked,
                    createRequest(tenant.company(), requester, null));

            assertEquals(Set.of(fromRequest.getId()), visibleIds(login(requester), linked),
                    "only the work order of the own request");
        }

        // ----- R-05: the auxiliary reads of work orders follow the detail rule (WorkOrder.isAccessibleBy) -----

        @Test
        @DisplayName("INC-02 R-05: with view on work orders but not on others, the search also returns the work order of an own request")
        void search_ownRequestWorkOrder_visible() throws Exception {
            Tenant tenant = newTenant("R05-search");
            Linked linked = linkedEntities(tenant.company());
            User user = createUser(tenant.company(), createCustomRole(tenant.company(), "R05 own", ownWorkOrdersOnly));
            linkedWorkOrder(tenant.company(), tenant.admin(), linked, null);
            WorkOrder own = linkedWorkOrder(tenant.company(), user, linked, null);
            WorkOrder fromRequest = linkedWorkOrder(tenant.company(), tenant.admin(), linked,
                    createRequest(tenant.company(), user, null));

            assertEquals(Set.of(own.getId(), fromRequest.getId()), visibleIds(login(user), linked),
                    "the own work order and the one of the own request");
        }

        @Test
        @DisplayName("INC-02 R-05: the recent work orders of a preventive maintenance are only the visible ones, filtered before the limit")
        void preventiveMaintenanceRecent_onlyVisible() throws Exception {
            Tenant tenant = newTenant("R05-pm");
            User user = createUser(tenant.company(), createCustomRole(tenant.company(), "R05 pm", new Perms(
                    set(PermissionEntity.WORK_ORDERS, PermissionEntity.PREVENTIVE_MAINTENANCES),
                    set(PermissionEntity.PREVENTIVE_MAINTENANCES), set(), set(), set())));
            SecurityContextHolder.clearContext();
            PreventiveMaintenance pm = new PreventiveMaintenance();
            pm.setName("R05 pm");
            pm.setTitle("R05 pm");
            pm.setCompany(tenant.company());
            PreventiveMaintenance savedPm = preventiveMaintenanceRepository.save(pm);
            // The visible work order is the oldest: the endpoint returns the last ten.
            WorkOrder own = pmWorkOrder(tenant.company(), user, savedPm);
            for (int i = 0; i < 10; i++) pmWorkOrder(tenant.company(), tenant.admin(), savedPm);

            HttpResult result = call(get("/preventive-maintenances/" + savedPm.getId() + "/recent-work-orders"),
                    login(user));

            assertEquals(Set.of(own.getId()), idsOfArray(result), "only the visible work order");
        }

        @Test
        @DisplayName("INC-02 R-05: planning and exports of every work order need the view of every work order, as the detail")
        void companyWideReads_viewOtherWithoutView_forbidden() throws Exception {
            Tenant tenant = newTenant("R05-company");
            createWorkOrder(tenant.company(), tenant.admin(), null);
            String token = login(createUser(tenant.company(), createCustomRole(tenant.company(), "R05 view other",
                    new Perms(set(), set(PermissionEntity.WORK_ORDERS), set(),
                            set(PermissionEntity.PEOPLE_AND_TEAMS), set()))));
            String uuid = UUID.randomUUID().toString();

            HttpResult unscheduled = call(get("/workload/unscheduled"), token);
            HttpResult overview = call(get("/workload/overview").param("startDate", "2026-11-01")
                    .param("endDate", "2026-11-07"), token);
            // INC-01: the export is a POST with the criteria of the list
            HttpResult export = call(post("/export/work-orders").param("uuid", uuid)
                    .contentType(MediaType.APPLICATION_JSON).content("{}"), token);
            HttpResult costs = call(get("/export/costs-times").param("uuid", uuid), token);
            HttpResult adminUnscheduled = call(get("/workload/unscheduled"), login(tenant.admin()));

            assertAll(
                    () -> assertEquals(403, unscheduled.status(), "GET /workload/unscheduled: " + unscheduled),
                    () -> assertEquals(403, overview.status(), "GET /workload/overview: " + overview),
                    () -> assertEquals(403, export.status(), "POST /export/work-orders: " + export),
                    () -> assertEquals(403, costs.status(), "GET /export/costs-times: " + costs),
                    () -> assertTrue(adminUnscheduled.is2xx(), "administrator, GET /workload/unscheduled: "
                            + adminUnscheduled));
        }

        @Test
        @DisplayName("INC-02 R-05: the relations of a visible work order hide a related work order that is not visible")
        void relations_hideNotVisibleWorkOrder() throws Exception {
            Tenant tenant = newTenant("R05-relations");
            User user = createUser(tenant.company(), createCustomRole(tenant.company(), "R05 own", ownWorkOrdersOnly));
            WorkOrder own = createWorkOrder(tenant.company(), user, null);
            WorkOrder ownOther = createWorkOrder(tenant.company(), user, null);
            WorkOrder hidden = createWorkOrder(tenant.company(), tenant.admin(), null);
            Relation visible = relation(tenant.company(), own, ownOther);
            relation(tenant.company(), hidden, own);

            HttpResult result = call(get("/relations/work-order/" + own.getId()), login(user));

            assertEquals(Set.of(visible.getId()), idsOfArray(result), "only the relation between visible work orders");
        }

        @Test
        @DisplayName("INC-02 R-05: the calendar shows the work order of an own request, as the detail")
        void calendar_ownRequestWorkOrder_visible() throws Exception {
            Tenant tenant = newTenant("R05-calendar");
            User user = createUser(tenant.company(), createCustomRole(tenant.company(), "R05 own", ownWorkOrdersOnly));
            Date due = new Date(System.currentTimeMillis() + 24 * 3600 * 1000L);
            WorkOrder fromRequest = dueWorkOrder(createWorkOrder(tenant.company(), tenant.admin(), null), due,
                    createRequest(tenant.company(), user, null));
            dueWorkOrder(createWorkOrder(tenant.company(), tenant.admin(), null), due, null);
            ObjectNode range = objectMapper.createObjectNode()
                    .put("start", System.currentTimeMillis() - 24 * 3600 * 1000L)
                    .put("end", System.currentTimeMillis() + 3 * 24 * 3600 * 1000L);

            HttpResult result = call(post("/work-orders/events").contentType(MediaType.APPLICATION_JSON)
                    .content(json(range)), login(user));

            assertTrue(result.is2xx(), "POST /work-orders/events: " + result);
            Set<Long> ids = new HashSet<>();
            objectMapper.readTree(result.body()).forEach(event -> ids.add(event.path("event").path("id").asLong()));
            assertEquals(Set.of(fromRequest.getId()), ids, "only the work order of the own request");
        }

        @Test
        @DisplayName("INC-02 R-05: the PDF report follows the detail rule, including the work order of an own request")
        void report_followsDetailRule() throws Exception {
            Tenant tenant = newTenant("R05-report");
            User user = createUser(tenant.company(), createCustomRole(tenant.company(), "R05 own", ownWorkOrdersOnly));
            WorkOrder fromRequest = createWorkOrder(tenant.company(), tenant.admin(), null);
            inTx(() -> {
                WorkOrder saved = workOrderRepository.findById(fromRequest.getId()).orElseThrow();
                saved.setParentRequest(createRequest(tenant.company(), user, null));
                return workOrderRepository.save(saved);
            });
            WorkOrder hidden = createWorkOrder(tenant.company(), tenant.admin(), null);
            String token = login(user);

            HttpResult allowed = call(post("/work-orders/report/" + fromRequest.getId())
                    .contentType(MediaType.APPLICATION_JSON).content("{}"), token);
            HttpResult denied = call(post("/work-orders/report/" + hidden.getId())
                    .contentType(MediaType.APPLICATION_JSON).content("{}"), token);

            assertAll(
                    // Past the access check the report needs a storage service the test profile lacks.
                    () -> assertNotEquals(403, allowed.status(), "report of the own request work order: " + allowed),
                    () -> assertEquals(403, denied.status(), "report of a work order not visible: " + denied));
        }

        @Test
        @DisplayName("INC-02 E-03 (F-03): the PDF report lists only the relations whose two work orders are visible, as GET /relations")
        void reportPdf_hidesRelationsToNotVisibleWorkOrders() throws Exception {
            Tenant tenant = newTenant("E03-report");
            User user = createUser(tenant.company(), createCustomRole(tenant.company(), "E03 own", ownWorkOrdersOnly));
            WorkOrder fromRequest = dueWorkOrder(createWorkOrder(tenant.company(), tenant.admin(), null), null,
                    createRequest(tenant.company(), user, null));
            WorkOrder own = createWorkOrder(tenant.company(), user, null);
            WorkOrder hidden = createWorkOrder(tenant.company(), tenant.admin(), null);
            String ownTitle = "E03-visible-" + UUID.randomUUID().toString().substring(0, 8);
            String hiddenTitle = "E03-restricted-" + UUID.randomUUID().toString().substring(0, 8);
            inTx(() -> {
                workOrderRepository.findById(own.getId()).orElseThrow().setTitle(ownTitle);
                workOrderRepository.findById(hidden.getId()).orElseThrow().setTitle(hiddenTitle);
                return null;
            });
            relation(tenant.company(), fromRequest, own);
            relation(tenant.company(), hidden, fromRequest);

            // The download (POST /work-orders/report/{id}) and the mail (POST /work-orders/{id}/send-report) both
            // render this PDF; the download then needs a storage service the test profile lacks.
            String text = inTx(() -> {
                User viewer = userRepository.findById(user.getId()).orElseThrow();
                WorkOrder workOrder = workOrderRepository.findById(fromRequest.getId()).orElseThrow();
                assertTrue(workOrder.isAccessibleBy(viewer), "precondition, the work order of an own request");
                ReportConfig config = new ReportConfig();
                config.setWorkOrderHistory(false); // the history reads the locale of the authenticated user
                return pdfText(workOrderService.generatePdfBytes(workOrder, viewer, config));
            });

            assertAll(
                    () -> assertTrue(text.contains(ownTitle), "the relation between visible work orders is listed"),
                    () -> assertFalse(text.contains(hiddenTitle), "a work order not visible must not appear"));
        }

        private String pdfText(byte[] pdf) {
            try (PdfDocument document = new PdfDocument(new PdfReader(new ByteArrayInputStream(pdf)))) {
                StringBuilder text = new StringBuilder();
                for (int page = 1; page <= document.getNumberOfPages(); page++)
                    text.append(PdfTextExtractor.getTextFromPage(document.getPage(page))).append('\n');
                return text.toString().replaceAll("\\s", ""); // the layout may wrap a title
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private WorkOrder pmWorkOrder(Company company, User createdBy, PreventiveMaintenance pm) {
            SecurityContextHolder.clearContext();
            WorkOrder workOrder = new WorkOrder();
            workOrder.setTitle("R05 pm work order " + UUID.randomUUID());
            workOrder.setCompany(company);
            workOrder.setCreatedBy(createdBy.getId());
            workOrder.setParentPreventiveMaintenance(pm);
            return workOrderRepository.save(workOrder);
        }

        private Relation relation(Company company, WorkOrder parent, WorkOrder child) {
            SecurityContextHolder.clearContext();
            Relation relation = new Relation();
            relation.setParent(parent);
            relation.setChild(child);
            relation.setCompany(company);
            return relationRepository.save(relation);
        }

        private WorkOrder dueWorkOrder(WorkOrder workOrder, Date dueDate, Request parentRequest) {
            return inTx(() -> {
                WorkOrder saved = workOrderRepository.findById(workOrder.getId()).orElseThrow();
                saved.setDueDate(dueDate);
                saved.setParentRequest(parentRequest);
                return workOrderRepository.save(saved);
            });
        }

        /**
         * Ids returned by each collection read. The four reads must agree with the detail rule, so all of them
         * must return the same ids.
         */
        private Set<Long> visibleIds(String token, Linked linked) throws Exception {
            Map<String, Set<Long>> byRead = new LinkedHashMap<>();
            byRead.put("asset", idsOfArray(call(get("/work-orders/asset/" + linked.asset().getId()), token)));
            byRead.put("location", idsOfArray(call(get("/work-orders/location/" + linked.location().getId()), token)));
            byRead.put("part", idsOfArray(call(get("/work-orders/part/" + linked.part().getId()), token)));
            HttpResult search = call(post("/work-orders/search").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"filterFields\":[],\"pageNum\":0,\"pageSize\":50}"), token);
            assertTrue(search.is2xx(), "POST /work-orders/search: " + search);
            Set<Long> searched = new HashSet<>();
            objectMapper.readTree(search.body()).path("content").forEach(node -> searched.add(node.get("id").asLong()));
            byRead.put("search", searched);
            Set<Long> first = byRead.values().iterator().next();
            assertTrue(byRead.values().stream().allMatch(first::equals), "all reads must agree: " + byRead);
            return first;
        }

        private Set<Long> idsOfArray(HttpResult result) throws Exception {
            assertTrue(result.is2xx(), "collection read: " + result);
            return idsIn(result.body());
        }

        private Linked linkedEntities(Company company) {
            SecurityContextHolder.clearContext();
            Location location = new Location();
            location.setName("T12 location");
            location.setCompany(company);
            location = locationRepository.save(location);
            Asset asset = new Asset();
            asset.setName("T12 asset");
            asset.setCompany(company);
            asset.setLocation(location);
            asset = assetRepository.save(asset);
            Part part = new Part();
            part.setName("T12 part");
            part.setCompany(company);
            return new Linked(location, asset, partRepository.save(part));
        }

        private WorkOrder linkedWorkOrder(Company company, User createdBy, Linked linked, Request parentRequest) {
            SecurityContextHolder.clearContext();
            WorkOrder workOrder = new WorkOrder();
            workOrder.setTitle("T12 work order " + UUID.randomUUID());
            workOrder.setCompany(company);
            workOrder.setCreatedBy(createdBy.getId());
            workOrder.setAsset(linked.asset());
            workOrder.setLocation(linked.location());
            workOrder.setParentRequest(parentRequest);
            workOrder = workOrderRepository.save(workOrder);
            PartQuantity quantity = new PartQuantity(linked.part(), workOrder, null, 1);
            quantity.setCompany(company);
            partQuantityRepository.save(quantity);
            return workOrder;
        }
    }

    // =====================================================================================================
    // T-06: POST /roles must never write an existing row (Codex A-02)
    // =====================================================================================================

    @Nested
    @DisplayName("INC-02 role creation with a client-supplied id")
    class CreateWithIdTests {

        @Test
        @DisplayName("INC-02 C10 (T-06): POST /roles with the id of an own custom role creates a new role and keeps the existing one")
        void ownCustomRoleId_existingRowUnchanged() throws Exception {
            Tenant tenant = newTenant("T06-own");
            Role existing = createCustomRole(tenant.company(), "T06 own role", new Perms(
                    set(PermissionEntity.REQUESTS), set(), set(), set(), set()));

            assertPostWithIdKeepsRow(tenant, existing.getId());
        }

        @Test
        @DisplayName("INC-02 C7 (T-06): POST /roles with the id of a default role creates a new role and keeps the default one")
        void defaultRoleId_existingRowUnchanged() throws Exception {
            Tenant tenant = newTenant("T06-default");
            Role existing = roleService.findDefaultRoleWithCode(RoleCode.VIEW_ONLY).orElseThrow();
            Map<String, Object> row = roleRow(existing.getId());
            Perms perms = permsInDb(existing.getId());
            try {
                assertPostWithIdKeepsRow(tenant, existing.getId());
            } finally {
                restoreRole(existing.getId(), row, perms);
            }
        }

        @Test
        @DisplayName("INC-02 C12 (T-06): POST /roles with the id of a role of another company creates a new role and keeps the other one")
        void otherCompanyRoleId_existingRowUnchanged() throws Exception {
            Tenant tenant = newTenant("T06-other-A");
            Tenant other = newTenant("T06-other-B");
            Role existing = createCustomRole(other.company(), "T06 role B", new Perms(
                    set(PermissionEntity.REQUESTS), set(), set(), set(), set()));

            assertPostWithIdKeepsRow(tenant, existing.getId());
        }

        /**
         * The administrator sends the UI creation payload plus the id of an existing role. The server must ignore the
         * id: a new role of the requester's company is created and the existing row stays identical.
         */
        private void assertPostWithIdKeepsRow(Tenant tenant, Long existingId) throws Exception {
            Map<String, Object> rowBefore = roleRow(existingId);
            Perms permsBefore = permsInDb(existingId);
            ObjectNode payload = uiCreatePayload(tenant.company(), "T06 new role", new Perms(
                    set(PermissionEntity.WORK_ORDERS), set(), set(PermissionEntity.WORK_ORDERS), set(), set()));
            payload.put("id", existingId);

            HttpResult result = call(post("/roles").contentType(MediaType.APPLICATION_JSON).content(json(payload)),
                    login(tenant.admin()));

            long newId = result.is2xx() ? objectMapper.readTree(result.body()).get("id").asLong() : -1;
            assertAll(
                    () -> assertTrue(result.is2xx(), "POST /roles: " + result),
                    () -> assertNotEquals(existingId.longValue(), newId, "the server must generate a new id"),
                    () -> assertEquals(rowBefore, roleRow(existingId), "existing role row must not change"),
                    () -> assertEquals(permsBefore, permsInDb(existingId), "existing role permissions must not change"),
                    () -> assertTrue(roleNamesOf(tenant.company()).contains("T06 new role"),
                            "the new role must belong to the requester's company"));
        }

        private Map<String, Object> roleRow(Long roleId) {
            return jdbcTemplate.queryForMap("SELECT name, description, external_id, code, role_type, paid, " +
                    "company_settings_id FROM role WHERE id = ?", roleId);
        }

        /**
         * Puts a shared default role back exactly as it was, in case the request managed to overwrite it.
         */
        private void restoreRole(Long roleId, Map<String, Object> row, Perms perms) {
            jdbcTemplate.update("UPDATE role SET name = ?, description = ?, external_id = ?, code = ?, role_type = ?, " +
                            "paid = ?, company_settings_id = ? WHERE id = ?", row.get("name"), row.get("description"),
                    row.get("external_id"), row.get("code"), row.get("role_type"), row.get("paid"),
                    row.get("company_settings_id"), roleId);
            inTx(() -> {
                Role role = roleRepository.findById(roleId).orElseThrow();
                role.setViewPermissions(new HashSet<>(perms.view()));
                role.setViewOtherPermissions(new HashSet<>(perms.viewOther()));
                role.setCreatePermissions(new HashSet<>(perms.create()));
                role.setEditOtherPermissions(new HashSet<>(perms.editOther()));
                role.setDeleteOtherPermissions(new HashSet<>(perms.deleteOther()));
                return roleRepository.save(role);
            });
        }
    }

    // =====================================================================================================
    // Deletion and default roles
    // =====================================================================================================

    @Nested
    @DisplayName("INC-02 deletion")
    class DeletionTests {

        @Test
        @DisplayName("INC-02 C7: editing a default role is rejected (4xx) and the role is unchanged")
        void patchDefaultRole_rejected_roleUnchanged() throws Exception {
            Tenant tenant = newTenant("C7-patch");
            Role defaultRole = roleService.findDefaultRoleWithCode(RoleCode.VIEW_ONLY).orElseThrow();
            RoleSnapshot before = snapshot(defaultRole.getId());
            try {
                HttpResult result = patchRoleLikeUi(login(tenant.admin()), tenant.company(), defaultRole.getId(),
                        new Perms(all(), all(), all(), all(), all()));

                assertAll(
                        () -> assertTrue(result.is4xx(), "PATCH /roles/{defaultId}: " + result),
                        () -> assertEquals(before, snapshot(defaultRole.getId()), "default role must not change"));
            } finally {
                restoreDefaultRole(RoleCode.VIEW_ONLY, before);
            }
        }

        @Test
        @DisplayName("INC-02 C7: deleting a default role is rejected (4xx) and the role is unchanged")
        void deleteDefaultRole_rejected_roleUnchanged() throws Exception {
            Tenant tenant = newTenant("C7-delete");
            Role defaultRole = roleService.findDefaultRoleWithCode(RoleCode.VIEW_ONLY).orElseThrow();
            RoleSnapshot before = snapshot(defaultRole.getId());
            try {
                HttpResult result = call(delete("/roles/" + defaultRole.getId()), login(tenant.admin()));

                assertAll(
                        () -> assertTrue(result.is4xx(), "DELETE /roles/{defaultId}: " + result),
                        () -> assertTrue(roleRepository.existsById(defaultRole.getId()), "default role must exist"),
                        () -> assertEquals(before, snapshot(defaultRole.getId()), "default role must not change"));
            } finally {
                restoreDefaultRole(RoleCode.VIEW_ONLY, before);
            }
        }

        @Test
        @DisplayName("INC-02 C7 (T-07): editing a default-code role that belongs to the company is rejected (4xx) and unchanged")
        void patchCompanyScopedDefaultRole_rejected_roleUnchanged() throws Exception {
            Tenant tenant = newTenant("T07-patch");
            Role defaultCoded = createCompanyRoleWithCode(tenant.company(), RoleCode.TECHNICIAN);
            RoleSnapshot before = snapshot(defaultCoded.getId());

            HttpResult result = patchRoleLikeUi(login(tenant.admin()), tenant.company(), defaultCoded.getId(),
                    new Perms(all(), all(), all(), all(), all()));

            assertAll(
                    () -> assertTrue(result.is4xx(), "PATCH /roles/{id}: " + result),
                    () -> assertEquals(before, snapshot(defaultCoded.getId()), "default-code role must not change"));
        }

        @Test
        @DisplayName("INC-02 C7 (T-07): deleting a default-code role that belongs to the company is rejected (4xx)")
        void deleteCompanyScopedDefaultRole_rejected_roleKept() throws Exception {
            Tenant tenant = newTenant("T07-delete");
            Role defaultCoded = createCompanyRoleWithCode(tenant.company(), RoleCode.TECHNICIAN);

            HttpResult result = call(delete("/roles/" + defaultCoded.getId()), login(tenant.admin()));

            assertAll(
                    () -> assertTrue(result.is4xx(), "DELETE /roles/{id}: " + result),
                    () -> assertTrue(roleRepository.existsById(defaultCoded.getId()), "default-code role must exist"));
        }

        /**
         * A role with a default code attached to the company: it passes the ownership check, so only the code check
         * protects it (Codex A-11).
         */
        private Role createCompanyRoleWithCode(Company company, RoleCode code) {
            Role role = createCustomRole(company, "T07 " + code, new Perms(
                    set(PermissionEntity.WORK_ORDERS), set(), set(), set(), set()));
            role.setCode(code);
            return roleRepository.save(role);
        }

        @Test
        @DisplayName("INC-02 C8: deleting a role assigned to a user returns 409 and the user keeps the role")
        void deleteRoleWithUser_conflict_userKeepsRole() throws Exception {
            Tenant tenant = newTenant("C8-plain");
            Role role = createCustomRole(tenant.company(), "C8 role", new Perms(
                    set(PermissionEntity.WORK_ORDERS), set(), set(), set(), set()));
            User user = createUser(tenant.company(), role);

            HttpResult result = call(delete("/roles/" + role.getId()), login(tenant.admin()));

            assertAll(
                    () -> assertEquals(409, result.status(), "DELETE /roles/{id}: " + result),
                    () -> assertTrue(roleRepository.existsById(role.getId()), "role must still exist"),
                    () -> assertTrue(userRepository.existsById(user.getId()), "user must still exist"),
                    () -> assertEquals(role.getId(), roleIdOfUser(user.getId()), "user must keep the role"));
        }

        @Test
        @DisplayName("INC-02 C8: deleting a role whose user has a work order and a request returns 409 and keeps all references")
        void deleteRoleWithUserAndWork_conflict_referencesKept() throws Exception {
            Tenant tenant = newTenant("C8-work");
            Role role = createCustomRole(tenant.company(), "C8 role", new Perms(
                    set(PermissionEntity.WORK_ORDERS, PermissionEntity.REQUESTS), set(),
                    set(PermissionEntity.WORK_ORDERS, PermissionEntity.REQUESTS), set(), set()));
            User user = createUser(tenant.company(), role);
            Request request = createRequest(tenant.company(), user, user);
            WorkOrder workOrder = createWorkOrder(tenant.company(), user, user);

            HttpResult result = call(delete("/roles/" + role.getId()), login(tenant.admin()));

            assertAll(
                    () -> assertEquals(409, result.status(), "DELETE /roles/{id}: " + result),
                    () -> assertTrue(roleRepository.existsById(role.getId()), "role must still exist"),
                    () -> assertTrue(userRepository.existsById(user.getId()), "user must still exist"),
                    () -> assertEquals(role.getId(), roleIdOfUser(user.getId()), "user must keep the role"),
                    () -> assertEquals(new References(user.getId(), user.getId(), List.of(user.getId())),
                            requestReferences(request.getId()), "request references (createdBy, primaryUser, assignedTo)"),
                    () -> assertEquals(new References(user.getId(), user.getId(), List.of(user.getId())),
                            workOrderReferences(workOrder.getId()), "work order references (createdBy, primaryUser, assignedTo)"));
        }

        @Test
        @DisplayName("INC-02 C8 (T-01): the database rejects deleting a role assigned to a user, even bypassing the service")
        void deleteRoleWithUser_directRepositoryDelete_rejectedByConstraint() {
            Tenant tenant = newTenant("C8-schema");
            Role role = createCustomRole(tenant.company(), "C8 schema role", new Perms(
                    set(PermissionEntity.WORK_ORDERS), set(), set(), set(), set()));
            User user = createUser(tenant.company(), role);

            assertThrows(DataIntegrityViolationException.class, () -> roleRepository.deleteById(role.getId()),
                    "FK own_user.role_id must restrict the deletion");

            assertAll(
                    () -> assertTrue(roleRepository.existsById(role.getId()), "role must still exist"),
                    () -> assertTrue(userRepository.existsById(user.getId()), "user must still exist"),
                    () -> assertEquals(role.getId(), roleIdOfUser(user.getId()), "user must keep the role"));
        }

        @Test
        @DisplayName("INC-02 C8 (T-01): deleting a company still removes its custom roles and the users holding them")
        void deleteCompany_withAssignedCustomRole_removesRolesAndUsers() {
            Tenant tenant = newTenant("C8-company");
            Role role = createCustomRole(tenant.company(), "C8 company role", new Perms(
                    set(PermissionEntity.WORK_ORDERS), set(), set(), set(), set()));
            User user = createUser(tenant.company(), role);

            // Same path as account deletion by the owner (AuthController) and the demo cleanup job.
            companyService.delete(tenant.company().getId());

            assertAll(
                    () -> assertFalse(companyRepository.existsById(tenant.company().getId()), "company must be gone"),
                    () -> assertFalse(roleRepository.existsById(role.getId()), "custom role must be gone"),
                    () -> assertFalse(userRepository.existsById(user.getId()), "user must be gone"),
                    () -> assertFalse(userRepository.existsById(tenant.admin().getId()), "owner must be gone"));
        }

        @Test
        @DisplayName("INC-02 C9: deleting a custom role without users returns 2xx and the role no longer exists")
        void deleteRoleWithoutUsers_succeeds_roleGone() throws Exception {
            Tenant tenant = newTenant("C9");
            Role role = createCustomRole(tenant.company(), "C9 role", new Perms(
                    set(PermissionEntity.WORK_ORDERS), set(), set(), set(), set()));
            String adminToken = login(tenant.admin());

            HttpResult result = call(delete("/roles/" + role.getId()), adminToken);
            HttpResult readBack = call(get("/roles/" + role.getId()), adminToken);

            assertAll(
                    () -> assertTrue(result.is2xx(), "DELETE /roles/{id}: " + result),
                    () -> assertFalse(roleRepository.existsById(role.getId()), "role must be gone"),
                    () -> assertEquals(404, readBack.status(), "GET /roles/{id} after delete: " + readBack));
        }
    }

    // =====================================================================================================
    // Fixtures
    // =====================================================================================================

    private record Tenant(Company company, User admin) {
    }

    /**
     * The five permission sets of a role, in the order used by the UI.
     */
    private record Perms(Set<PermissionEntity> view, Set<PermissionEntity> viewOther,
                         Set<PermissionEntity> create, Set<PermissionEntity> editOther,
                         Set<PermissionEntity> deleteOther) {
    }

    private record RoleSnapshot(String name, String description, String externalId, Perms perms) {
    }

    private record References(Long createdBy, Long primaryUser, List<Long> assignedTo) {
    }

    private record HttpResult(int status, String body) {
        boolean is2xx() {
            return status >= 200 && status < 300;
        }

        boolean is4xx() {
            return status >= 400 && status < 500;
        }
    }

    private static Set<PermissionEntity> set(PermissionEntity... entities) {
        return entities.length == 0 ? EnumSet.noneOf(PermissionEntity.class) : EnumSet.copyOf(Arrays.asList(entities));
    }

    private static Set<PermissionEntity> all() {
        return EnumSet.allOf(PermissionEntity.class);
    }

    /**
     * A company on a plan with every feature enabled (INV-02), with the default ADMIN role assigned to its owner, as
     * signup does.
     */
    private Tenant newTenant(String label) {
        SecurityContextHolder.clearContext();
        SubscriptionPlan plan = subscriptionPlanRepository.save(SubscriptionPlan.builder()
                .name("INC-02 plan " + label)
                .monthlyCostPerUser(0.0)
                .yearlyCostPerUser(0.0)
                .features(EnumSet.allOf(PlanFeatures.class))
                .build());
        createdPlanIds.add(plan.getId());
        Subscription subscription = subscriptionRepository.save(Subscription.builder()
                .usersCount(50)
                .subscriptionPlan(plan)
                .build());

        Company company = companyRepository.save(new Company("INC-02 " + label, 10, subscription));
        createdCompanyIds.add(company.getId());
        CompanySettings settings = company.getCompanySettings();
        settings.setCompany(company);
        companySettingsRepository.save(settings);
        GeneralPreferences preferences = settings.getGeneralPreferences();
        preferences.setCurrency(currencyRepository.findFirstBy().orElseThrow());
        preferences.setDateFormat(DateFormat.DDMMYY);
        preferences.setTimeZone("UTC");
        generalPreferencesRepository.save(preferences);

        User admin = createUser(company, roleService.findDefaultRoleWithCode(RoleCode.ADMIN).orElseThrow());
        return new Tenant(company, admin);
    }

    private Role createCustomRole(Company company, String name, Perms perms) {
        SecurityContextHolder.clearContext();
        return roleRepository.save(Role.builder()
                .name(name)
                .roleType(RoleType.ROLE_CLIENT)
                .code(RoleCode.USER_CREATED)
                .paid(true)
                .companySettings(company.getCompanySettings())
                .viewPermissions(new HashSet<>(perms.view()))
                .viewOtherPermissions(new HashSet<>(perms.viewOther()))
                .createPermissions(new HashSet<>(perms.create()))
                .editOtherPermissions(new HashSet<>(perms.editOther()))
                .deleteOtherPermissions(new HashSet<>(perms.deleteOther()))
                .build());
    }

    private User createUser(Company company, Role role) {
        SecurityContextHolder.clearContext();
        String unique = UUID.randomUUID().toString();
        User user = new User();
        user.setFirstName("Inc02");
        user.setLastName("User");
        user.setEmail("inc02-" + unique + "@example.com");
        user.setUsername(unique);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setRole(role);
        user.setCompany(company);
        user.setEnabled(true);
        user.setEnabledInSubscription(true);
        user.setSuperAccountRelations(new ArrayList<>());
        user.setUserSettings(new UserSettings());
        return userRepository.save(user);
    }

    private Request createRequest(Company company, User createdBy, User assignee) {
        SecurityContextHolder.clearContext();
        Request request = new Request();
        request.setTitle("INC-02 request " + UUID.randomUUID());
        request.setCompany(company);
        request.setCreatedBy(createdBy.getId());
        if (assignee != null) {
            request.setPrimaryUser(assignee);
            request.setAssignedTo(new ArrayList<>(List.of(assignee)));
        }
        return requestRepository.save(request);
    }

    private WorkOrder createWorkOrder(Company company, User createdBy, User assignee) {
        SecurityContextHolder.clearContext();
        WorkOrder workOrder = new WorkOrder();
        workOrder.setTitle("INC-02 work order " + UUID.randomUUID());
        workOrder.setCompany(company);
        workOrder.setCreatedBy(createdBy.getId());
        if (assignee != null) {
            workOrder.setPrimaryUser(assignee);
            workOrder.setAssignedTo(new ArrayList<>(List.of(assignee)));
        }
        return workOrderRepository.save(workOrder);
    }

    // =====================================================================================================
    // HTTP helpers
    // =====================================================================================================

    private String login(User user) throws Exception {
        ObjectNode credentials = objectMapper.createObjectNode()
                .put("email", user.getEmail())
                .put("password", PASSWORD);
        MvcResult result = mockMvc.perform(post("/auth/signin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(credentials)))
                .andReturn();
        assertEquals(200, result.getResponse().getStatus(),
                "login precondition failed: " + result.getResponse().getContentAsString());
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private HttpResult call(MockHttpServletRequestBuilder builder, String token) throws Exception {
        MvcResult result = mockMvc.perform(builder.header(HttpHeaders.AUTHORIZATION, Consts.TOKEN_PREFIX + token))
                .andReturn();
        SecurityContextHolder.clearContext();
        return new HttpResult(result.getResponse().getStatus(), result.getResponse().getContentAsString());
    }

    private HttpResult createRequestViaApi(String token, String title) throws Exception {
        ObjectNode body = objectMapper.createObjectNode().put("title", title);
        return call(post("/requests").contentType(MediaType.APPLICATION_JSON).content(json(body)), token);
    }

    private HttpResult approve(String token, Long requestId) throws Exception {
        return call(patch("/requests/" + requestId + "/approve").contentType(MediaType.APPLICATION_JSON)
                .content("{}"), token);
    }

    private HttpResult reject(String token, Long requestId) throws Exception {
        return call(patch("/requests/" + requestId + "/cancel").param("reason", "INC-02 rejection"), token);
    }

    private HttpResult complete(String token, Long workOrderId) throws Exception {
        ObjectNode body = objectMapper.createObjectNode().put("status", Status.COMPLETE.name());
        return call(patch("/work-orders/" + workOrderId + "/change-status").contentType(MediaType.APPLICATION_JSON)
                .content(json(body)), token);
    }

    /**
     * Same body the web UI sends on role creation (Settings/Roles/PageHeader.tsx through formatValues).
     */
    private ObjectNode uiCreatePayload(Company company, String name, Perms perms) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("name", name);
        payload.put("description", "");
        payload.put("externalId", "");
        payload.putNull("submit");
        payload.putObject("companySettings").put("id", company.getCompanySettings().getId());
        payload.put("roleType", RoleType.ROLE_CLIENT.name());
        putPerms(payload, perms);
        return payload;
    }

    /**
     * Same body the web UI sends on role edition (Settings/Roles/EditRole.tsx through formatValues): the role as
     * returned by the API, with the five permission arrays replaced.
     */
    private HttpResult patchRoleLikeUi(String token, Company company, Long roleId, Perms perms) throws Exception {
        HttpResult current = call(get("/roles/" + roleId), token);
        ObjectNode payload = current.is2xx()
                ? (ObjectNode) objectMapper.readTree(current.body())
                : objectMapper.createObjectNode().put("id", roleId).put("name", "INC-02 tampered role");
        payload.putNull("submit");
        payload.putObject("companySettings").put("id", company.getCompanySettings().getId());
        payload.put("roleType", RoleType.ROLE_CLIENT.name());
        putPerms(payload, perms);
        return call(patch("/roles/" + roleId).contentType(MediaType.APPLICATION_JSON).content(json(payload)), token);
    }

    /**
     * An administrator of the tenant replaces the role permissions through the API, as the UI does.
     */
    private void revoke(Tenant tenant, Role role, Perms remaining) throws Exception {
        HttpResult patched = patchRoleLikeUi(login(tenant.admin()), tenant.company(), role.getId(), remaining);
        assertTrue(patched.is2xx(), "precondition, PATCH /roles/{id} by the administrator: " + patched);
        assertEquals(remaining, permsInDb(role.getId()), "precondition, permission removed in the database");
    }

    /**
     * An administrator of the tenant replaces the role permissions through the API, as the UI does.
     */
    private void grant(Tenant tenant, Role role, Perms granted) throws Exception {
        HttpResult patched = patchRoleLikeUi(login(tenant.admin()), tenant.company(), role.getId(), granted);
        assertTrue(patched.is2xx(), "precondition, PATCH /roles/{id} by the administrator: " + patched);
        assertEquals(granted, permsInDb(role.getId()), "precondition, permission granted in the database");
    }

    private void putPerms(ObjectNode node, Perms perms) {
        putArray(node, "viewPermissions", perms.view());
        putArray(node, "viewOtherPermissions", perms.viewOther());
        putArray(node, "createPermissions", perms.create());
        putArray(node, "editOtherPermissions", perms.editOther());
        putArray(node, "deleteOtherPermissions", perms.deleteOther());
    }

    private void putArray(ObjectNode node, String field, Set<PermissionEntity> values) {
        ArrayNode array = node.putArray(field);
        values.forEach(value -> array.add(value.name()));
    }

    private Perms readPerms(JsonNode role) {
        return new Perms(readSet(role, "viewPermissions"), readSet(role, "viewOtherPermissions"),
                readSet(role, "createPermissions"), readSet(role, "editOtherPermissions"),
                readSet(role, "deleteOtherPermissions"));
    }

    private Set<PermissionEntity> readSet(JsonNode role, String field) {
        Set<PermissionEntity> result = EnumSet.noneOf(PermissionEntity.class);
        role.path(field).forEach(value -> result.add(PermissionEntity.valueOf(value.asText())));
        return result;
    }

    private Set<Long> idsIn(String jsonArray) throws Exception {
        Set<Long> ids = new HashSet<>();
        objectMapper.readTree(jsonArray).forEach(node -> ids.add(node.get("id").asLong()));
        return ids;
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    // =====================================================================================================
    // Database checks (fresh reads, outside the request transactions)
    // =====================================================================================================

    private <T> T inTx(Supplier<T> supplier) {
        return transactionTemplate.execute(status -> supplier.get());
    }

    private Perms permsInDb(Long roleId) {
        return inTx(() -> toPerms(roleRepository.findById(roleId).orElseThrow()));
    }

    private static Perms toPerms(Role role) {
        return new Perms(copy(role.getViewPermissions()), copy(role.getViewOtherPermissions()),
                copy(role.getCreatePermissions()), copy(role.getEditOtherPermissions()),
                copy(role.getDeleteOtherPermissions()));
    }

    private static Set<PermissionEntity> copy(Set<PermissionEntity> values) {
        return values == null || values.isEmpty() ? set() : EnumSet.copyOf(values);
    }

    private RoleSnapshot snapshot(Long roleId) {
        return inTx(() -> {
            Role role = roleRepository.findById(roleId).orElseThrow();
            return new RoleSnapshot(role.getName(), role.getDescription(), role.getExternalId(), toPerms(role));
        });
    }

    /**
     * Default roles are shared by every company: put the role back if a test managed to change or delete it.
     */
    private void restoreDefaultRole(RoleCode code, RoleSnapshot expected) {
        if (roleService.findDefaultRoleWithCode(code).isEmpty()) {
            roleService.updateDefaultRoles();
        }
        inTx(() -> {
            Role role = roleService.findDefaultRoleWithCode(code).orElseThrow();
            role.setName(expected.name());
            role.setDescription(expected.description());
            role.setExternalId(expected.externalId());
            role.setViewPermissions(new HashSet<>(expected.perms().view()));
            role.setViewOtherPermissions(new HashSet<>(expected.perms().viewOther()));
            role.setCreatePermissions(new HashSet<>(expected.perms().create()));
            role.setEditOtherPermissions(new HashSet<>(expected.perms().editOther()));
            role.setDeleteOtherPermissions(new HashSet<>(expected.perms().deleteOther()));
            return roleRepository.save(role);
        });
    }

    private Long roleIdOfUser(Long userId) {
        return inTx(() -> userRepository.findById(userId)
                .map(User::getRole)
                .map(Role::getId)
                .orElse(null));
    }

    private Set<String> roleNamesOf(Company company) {
        return inTx(() -> {
            Set<String> names = new HashSet<>();
            roleRepository.findByCompany_Id(company.getId()).forEach(role -> names.add(role.getName()));
            return names;
        });
    }

    private Long workOrderIdOf(Long requestId) {
        return inTx(() -> {
            WorkOrder workOrder = requestRepository.findById(requestId).orElseThrow().getWorkOrder();
            return workOrder == null ? null : workOrder.getId();
        });
    }

    private boolean isCancelled(Long requestId) {
        return inTx(() -> requestRepository.findById(requestId).orElseThrow().isCancelled());
    }

    private Status statusOf(Long workOrderId) {
        return inTx(() -> workOrderRepository.findById(workOrderId).orElseThrow().getStatus());
    }

    private References requestReferences(Long requestId) {
        return inTx(() -> requestRepository.findById(requestId).map(this::referencesOf).orElse(null));
    }

    private References workOrderReferences(Long workOrderId) {
        return inTx(() -> workOrderRepository.findById(workOrderId).map(this::referencesOf).orElse(null));
    }

    private References referencesOf(WorkOrderBase base) {
        return new References(base.getCreatedBy(),
                base.getPrimaryUser() == null ? null : base.getPrimaryUser().getId(),
                base.getAssignedTo().stream().map(User::getId).sorted().toList());
    }
}
