package com.grash.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grash.integration.support.TestApi;
import com.grash.integration.support.TestApi.Response;
import com.grash.integration.support.TestOrganizations;
import com.grash.integration.support.TestOrganizations.CommonData;
import com.grash.integration.support.TestOrganizations.O1;
import com.grash.integration.support.WebhookDouble;
import com.grash.model.ApiKey;
import com.grash.model.Asset;
import com.grash.model.AssetDowntime;
import com.grash.model.Company;
import com.grash.model.Part;
import com.grash.model.Role;
import com.grash.model.User;
import com.grash.model.UserInvitation;
import com.grash.model.WebhookEndpoint;
import com.grash.model.enums.AssetStatus;
import com.grash.model.enums.PermissionEntity;
import com.grash.model.enums.RoleCode;
import com.grash.model.enums.RoleType;
import com.grash.model.enums.Status;
import com.grash.model.enums.webhook.WebhookEvent;
import com.grash.repository.ApiKeyRepository;
import com.grash.repository.AssetDowntimeRepository;
import com.grash.repository.AssetRepository;
import com.grash.repository.PartRepository;
import com.grash.repository.RoleRepository;
import com.grash.repository.UserInvitationRepository;
import com.grash.repository.UserRepository;
import com.grash.repository.WebhookEndpointRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;

/**
 * P-10 characterization: fixes the behavior of the five deletions that P-10 connects to the audit log (part, API
 * key, webhook endpoint, role, asset) before any change. Every test here must keep passing after P-10: status, body
 * and effects of the allowed deletion, the 403 and 404 answers, and the 409 of a role with users.
 */
@AutoConfigureMockMvc
class DeletionCharacterizationIntegrationTest extends AbstractIntegrationTest {

    private static final String DELETED_BODY = "{\"success\":true,\"message\":\"Deleted successfully\"}";
    private static final String ROLE_IN_USE_MESSAGE = "This role is assigned to at least one user. Assign another "
            + "role to those users before deleting it";

    private final TestOrganizations organizations;
    private final TestApi api;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final PartRepository partRepository;
    private final ApiKeyRepository apiKeyRepository;
    private final WebhookEndpointRepository webhookEndpointRepository;
    private final RoleRepository roleRepository;
    private final UserInvitationRepository userInvitationRepository;
    private final UserRepository userRepository;
    private final AssetRepository assetRepository;
    private final AssetDowntimeRepository assetDowntimeRepository;
    private final List<CommonData> created = new ArrayList<>();

    @Autowired
    DeletionCharacterizationIntegrationTest(TestOrganizations organizations, MockMvc mockMvc,
                                            ObjectMapper objectMapper, JdbcTemplate jdbcTemplate,
                                            PartRepository partRepository, ApiKeyRepository apiKeyRepository,
                                            WebhookEndpointRepository webhookEndpointRepository,
                                            RoleRepository roleRepository,
                                            UserInvitationRepository userInvitationRepository,
                                            UserRepository userRepository, AssetRepository assetRepository,
                                            AssetDowntimeRepository assetDowntimeRepository) {
        this.organizations = organizations;
        this.api = new TestApi(mockMvc, objectMapper);
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.partRepository = partRepository;
        this.apiKeyRepository = apiKeyRepository;
        this.webhookEndpointRepository = webhookEndpointRepository;
        this.roleRepository = roleRepository;
        this.userInvitationRepository = userInvitationRepository;
        this.userRepository = userRepository;
        this.assetRepository = assetRepository;
        this.assetDowntimeRepository = assetDowntimeRepository;
    }

    @AfterEach
    void cleanUp() {
        created.forEach(organizations::delete);
        created.clear();
    }

    private O1 newO1() {
        CommonData data = organizations.create();
        created.add(data);
        return data.o1();
    }

    private CommonData newData() {
        CommonData data = organizations.create();
        created.add(data);
        return data;
    }

    private long count(String sql, Object... arguments) {
        Long result = jdbcTemplate.queryForObject(sql, Long.class, arguments);
        return result == null ? 0 : result;
    }

    private JsonNode json(Response response) {
        try {
            return objectMapper.readTree(response.body());
        } catch (Exception e) {
            throw new AssertionError("not JSON: " + response, e);
        }
    }

