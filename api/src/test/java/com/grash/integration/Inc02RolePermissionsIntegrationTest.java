package com.grash.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.grash.model.*;
import com.grash.model.abstracts.WorkOrderBase;
import com.grash.model.enums.DateFormat;
import com.grash.model.enums.PermissionEntity;
import com.grash.model.enums.PlanFeatures;
import com.grash.model.enums.RoleCode;
import com.grash.model.enums.RoleType;
import com.grash.model.enums.Status;
import com.grash.repository.*;
import com.grash.service.CompanyService;
import com.grash.service.RoleService;
import com.grash.utils.Consts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
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
    // Authorization
    // =====================================================================================================

    @Nested
    @DisplayName("INC-02 authorization")
    class AuthorizationTests {

        private final Perms requestCreator = new Perms(
                set(PermissionEntity.REQUESTS), set(), set(PermissionEntity.REQUESTS), set(), set());
        private final Perms requestViewer = new Perms(
                set(PermissionEntity.REQUESTS), set(), set(), set(), set());
        private final Perms requestReviewer = new Perms(
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

            assertTrue(result.is2xx(), "PATCH /requests/{id}/approve: " + result);
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

            assertTrue(result.is2xx(), "PATCH /requests/{id}/cancel: " + result);
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
            User target = createUser(tenant.company(), createCustomRole(tenant.company(), "T04 other", requestViewer));

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
