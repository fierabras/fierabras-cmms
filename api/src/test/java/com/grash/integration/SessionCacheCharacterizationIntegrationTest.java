package com.grash.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.grash.integration.support.Concurrently;
import com.grash.integration.support.Concurrently.Outcome;
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
import com.grash.service.CacheService;
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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * P-22 (step 4): the behaviour before the fix of the API keys of disabled users, of role and permission changes
 * during an open session, and of the user cache under concurrent requests (C-11, C-18). Assertions fix today's
 * answers; the race and the SQL cost are recorded in the log (lines starting with {@code P-22}).
 */
@AutoConfigureMockMvc
class SessionCacheCharacterizationIntegrationTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(SessionCacheCharacterizationIntegrationTest.class);
    private static final String API_KEY_HEADER = "x-api-key";

    private final TestOrganizations organizations;
    private final TestApi api;
    private final ObjectMapper objectMapper;
    private final RoleRepository roleRepository;
    private final CacheService cacheService;
    private final Statistics statistics;
    private CommonData data;

    @Autowired
    SessionCacheCharacterizationIntegrationTest(TestOrganizations organizations, MockMvc mockMvc,
                                                ObjectMapper objectMapper, RoleRepository roleRepository,
                                                CacheService cacheService,
                                                EntityManagerFactory entityManagerFactory) {
        this.organizations = organizations;
        this.api = new TestApi(mockMvc, objectMapper);
        this.objectMapper = objectMapper;
        this.roleRepository = roleRepository;
        this.cacheService = cacheService;
        this.statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    @BeforeEach
    void createOrganizations() {
        data = organizations.create();
    }

    @AfterEach
    void deleteOrganizations() {
        statistics.setStatisticsEnabled(false);
        organizations.delete(data);
    }

    // ------------------------------------------------------------------------------------------------------------
    // API keys (C-18)
    // ------------------------------------------------------------------------------------------------------------

    @Test
    void apiKeyOfDisabledUser_isStillAccepted_andAgainAfterRestore() {
        User admin = data.o1().admin();
        String key = createApiKey(api.login(admin));
        assertEquals(200, withKey(get("/users/mini"), key).status(), "precondition: the key works");
        String owner = api.login(data.o1().owner());
        assertEquals(200, api.call(patch("/users/" + admin.getId() + "/disable"), owner).status());

        Response disabled = withKey(get("/users/mini"), key);
        assertEquals(200, api.call(patch("/users/" + admin.getId() + "/enable"), owner).status());
        Response restored = withKey(get("/users/mini"), key);

        log.info("P-22 api key: disabled user -> {}, restored user -> {}", disabled.status(), restored.status());
        assertEquals(200, disabled.status(), "today: the key of a disabled user is accepted " + disabled);
        assertEquals(200, restored.status(), restored.toString());
    }

    @Test
    void apiKeyAfterOwnAccountDeleted_orSoftDelete_isStillAccepted() {
        User admin = data.o1().admin();
        String key = createApiKey(api.login(admin));
        assertEquals(200, api.call(delete("/auth"), api.login(admin)).status(), "delete my account");
        Response afterOwnDelete = withKey(get("/users/mini"), key);

        User technician = data.o1().technician();
        String owner = api.login(data.o1().owner());
        Role technicianRole = technician.getRole();
        // the technician needs SETTINGS to create a key: give them the administrator role first
        assertEquals(200, api.call(patch("/users/" + technician.getId() + "/role")
                .param("role", data.o1().owner().getRole().getId().toString()), owner).status());
        String technicianKey = createApiKey(api.login(technician));
        assertEquals(200, api.call(patch("/users/soft-delete/" + technician.getId()), owner).status());
        Response afterSoftDelete = withKey(get("/users/mini"), technicianKey);

        log.info("P-22 api key: own account deleted -> {}, soft-deleted -> {} (technician role {})",
                afterOwnDelete.status(), afterSoftDelete.status(), technicianRole.getId());
        assertEquals(200, afterOwnDelete.status(), afterOwnDelete.toString());
        assertEquals(200, afterSoftDelete.status(), afterSoftDelete.toString());
    }

    // ------------------------------------------------------------------------------------------------------------
    // Role and permissions during an open session (C-11, C-18)
    // ------------------------------------------------------------------------------------------------------------

    @Test
    void roleChange_withOpenSession_appliesOnlyAfterANewSignIn() {
        User technician = data.o1().technician();
        Long probe = technician.getRole().getId();
        String token = api.login(technician);
        assertEquals(403, api.call(get("/roles/" + probe), token).status(), "precondition: no SETTINGS");

        String admin = api.login(data.o1().admin());
        Response changed = api.call(patch("/users/" + technician.getId() + "/role")
                .param("role", data.o1().admin().getRole().getId().toString()), admin);
        assertEquals(200, changed.status(), changed.toString());

        Response sameSession = api.call(get("/roles/" + probe), token);
        Response newSession = api.call(get("/roles/" + probe), api.login(technician));

        log.info("P-22 role change: same session -> {}, after new sign in -> {}", sameSession.status(),
                newSession.status());
        assertEquals(403, sameSession.status(), "today: the cached role still applies " + sameSession);
        assertEquals(200, newSession.status(), newSession.toString());
    }

    @Test
    void rolePermissionsEdited_withOpenSession_applyOnlyAfterANewSignIn() throws Exception {
        Role custom = customRole(Set.of(PermissionEntity.WORK_ORDERS));
        User technician = data.o1().technician();
        String admin = api.login(data.o1().admin());
        assertEquals(200, api.call(patch("/users/" + technician.getId() + "/role")
                .param("role", custom.getId().toString()), admin).status());
        String token = api.login(technician);
        assertEquals(403, api.call(get("/roles/" + custom.getId()), token).status(), "precondition: no SETTINGS");

        Response edited = patchViewPermissions(admin, custom.getId(),
                Set.of(PermissionEntity.WORK_ORDERS, PermissionEntity.SETTINGS));
        assertEquals(200, edited.status(), edited.toString());

        Response sameSession = api.call(get("/roles/" + custom.getId()), token);
        Response newSession = api.call(get("/roles/" + custom.getId()), api.login(technician));

        log.info("P-22 role permissions: same session -> {}, after new sign in -> {}", sameSession.status(),
                newSession.status());
        assertEquals(403, sameSession.status(), "today: the cached permissions still apply " + sameSession);
        assertEquals(200, newSession.status(), newSession.toString());
    }

    // ------------------------------------------------------------------------------------------------------------
    // Concurrent requests of one user (C-11) and SQL cost of an authenticated request
    // ------------------------------------------------------------------------------------------------------------

    @Test
    void sameUser_concurrentRequestsRightAfterSignIn_recordsTheAnswers() {
        User admin = data.o1().admin();
        int rounds = 5;
        Map<Integer, Integer> statuses = new TreeMap<>();
        String firstServerError = null;
        for (int round = 0; round < rounds; round++) {
            String token = api.login(admin);
            List<Callable<Response>> tasks = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                tasks.add(() -> api.call(json(post("/work-orders"), "{\"title\":\""
                        + TestRecords.unique("P-22 WO") + "\"}"), token));
            }
            for (int i = 0; i < 2; i++) {
                tasks.add(() -> api.call(json(post("/work-orders/search"), "{\"pageSize\":10}"), token));
            }
            tasks.add(() -> api.call(json(post("/assets"), "{\"name\":\"" + TestRecords.unique("P-22 asset")
                    + "\"}"), token));
            tasks.add(() -> api.call(get("/users/mini"), token));
            List<Outcome<Response>> outcomes = Concurrently.run(tasks, Concurrently.DEFAULT_TIMEOUT);
            for (Outcome<Response> outcome : outcomes) {
                assertTrue(outcome.succeeded(), "no request may fail before an HTTP response: " + outcome);
                statuses.merge(outcome.value().status(), 1, Integer::sum);
                if (outcome.value().status() >= 500 && firstServerError == null) {
                    firstServerError = outcome.value().body();
                }
            }
        }
        log.info("P-22 C-11: {} rounds of 7 concurrent requests of one user right after sign in -> statuses {}",
                rounds, statuses);
        log.info("P-22 C-11: first 5xx body: {}", firstServerError);
    }

    @Test
    void sqlStatementsPerAuthenticatedRequest_withWarmAndColdUserCache() {
        User admin = data.o1().admin();
        String token = api.login(admin);
        String key = createApiKey(token);
        statistics.setStatisticsEnabled(true);
        Map<String, Supplier<MockHttpServletRequestBuilder>> requests = Map.of(
                "GET /users/mini", () -> get("/users/mini"),
                "POST /work-orders/search", () -> json(post("/work-orders/search"), "{\"pageSize\":10}"),
                "POST /work-orders", () -> json(post("/work-orders"), "{\"title\":\""
                        + TestRecords.unique("P-22 WO") + "\"}"));
        for (Map.Entry<String, Supplier<MockHttpServletRequestBuilder>> request : requests.entrySet()) {
            long warm = Long.MAX_VALUE;
            long cold = Long.MAX_VALUE;
            long apiKey = Long.MAX_VALUE;
            for (int i = 0; i < 3; i++) {
                api.call(request.getValue().get(), token); // fills the cache
                warm = Math.min(warm, statements(() -> api.call(request.getValue().get(), token)));
                cacheService.evictUserFromCache(admin.getEmail());
                cold = Math.min(cold, statements(() -> api.call(request.getValue().get(), token)));
                apiKey = Math.min(apiKey, statements(() -> withKey(request.getValue().get(), key)));
            }
            log.info("P-22 SQL per request, {}: token warm cache {}, token cold cache {}, API key {}",
                    request.getKey(), warm, cold, apiKey);
        }
        long authOnlyWarm = Long.MAX_VALUE;
        long authOnlyCold = Long.MAX_VALUE;
        for (int i = 0; i < 3; i++) {
            api.call(get("/users/mini"), token);
            authOnlyWarm = Math.min(authOnlyWarm, statements(() -> api.call(get("/auth/me"), token)));
            cacheService.evictUserFromCache(admin.getEmail());
            authOnlyCold = Math.min(authOnlyCold, statements(() -> api.call(get("/auth/me"), token)));
        }
        log.info("P-22 SQL per request, GET /auth/me (uncached whoami): token warm cache {}, token cold cache {}",
                authOnlyWarm, authOnlyCold);
    }

    // ------------------------------------------------------------------------------------------------------------

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
