package com.grash.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import com.grash.model.Role;
import com.grash.model.User;
import com.grash.model.enums.PermissionEntity;
import com.grash.model.enums.RoleCode;
import com.grash.model.enums.RoleType;
import com.grash.repository.RoleRepository;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * P-22 (C-11, C-18): the API key of a disabled user stops working; a change of role or of the permissions of a role
 * applies on the next request of an open session; and concurrent requests of one user never share a user entity.
 */
@AutoConfigureMockMvc
class SessionCacheIntegrationTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(SessionCacheIntegrationTest.class);
    private static final String API_KEY_HEADER = "x-api-key";
    private static final String SETTINGS_PROBE = "/roles/";

    private final TestOrganizations organizations;
    private final TestApi api;
    private final ObjectMapper objectMapper;
    private final RoleRepository roleRepository;
    private final JdbcTemplate jdbcTemplate;
    private final FaultInjector faults;
    private final Statistics statistics;
    private CommonData data;

    @Autowired
    SessionCacheIntegrationTest(TestOrganizations organizations, MockMvc mockMvc, ObjectMapper objectMapper,
                                RoleRepository roleRepository, JdbcTemplate jdbcTemplate,
                                EntityManagerFactory entityManagerFactory) {
        this.organizations = organizations;
        this.api = new TestApi(mockMvc, objectMapper);
        this.objectMapper = objectMapper;
        this.roleRepository = roleRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.faults = new FaultInjector(jdbcTemplate);
        this.statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    @BeforeEach
    void createOrganizations() {
        data = organizations.create();
    }

    @AfterEach
    void deleteOrganizations() {
        faults.removeAll();
        statistics.setStatisticsEnabled(false);
        organizations.delete(data);
    }

    // ------------------------------------------------------------------------------------------------------------
    // E-1: API keys (CA-P22-01, CA-P22-02)
    // ------------------------------------------------------------------------------------------------------------

    @Test
    void ca01_apiKeyOfUserDisabledByAnAdministrator_answers401_andTheOperationIsNotExecuted() {
        User admin = data.o1().admin();
        String key = createApiKey(api.login(admin));
        assertEquals(200, api.call(patch("/users/" + admin.getId() + "/disable"), owner()).status());

        String title = TestRecords.unique("P-22 WO by key");
        Response response = withKey(json(post("/work-orders"), "{\"title\":\"" + title + "\"}"), key);

        assertEquals(401, response.status(), response.toString());
        assertEquals(0, workOrdersTitled(title), "the operation must not be executed");
    }

    @Test
    void ca01_apiKeyOfSoftDeletedUser_answers401() {
        User admin = data.o1().admin();
        String key = createApiKey(api.login(admin));
        assertEquals(200, api.call(patch("/users/soft-delete/" + admin.getId()), owner()).status());

        Response response = withKey(get("/users/mini"), key);

        assertEquals(401, response.status(), response.toString());
    }

    @Test
    void ca01_apiKeyAfterDeleteMyAccount_answers401() {
        User admin = data.o1().admin();
        String token = api.login(admin);
        String key = createApiKey(token);
        assertEquals(200, api.call(delete("/auth"), token).status(), "delete my account");

        Response response = withKey(get("/users/mini"), key);

        assertEquals(401, response.status(), response.toString());
    }

    @Test
    void ca02_apiKeyOfRestoredUser_worksAgain() {
        User admin = data.o1().admin();
        String key = createApiKey(api.login(admin));
        String owner = owner();
        assertEquals(200, api.call(patch("/users/" + admin.getId() + "/disable"), owner).status());
        assertEquals(401, withKey(get("/users/mini"), key).status(), "precondition: disabled");
        assertEquals(200, api.call(patch("/users/" + admin.getId() + "/enable"), owner).status());

        Response response = withKey(get("/users/mini"), key);

        assertEquals(200, response.status(), response.toString());
    }

    // ------------------------------------------------------------------------------------------------------------
    // E-2: role and permissions during an open session (CA-P22-03 to CA-P22-05)
    // ------------------------------------------------------------------------------------------------------------

    @Test
    void ca03_roleChange_appliesOnTheNextRequestOfTheSameSession_bothWays() {
        User technician = data.o1().technician();
        Long probe = technician.getRole().getId();
        String token = api.login(technician);
        assertEquals(403, probe(probe, token).status(), "precondition: the technician role has no SETTINGS");
        String admin = api.login(data.o1().admin());

        assertEquals(200, changeRole(technician, data.o1().admin().getRole(), admin).status());
        Response granted = probe(probe, token);
        assertEquals(200, changeRole(technician, technician.getRole(), admin).status());
        Response revoked = probe(probe, token);

        assertEquals(200, granted.status(), "the new role allows it: " + granted);
        assertEquals(403, revoked.status(), "the new role forbids it: " + revoked);
    }

    @Test
    void ca04_rolePermissionsEdited_applyOnTheNextRequestOfTheSameSession_bothWays() throws Exception {
        Role custom = customRole(Set.of(PermissionEntity.WORK_ORDERS));
        User technician = data.o1().technician();
        String admin = api.login(data.o1().admin());
        assertEquals(200, changeRole(technician, custom, admin).status());
        String token = api.login(technician);
        assertEquals(403, probe(custom.getId(), token).status(), "precondition: the custom role has no SETTINGS");

        assertEquals(200, patchViewPermissions(admin, custom.getId(),
                Set.of(PermissionEntity.WORK_ORDERS, PermissionEntity.SETTINGS)).status());
        Response granted = probe(custom.getId(), token);
        assertEquals(200, patchViewPermissions(admin, custom.getId(), Set.of(PermissionEntity.WORK_ORDERS)).status());
        Response revoked = probe(custom.getId(), token);

        assertEquals(200, granted.status(), "the edited role allows it: " + granted);
        assertEquals(403, revoked.status(), "the edited role forbids it: " + revoked);
    }

    @Test
    void ca05_roleChangeRolledBack_nextRequestUsesThePreviousRole() {
        User technician = data.o1().technician();
        Long probe = technician.getRole().getId();
        String token = api.login(technician);
        String admin = api.login(data.o1().admin());
        assertEquals(403, probe(probe, token).status(), "precondition");

        Response changed;
        try (Fault fault = faults.failAtCommit("own_user", Operation.UPDATE)) {
            changed = changeRole(technician, data.o1().admin().getRole(), admin);
            assertTrue(fault.hits() > 0, "the fault point was crossed");
        }
        Response next = probe(probe, token);

        assertFalse(changed.is2xx(), "the change must not be confirmed: " + changed);
        assertEquals(technician.getRole().getId(), roleIdInDb(technician), "precondition: rolled back");
        assertEquals(403, next.status(), "the previous role still applies: " + next);
    }

    @Test
    void ca05_rolePermissionsEditRolledBack_nextRequestUsesThePreviousPermissions() throws Exception {
        Role custom = customRole(Set.of(PermissionEntity.WORK_ORDERS));
        User technician = data.o1().technician();
        String admin = api.login(data.o1().admin());
        assertEquals(200, changeRole(technician, custom, admin).status());
        String token = api.login(technician);
        assertEquals(403, probe(custom.getId(), token).status(), "precondition");

        Response edited;
        try (Fault fault = faults.failAtCommit("role_view_permissions", Operation.INSERT)) {
            edited = patchViewPermissions(admin, custom.getId(),
                    Set.of(PermissionEntity.WORK_ORDERS, PermissionEntity.SETTINGS));
            assertTrue(fault.hits() > 0, "the fault point was crossed");
        }
        Response next = probe(custom.getId(), token);

        assertFalse(edited.is2xx(), "the edit must not be confirmed: " + edited);
        assertEquals(403, next.status(), "the previous permissions still apply: " + next);
    }

    // ------------------------------------------------------------------------------------------------------------
    // E-3: no user entity shared between threads (CA-P22-06, CA-P22-07, INV-10)
    // ------------------------------------------------------------------------------------------------------------

    @Test
    void ca06_sameUserConcurrentRequestsRightAfterSignIn_noServerError_andEachGetsItsResult() throws Exception {
        User admin = data.o1().admin();
        for (int round = 0; round < 5; round++) {
            String token = api.login(admin);
            List<String> titles = new ArrayList<>();
            List<Callable<Response>> tasks = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                String title = TestRecords.unique("P-22 WO");
                titles.add(title);
                tasks.add(() -> api.call(json(post("/work-orders"), "{\"title\":\"" + title + "\"}"), token));
            }
            for (int i = 0; i < 2; i++) {
                tasks.add(() -> api.call(json(post("/work-orders/search"), "{\"pageSize\":10}"), token));
            }
            String assetName = TestRecords.unique("P-22 asset");
            tasks.add(() -> api.call(json(post("/assets"), "{\"name\":\"" + assetName + "\"}"), token));
            tasks.add(() -> api.call(get("/users/mini"), token));

            List<Outcome<Response>> outcomes = Concurrently.run(tasks, Concurrently.DEFAULT_TIMEOUT);

            for (int i = 0; i < outcomes.size(); i++) {
                Outcome<Response> outcome = outcomes.get(i);
                assertTrue(outcome.succeeded(), "round " + round + ", task " + i + ": " + outcome);
                assertEquals(200, outcome.value().status(), "round " + round + ", task " + i + ": "
                        + outcome.value());
            }
            Set<Long> workOrderIds = new HashSet<>();
            for (int i = 0; i < 3; i++) {
                JsonNode created = objectMapper.readTree(outcomes.get(i).value().body());
                assertEquals(titles.get(i), created.get("title").asText());
                workOrderIds.add(created.get("id").asLong());
                assertEquals(1, workOrdersTitled(titles.get(i)));
            }
            assertEquals(3, workOrderIds.size(), "three distinct work orders");
            for (int i = 3; i < 5; i++) {
                assertTrue(objectMapper.readTree(outcomes.get(i).value().body()).has("content"));
            }
            assertEquals(assetName, objectMapper.readTree(outcomes.get(5).value().body()).get("name").asText());
            assertTrue(userIds(outcomes.get(6).value()).contains(admin.getId()));
        }
    }

    @Test
    void ca07_tokenOfDisabledUser_stillAnswers401() {
        User technician = data.o1().technician();
        String token = api.login(technician);
        assertEquals(200, api.call(get("/users/mini"), token).status(), "precondition");
        assertEquals(200, api.call(patch("/users/" + technician.getId() + "/disable"),
                api.login(data.o1().admin())).status());

        Response response = api.call(get("/users/mini"), token);

        assertEquals(401, response.status(), response.toString());
    }

    @Test
    void isolation_usersOfTwoOrganizationsConcurrently_eachGetsOnlyItsOwnOrganization() throws Exception {
        Set<Long> o1Users = Set.of(data.o1().owner().getId(), data.o1().admin().getId(),
                data.o1().technician().getId());
        Set<Long> o2Users = Set.of(data.o2().admin().getId());
        // one sign in, then rounds in a row: from the second round on, a shared instance would already be loaded
        String o1Token = api.login(data.o1().technician());
        String o2Token = api.login(data.o2().admin());
        for (int round = 0; round < 3; round++) {
            List<Callable<Response>> tasks = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                String token = i % 2 == 0 ? o1Token : o2Token;
                tasks.add(() -> api.call(get("/users/mini"), token));
            }

            List<Outcome<Response>> outcomes = Concurrently.run(tasks, Concurrently.DEFAULT_TIMEOUT);

            for (int i = 0; i < outcomes.size(); i++) {
                Outcome<Response> outcome = outcomes.get(i);
                assertTrue(outcome.succeeded(), outcome.toString());
                assertEquals(200, outcome.value().status(), outcome.value().toString());
                Set<Long> expected = i % 2 == 0 ? o1Users : o2Users;
                assertEquals(expected, userIds(outcome.value()), "round " + round + ", task " + i);
            }
        }
    }

    /**
     * D-3 (G-1): with the token of the technician and the API key of the owner of the same company, the request is
     * evaluated as the technician, as before P-22. (With the key of another company the request fails before an
     * answer, in {@code ApiKey}'s {@code @PostLoad}: unchanged and out of scope.)
     */
    @Test
    void tokenAndApiKeyOfAnotherUser_answerAsTheUserOfTheToken() {
        String ownerKey = createApiKey(owner());
        User technician = data.o1().technician();
        String technicianToken = api.login(technician);
        assertEquals(200, withKey(get(SETTINGS_PROBE + technician.getRole().getId()), ownerKey).status(),
                "precondition: the owner alone may read the role");

        Response response = api.call(get(SETTINGS_PROBE + technician.getRole().getId())
                .header(API_KEY_HEADER, ownerKey), technicianToken);

        assertEquals(403, response.status(), "evaluated as the technician: " + response);
    }

    /**
     * D-1 (G-1): loading the user on every request costs no more SQL statements than a request with an empty user
     * cache did before P-22 (measured in the characterization, commit 8cfcd835).
     */
    @Test
    void sqlStatementsPerAuthenticatedRequest_doNotExceedTheEmptyCacheCostBeforeP22() {
        String token = api.login(data.o1().admin());
        Map<String, Long> bounds = new LinkedHashMap<>();
        bounds.put("GET /users/mini", 17L);
        bounds.put("POST /work-orders", 34L);
        bounds.put("POST /work-orders/search", 57L);
        Map<String, Supplier<MockHttpServletRequestBuilder>> requests = Map.of(
                "GET /users/mini", () -> get("/users/mini"),
                "POST /work-orders", () -> json(post("/work-orders"), "{\"title\":\""
                        + TestRecords.unique("P-22 WO") + "\"}"),
                "POST /work-orders/search", () -> json(post("/work-orders/search"), "{\"pageSize\":10}"));
        statistics.setStatisticsEnabled(true);
        for (Map.Entry<String, Long> bound : bounds.entrySet()) {
            Supplier<MockHttpServletRequestBuilder> request = requests.get(bound.getKey());
            api.call(request.get(), token);
            long statements = Long.MAX_VALUE;
            for (int i = 0; i < 3; i++) {
                statements = Math.min(statements, statements(() -> api.call(request.get(), token)));
            }
            log.info("P-22 SQL per request, {}: {} (bound {})", bound.getKey(), statements, bound.getValue());
            assertTrue(statements <= bound.getValue(), bound.getKey() + ": " + statements + " statements");
        }
    }

    // ------------------------------------------------------------------------------------------------------------

    private String owner() {
        return api.login(data.o1().owner());
    }

    private Response probe(Long roleId, String token) {
        return api.call(get(SETTINGS_PROBE + roleId), token);
    }

    private Response changeRole(User user, Role role, String token) {
        return api.call(patch("/users/" + user.getId() + "/role").param("role", role.getId().toString()), token);
    }

    private Long roleIdInDb(User user) {
        return jdbcTemplate.queryForObject("SELECT role_id FROM own_user WHERE id = ?", Long.class, user.getId());
    }

    private int workOrdersTitled(String title) {
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM work_order WHERE title = ?",
                Integer.class, title);
        return count == null ? 0 : count;
    }

    private Set<Long> userIds(Response response) {
        try {
            Set<Long> ids = new HashSet<>();
            objectMapper.readTree(response.body()).forEach(user -> ids.add(user.get("id").asLong()));
            return ids;
        } catch (Exception e) {
            throw new IllegalStateException(response.toString(), e);
        }
    }

    private long statements(Supplier<Response> call) {
        statistics.clear();
        Response response = call.get();
        assertTrue(response.is2xx(), response.toString());
        return statistics.getPrepareStatementCount();
    }

    private String createApiKey(String token) {
        Response created = api.call(json(post("/api-keys"), "{\"label\":\"" + TestRecords.unique("P-22 key")
                + "\"}"), token);
        assertEquals(200, created.status(), created.toString());
        try {
            return objectMapper.readTree(created.body()).get("code").asText();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Calls with the API key only. Never logs the key.
     */
    private Response withKey(MockHttpServletRequestBuilder request, String key) {
        return api.call(request.header(API_KEY_HEADER, key), null);
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private Role customRole(Set<PermissionEntity> view) {
        SecurityContextHolder.clearContext();
        return roleRepository.save(Role.builder()
                .name(TestRecords.unique("P-22 role"))
                .roleType(RoleType.ROLE_CLIENT)
                .code(RoleCode.USER_CREATED)
                .paid(true)
                .companySettings(data.o1().company().getCompanySettings())
                .viewPermissions(new HashSet<>(view))
                .viewOtherPermissions(new HashSet<>())
                .createPermissions(new HashSet<>())
                .editOtherPermissions(new HashSet<>())
                .deleteOtherPermissions(new HashSet<>())
                .build());
    }

    /**
     * Replaces the view permissions of a role as the web interface does: reads the role and sends it back.
     */
    private Response patchViewPermissions(String token, Long roleId, Set<PermissionEntity> view) throws Exception {
        Response current = api.call(get("/roles/" + roleId), token);
        assertEquals(200, current.status(), current.toString());
        ObjectNode payload = (ObjectNode) objectMapper.readTree(current.body());
        ArrayNode permissions = payload.putArray("viewPermissions");
        view.forEach(permission -> permissions.add(permission.name()));
        return api.call(json(patch("/roles/" + roleId), objectMapper.writeValueAsString(payload)), token);
    }
}