    private void assertRejected(Response response, int status, String message) {
        JsonNode body = json(response);
        assertAll(
                () -> assertEquals(status, response.status(), response.toString()),
                () -> assertFalse(body.get("success").asBoolean(), response.toString()),
                () -> assertEquals(message, body.get("message").asText(), response.toString()),
                () -> assertNull(body.get("code"), "no code: " + response));
    }

    // =====================================================================================================
    // Fixtures
    // =====================================================================================================

    private Part newPart(Company company, String name) {
        Part part = new Part();
        part.setName(name);
        part.setCompany(company);
        part.setFiles(new ArrayList<>());
        part.setAssignedTo(new ArrayList<>());
        part.setCustomers(new ArrayList<>());
        part.setVendors(new ArrayList<>());
        part.setTeams(new ArrayList<>());
        part.setAssets(new ArrayList<>());
        part.setCustomFieldValues(new ArrayList<>());
        return partRepository.saveAndFlush(part);
    }

    private Asset newAsset(Company company, String name) {
        Asset asset = new Asset();
        asset.setName(name);
        asset.setCompany(company);
        asset.setStatus(AssetStatus.OPERATIONAL);
        asset.setFiles(new ArrayList<>());
        asset.setCustomers(new ArrayList<>());
        asset.setTeams(new ArrayList<>());
        asset.setParts(new ArrayList<>());
        asset.setCustomFieldValues(new ArrayList<>());
        return assetRepository.saveAndFlush(asset);
    }

    private ApiKey newApiKey(User owner) {
        ApiKey apiKey = new ApiKey();
        apiKey.setLabel("P-10 key " + UUID.randomUUID());
        apiKey.setCode("p10-" + UUID.randomUUID());
        apiKey.setUser(owner);
        apiKey.setCompany(owner.getCompany());
        return apiKeyRepository.saveAndFlush(apiKey);
    }

    private WebhookEndpoint newEndpoint(Company company, WebhookEvent event) {
        WebhookEndpoint endpoint = new WebhookEndpoint();
        endpoint.setUrl(WebhookDouble.ENDPOINT_URL);
        endpoint.setSecret("p10-secret");
        endpoint.setEvent(event);
        endpoint.setCompany(company);
        return webhookEndpointRepository.saveAndFlush(endpoint);
    }

    private Role newRole(Company company, String name) {
        return roleRepository.saveAndFlush(Role.builder()
                .name(name)
                .roleType(RoleType.ROLE_CLIENT)
                .code(RoleCode.USER_CREATED)
                .paid(true)
                .companySettings(company.getCompanySettings())
                .viewPermissions(new HashSet<>(Set.of(PermissionEntity.WORK_ORDERS)))
                .viewOtherPermissions(new HashSet<>())
                .createPermissions(new HashSet<>(Set.of(PermissionEntity.WORK_ORDERS)))
                .editOtherPermissions(new HashSet<>())
                .deleteOtherPermissions(new HashSet<>())
                .build());
    }

    // =====================================================================================================
    // Part: DELETE /parts/{id}
    // =====================================================================================================

    @Nested
    class PartDeletion {

        @Test
        void admin_deletesPart_200_partGone_partDeleteWebhookSent() {
            O1 o1 = newO1();
            newEndpoint(o1.company(), WebhookEvent.PART_DELETE);
            Part part = newPart(o1.company(), "P-10 part");

            Response response = api.call(delete("/parts/" + part.getId()), api.login(o1.admin()));

            assertAll(
                    () -> assertEquals(200, response.status(), response.toString()),
                    () -> assertEquals(DELETED_BODY, response.body()),
                    () -> assertFalse(partRepository.existsById(part.getId()), "part must be gone"));
            assertEquals(1, webhookDouble.awaitCalls(WebhookEvent.PART_DELETE.name(), 1,
                    Duration.ofSeconds(10)).size(), "PART_DELETE sent once");
        }

        @Test
        void technicianWithoutDeleteOther_403_partKept() {
            O1 o1 = newO1();
            Part part = newPart(o1.company(), "P-10 part");

            Response response = api.call(delete("/parts/" + part.getId()), api.login(o1.technician()));

            assertRejected(response, 403, "Forbidden");
            assertTrue(partRepository.existsById(part.getId()), "part must still exist");
        }

        @Test
        void unknownPart_404() {
            O1 o1 = newO1();

            Response response = api.call(delete("/parts/" + Long.MAX_VALUE), api.login(o1.admin()));

            assertRejected(response, 404, "Part not found");
        }

        @Test
        void adminOfOtherOrganization_403_partKept() {
            CommonData data = newData();
            Part part = newPart(data.o1().company(), "P-10 part");

            Response response = api.call(delete("/parts/" + part.getId()), api.login(data.o2().admin()));

            assertEquals(403, response.status(), response.toString());
            assertTrue(partRepository.existsById(part.getId()), "part must still exist");
        }
    }

    // =====================================================================================================
    // API key: DELETE /api-keys/{id}
    // =====================================================================================================

    @Nested
    class ApiKeyDeletion {

        @Test
        void admin_deletesApiKey_200_keyGone() {
            O1 o1 = newO1();
            ApiKey apiKey = newApiKey(o1.owner());

            Response response = api.call(delete("/api-keys/" + apiKey.getId()), api.login(o1.admin()));

            assertAll(
                    () -> assertEquals(200, response.status(), response.toString()),
                    () -> assertEquals(DELETED_BODY, response.body()),
                    () -> assertFalse(apiKeyRepository.existsById(apiKey.getId()), "key must be gone"));
        }

        @Test
        void technicianWithoutSettings_403_keyKept() {
            O1 o1 = newO1();
            ApiKey apiKey = newApiKey(o1.owner());

            Response response = api.call(delete("/api-keys/" + apiKey.getId()), api.login(o1.technician()));

            assertRejected(response, 403, "Access denied");
            assertTrue(apiKeyRepository.existsById(apiKey.getId()), "key must still exist");
        }

        @Test
        void unknownApiKey_404() {
            O1 o1 = newO1();

            Response response = api.call(delete("/api-keys/" + Long.MAX_VALUE), api.login(o1.admin()));

            assertRejected(response, 404, "Not found");
        }
    }

    // =====================================================================================================
    // Webhook endpoint: DELETE /webhook-endpoints/{id}
    // =====================================================================================================

    @Nested
    class WebhookEndpointDeletion {

        @Test
        void admin_deletesEndpoint_200_endpointAndFiltersGone() {
            O1 o1 = newO1();
            WebhookEndpoint endpoint = newEndpoint(o1.company(), WebhookEvent.WORK_ORDER_CHANGE);
            jdbcTemplate.update("INSERT INTO webhook_endpoint_work_order_statuses (webhook_endpoint_id, "
                    + "work_order_statuses) VALUES (?, ?)", endpoint.getId(), Status.OPEN.name());

            Response response = api.call(delete("/webhook-endpoints/" + endpoint.getId()), api.login(o1.admin()));

            assertAll(
                    () -> assertEquals(200, response.status(), response.toString()),
                    () -> assertEquals(DELETED_BODY, response.body()),
                    () -> assertFalse(webhookEndpointRepository.existsById(endpoint.getId()), "endpoint gone"),
                    () -> assertEquals(0, count("SELECT count(*) FROM webhook_endpoint_work_order_statuses "
                            + "WHERE webhook_endpoint_id = ?", endpoint.getId()), "status filter gone"));
        }

        @Test
        void unknownEndpoint_404() {
            O1 o1 = newO1();

            Response response = api.call(delete("/webhook-endpoints/" + Long.MAX_VALUE), api.login(o1.admin()));

            assertRejected(response, 404, "Webhook endpoint not found");
        }
    }

    // =====================================================================================================
    // Role: DELETE /roles/{id}
    // =====================================================================================================

    @Nested
    class RoleDeletion {

        @Test
        void admin_deletesRoleWithPendingInvitation_200_roleAndInvitationGone() {
            O1 o1 = newO1();
            Role role = newRole(o1.company(), "P-10 role " + UUID.randomUUID());
            UserInvitation invitation = userInvitationRepository.saveAndFlush(UserInvitation.builder()
                    .email("p10-invited-" + UUID.randomUUID() + "@example.com").role(role).build());

            Response response = api.call(delete("/roles/" + role.getId()), api.login(o1.admin()));

            assertAll(
                    () -> assertEquals(200, response.status(), response.toString()),
                    () -> assertEquals(DELETED_BODY, response.body()),
                    () -> assertFalse(roleRepository.existsById(role.getId()), "role gone"),
                    () -> assertFalse(userInvitationRepository.existsById(invitation.getId()), "invitation gone"),
                    () -> assertEquals(0, count("SELECT count(*) FROM role_view_permissions WHERE role_id = ?",
                            role.getId()), "permissions gone"));
        }

        @Test
        void roleWithUser_409_roleInUse_codeAndMessageKept() {
            O1 o1 = newO1();
            Role role = newRole(o1.company(), "P-10 role " + UUID.randomUUID());
            User holder = userRepository.findById(o1.technician().getId()).orElseThrow();
            holder.setRole(role);
            userRepository.saveAndFlush(holder);

            Response response = api.call(delete("/roles/" + role.getId()), api.login(o1.admin()));

            JsonNode body = json(response);
            assertAll(
                    () -> assertEquals(409, response.status(), response.toString()),
                    () -> assertFalse(body.get("success").asBoolean()),
                    () -> assertEquals(ROLE_IN_USE_MESSAGE, body.get("message").asText()),
                    () -> assertEquals("ROLE_IN_USE", body.get("code").asText()),
                    () -> assertTrue(roleRepository.existsById(role.getId()), "role must still exist"));
        }

        @Test
        void technicianWithoutSettings_403_roleKept() {
            O1 o1 = newO1();
            Role role = newRole(o1.company(), "P-10 role " + UUID.randomUUID());

            Response response = api.call(delete("/roles/" + role.getId()), api.login(o1.technician()));

            assertEquals(403, response.status(), response.toString());
            assertTrue(roleRepository.existsById(role.getId()), "role must still exist");
        }

        @Test
        void unknownRole_404() {
            O1 o1 = newO1();

            Response response = api.call(delete("/roles/" + Long.MAX_VALUE), api.login(o1.admin()));

            assertRejected(response, 404, "Role not found");
        }
    }

    // =====================================================================================================
    // Asset: DELETE /assets/{id}
    // =====================================================================================================

    @Nested
    class AssetDeletion {

        @Test
        void admin_deletesAssetWithDowntime_200_assetAndDowntimeGone() {
            O1 o1 = newO1();
            Asset asset = newAsset(o1.company(), "P-10 asset");
            AssetDowntime downtime = new AssetDowntime();
            downtime.setAsset(asset);
            downtime.setStartsOn(new Date());
            downtime.setCompany(o1.company());
            AssetDowntime savedDowntime = assetDowntimeRepository.saveAndFlush(downtime);

            Response response = api.call(delete("/assets/" + asset.getId()), api.login(o1.admin()));

            assertAll(
                    () -> assertEquals(200, response.status(), response.toString()),
                    () -> assertEquals(DELETED_BODY, response.body()),
                    () -> assertFalse(assetRepository.existsById(asset.getId()), "asset gone"),
                    () -> assertFalse(assetDowntimeRepository.existsById(savedDowntime.getId()), "downtime gone"));
            assertAll(
                    () -> assertTrue(mailDouble.calls().isEmpty(), "no mail"),
                    () -> assertTrue(webhookDouble.calls().isEmpty(), "no webhook"),
                    () -> assertTrue(storageDouble.calls().isEmpty(), "no storage call"));
        }

        @Test
        void technicianWithoutDeleteOther_403_assetKept() {
            O1 o1 = newO1();
            Asset asset = newAsset(o1.company(), "P-10 asset");

            Response response = api.call(delete("/assets/" + asset.getId()), api.login(o1.technician()));

            assertRejected(response, 403, "Forbidden");
            assertTrue(assetRepository.existsById(asset.getId()), "asset must still exist");
        }

        @Test
        void unknownAsset_404() {
            O1 o1 = newO1();

            Response response = api.call(delete("/assets/" + Long.MAX_VALUE), api.login(o1.admin()));

            assertRejected(response, 404, "Asset not found");
        }
    }
}
