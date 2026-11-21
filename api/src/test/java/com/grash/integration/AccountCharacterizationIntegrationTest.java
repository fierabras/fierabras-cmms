package com.grash.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grash.integration.support.TestApi;
import com.grash.integration.support.TestApi.Response;
import com.grash.integration.support.TestOrganizations;
import com.grash.integration.support.TestOrganizations.CommonData;
import com.grash.integration.support.TestOrganizations.O1;
import com.grash.model.Currency;
import com.grash.model.CustomField;
import com.grash.model.Role;
import com.grash.model.Team;
import com.grash.model.User;
import com.grash.model.enums.CustomFieldEntityType;
import com.grash.model.enums.CustomFieldType;
import com.grash.model.enums.RoleCode;
import com.grash.model.enums.RoleType;
import com.grash.repository.CurrencyRepository;
import com.grash.repository.CustomFieldRepository;
import com.grash.repository.TeamRepository;
import com.grash.repository.UserRepository;
import com.grash.service.RoleService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * P-11 characterization: fixes, before any change, the answers of the account and user operations and of the
 * team, custom field and currency deletions that P-11 changes. Every test here must keep passing after P-11: the
 * 200 bodies, and the 403, 404 and 406 answers with their messages.
 */
@AutoConfigureMockMvc
class AccountCharacterizationIntegrationTest extends AbstractIntegrationTest {

    private static final String DELETED_BODY = "{\"success\":true,\"message\":\"Deleted successfully\"}";
    private static final String ACCOUNT_DELETED_BODY =
            "{\"success\":true,\"message\":\"Account deleted successfully\"}";
    private static final String NO_PERMISSION = "You don't have permission";
    private static final String OWNER_ONLY = "Only the company owner can manage their own user";

    private final TestOrganizations organizations;
    private final TestApi api;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final UserRepository userRepository;
    private final TeamRepository teamRepository;
    private final CustomFieldRepository customFieldRepository;
    private final CurrencyRepository currencyRepository;
    private final RoleService roleService;
    private final PasswordEncoder passwordEncoder;
    private final List<CommonData> created = new ArrayList<>();
    private final List<Long> createdUsers = new ArrayList<>();
    private final List<Long> createdCurrencies = new ArrayList<>();

    @Autowired
    AccountCharacterizationIntegrationTest(TestOrganizations organizations, MockMvc mockMvc,
                                           ObjectMapper objectMapper, JdbcTemplate jdbcTemplate,
                                           UserRepository userRepository, TeamRepository teamRepository,
                                           CustomFieldRepository customFieldRepository,
                                           CurrencyRepository currencyRepository, RoleService roleService,
                                           PasswordEncoder passwordEncoder) {
        this.organizations = organizations;
        this.api = new TestApi(mockMvc, objectMapper);
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.userRepository = userRepository;
        this.teamRepository = teamRepository;
        this.customFieldRepository = customFieldRepository;
        this.currencyRepository = currencyRepository;
        this.roleService = roleService;
        this.passwordEncoder = passwordEncoder;
    }

    @AfterEach
    void cleanUp() {
        createdUsers.forEach(id -> jdbcTemplate.update("DELETE FROM own_user WHERE id = ?", id));
        createdUsers.clear();
        createdCurrencies.forEach(id -> {
            // P-11 records the deletion of a currency without organization (D-14)
            jdbcTemplate.update("DELETE FROM audit_log WHERE company_id IS NULL AND entity_type = 'CURRENCY' "
                    + "AND entity_id = ?", id);
            jdbcTemplate.update("DELETE FROM currency WHERE id = ?", id);
        });
        createdCurrencies.clear();
        created.forEach(organizations::delete);
        created.clear();
    }

    private CommonData newData() {
        CommonData data = organizations.create();
        created.add(data);
        return data;
    }

    private O1 newO1() {
        return newData().o1();
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
                () -> assertEquals(message, body.get("message").asText(), response.toString()));
    }

    private User reload(User user) {
        return userRepository.findById(user.getId()).orElseThrow();
    }

    // =====================================================================================================
    // Fixtures
    // =====================================================================================================

    /**
     * A super administrator in the company of the default super administrator role; deleted after the test.
     */
    private String superAdminToken() {
        Role role = roleService.findByCodeAndRoleType(RoleCode.ADMIN, RoleType.ROLE_SUPER_ADMIN).stream()
                .findFirst().orElseThrow(() -> new IllegalStateException("super admin role is missing"));
        String unique = UUID.randomUUID().toString();
        User user = new User();
        user.setFirstName("P-11");
        user.setLastName("ROOT");
        user.setEmail("p11-root-" + unique + "@example.com");
        user.setUsername(unique);
        user.setPassword(passwordEncoder.encode(TestOrganizations.PASSWORD));
        user.setRole(role);
        user.setCompany(role.getCompanySettings().getCompany());
        user.setEnabled(true);
        User saved = userRepository.saveAndFlush(user);
        createdUsers.add(saved.getId());
        Response response = api.call(post("/auth/signin").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + saved.getEmail() + "\",\"password\":\"" + TestOrganizations.PASSWORD
                        + "\",\"type\":\"SUPER_ADMIN\"}"), null);
        assertEquals(200, response.status(), "super admin login: " + response);
        return json(response).get("accessToken").asText();
    }

    private Team newTeam(O1 o1) {
        Team team = new Team();
        team.setName("P-11 team " + UUID.randomUUID());
        team.setCompany(o1.company());
        team.setUsers(new ArrayList<>(List.of(o1.technician())));
        return teamRepository.saveAndFlush(team);
    }

    private CustomField newCustomField(O1 o1) {
        CustomField field = new CustomField();
        field.setLabel("P-11 field " + UUID.randomUUID());
        field.setFieldType(CustomFieldType.SHORT_TEXT);
        field.setEntityType(CustomFieldEntityType.ASSET);
        field.setCompanySettings(o1.company().getCompanySettings());
        return customFieldRepository.saveAndFlush(field);
    }

    /**
     * Inserted with SQL: the currencies seeded by the changelog leave the JPA sequence behind their ids.
     */
    private Currency newCurrency() {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        Long id = jdbcTemplate.queryForObject("INSERT INTO currency (id, name, code) "
                + "SELECT max(id) + 1, ?, ? FROM currency RETURNING id", Long.class, "P-11 currency " + unique,
                "P11" + unique);
        createdCurrencies.add(id);
        return currencyRepository.findById(id).orElseThrow();
    }

    // =====================================================================================================
    // DELETE /auth and DELETE /auth/{username}
    // =====================================================================================================

    @Nested
    class OwnAccount {

        @Test
        void nonOwnerDeletesOwnAccount_200_body() {
            O1 o1 = newO1();

            Response response = api.call(delete("/auth"), api.login(o1.technician()));

            assertEquals(200, response.status(), response.toString());
            assertEquals(ACCOUNT_DELETED_BODY, response.body());
        }
    }

    @Nested
    class SuperAdminDeletion {

        @Test
        void superAdminDeletesUserWithoutRecords_200_bodyIsUsername() {
            O1 o1 = newO1();
            String token = superAdminToken();

            Response response = api.call(delete("/auth/" + o1.technician().getUsername()), token);

            assertEquals(200, response.status(), response.toString());
            assertEquals(o1.technician().getUsername(), response.body());
        }

        @Test
        void clientAdmin_403_userKept() {
            O1 o1 = newO1();

            Response response = api.call(delete("/auth/" + o1.technician().getUsername()), api.login(o1.admin()));

            assertEquals(403, response.status(), response.toString());
            assertTrue(userRepository.existsById(o1.technician().getId()));
        }
    }

    // =====================================================================================================
    // PATCH /users/{id}/disable, /users/soft-delete/{id}, /users/{id}/enable
    // =====================================================================================================

    @Nested
    class Disable {

        @Test
        void adminDisablesTechnician_200_disabled() {
            O1 o1 = newO1();

            Response response = api.call(patch("/users/" + o1.technician().getId() + "/disable"),
                    api.login(o1.admin()));

            assertEquals(200, response.status(), response.toString());
            assertFalse(reload(o1.technician()).isEnabled());
        }

        @Test
        void technicianWithoutEditOther_406_noPermission() {
            O1 o1 = newO1();

            Response response = api.call(patch("/users/" + o1.admin().getId() + "/disable"),
                    api.login(o1.technician()));

            assertRejected(response, 406, NO_PERMISSION);
            assertTrue(reload(o1.admin()).isEnabled());
        }

        @Test
        void adminDisablesOwner_403_ownerOnly() {
            O1 o1 = newO1();

            Response response = api.call(patch("/users/" + o1.owner().getId() + "/disable"), api.login(o1.admin()));

            assertRejected(response, 403, OWNER_ONLY);
            assertTrue(reload(o1.owner()).isEnabled());
        }

        @Test
        void userOfOtherOrganization_404() {
            CommonData data = newData();

            Response response = api.call(patch("/users/" + data.o1().technician().getId() + "/disable"),
                    api.login(data.o2().admin()));

            assertRejected(response, 404, "User or role not found");
            assertTrue(reload(data.o1().technician()).isEnabled());
        }
    }

    @Nested
    class SoftDelete {

        @Test
        void adminSoftDeletesTechnician_200_disabled_emailSuffixed() {
            O1 o1 = newO1();

            Response response = api.call(patch("/users/soft-delete/" + o1.technician().getId()),
                    api.login(o1.admin()));

            assertEquals(200, response.status(), response.toString());
            User technician = reload(o1.technician());
            assertFalse(technician.isEnabled());
            assertEquals(o1.technician().getEmail() + "_" + o1.technician().getId(), technician.getEmail());
        }

        @Test
        void technicianSoftDeletesSelf_200() {
            O1 o1 = newO1();

            Response response = api.call(patch("/users/soft-delete/" + o1.technician().getId()),
                    api.login(o1.technician()));

            assertEquals(200, response.status(), response.toString());
            assertFalse(reload(o1.technician()).isEnabled());
        }

        @Test
        void technicianWithoutSettings_406_noPermission() {
            O1 o1 = newO1();

            Response response = api.call(patch("/users/soft-delete/" + o1.admin().getId()),
                    api.login(o1.technician()));

            assertRejected(response, 406, NO_PERMISSION);
            assertTrue(reload(o1.admin()).isEnabled());
        }

        @Test
        void adminSoftDeletesOwner_403_ownerOnly() {
            O1 o1 = newO1();

            Response response = api.call(patch("/users/soft-delete/" + o1.owner().getId()), api.login(o1.admin()));

            assertRejected(response, 403, OWNER_ONLY);
            assertEquals(o1.owner().getEmail(), reload(o1.owner()).getEmail());
        }

        @Test
        void userOfOtherOrganization_404() {
            CommonData data = newData();

            Response response = api.call(patch("/users/soft-delete/" + data.o1().technician().getId()),
                    api.login(data.o2().admin()));

            assertRejected(response, 404, "User not found");
        }
    }

    @Nested
    class Enable {

        @Test
        void adminEnablesDisabledTechnician_200_enabled() {
            O1 o1 = newO1();
            String token = api.login(o1.admin());
            api.call(patch("/users/" + o1.technician().getId() + "/disable"), token);

            Response response = api.call(patch("/users/" + o1.technician().getId() + "/enable"), token);

            assertEquals(200, response.status(), response.toString());
            assertTrue(reload(o1.technician()).isEnabled());
        }

        @Test
        void technicianWithoutEditOther_406_noPermission() {
            O1 o1 = newO1();

            Response response = api.call(patch("/users/" + o1.admin().getId() + "/enable"),
                    api.login(o1.technician()));

            assertRejected(response, 406, NO_PERMISSION);
        }

        @Test
        void adminEnablesOwner_403_ownerOnly() {
            O1 o1 = newO1();

            Response response = api.call(patch("/users/" + o1.owner().getId() + "/enable"), api.login(o1.admin()));

            assertRejected(response, 403, OWNER_ONLY);
        }
    }

    // =====================================================================================================
    // DELETE /teams/{id}, /custom-fields/{id}, /currencies/{id}
    // =====================================================================================================

    @Nested
    class TeamDeletion {

        @Test
        void adminDeletesTeam_200_teamGone_memberKept() {
            O1 o1 = newO1();
            Team team = newTeam(o1);

            Response response = api.call(delete("/teams/" + team.getId()), api.login(o1.admin()));

            assertEquals(200, response.status(), response.toString());
            assertEquals(DELETED_BODY, response.body());
            assertFalse(teamRepository.existsById(team.getId()));
            assertTrue(userRepository.existsById(o1.technician().getId()));
        }

        @Test
        void technicianWithoutDeleteOther_403_forbidden() {
            O1 o1 = newO1();
            Team team = newTeam(o1);

            Response response = api.call(delete("/teams/" + team.getId()), api.login(o1.technician()));

            assertRejected(response, 403, "Forbidden");
            assertTrue(teamRepository.existsById(team.getId()));
        }

        @Test
        void unknownTeam_404() {
            O1 o1 = newO1();

            Response response = api.call(delete("/teams/" + Long.MAX_VALUE), api.login(o1.admin()));

            assertRejected(response, 404, "Team not found");
        }

        @Test
        void adminOfOtherOrganization_403_teamKept() {
            CommonData data = newData();
            Team team = newTeam(data.o1());

            Response response = api.call(delete("/teams/" + team.getId()), api.login(data.o2().admin()));

            assertEquals(403, response.status(), response.toString());
            assertTrue(teamRepository.existsById(team.getId()));
        }
    }

    @Nested
    class CustomFieldDeletion {

        @Test
        void adminDeletesCustomField_200_fieldGone() {
            O1 o1 = newO1();
            CustomField field = newCustomField(o1);

            Response response = api.call(delete("/custom-fields/" + field.getId()), api.login(o1.admin()));

            assertEquals(200, response.status(), response.toString());
            assertEquals(DELETED_BODY, response.body());
            assertFalse(customFieldRepository.existsById(field.getId()));
        }

        @Test
        void technicianWithoutSettings_403_accessDenied() {
            O1 o1 = newO1();
            CustomField field = newCustomField(o1);

            Response response = api.call(delete("/custom-fields/" + field.getId()), api.login(o1.technician()));

            assertRejected(response, 403, "Access Denied");
            assertTrue(customFieldRepository.existsById(field.getId()));
        }

        @Test
        void unknownField_404() {
            O1 o1 = newO1();

            Response response = api.call(delete("/custom-fields/" + Long.MAX_VALUE), api.login(o1.admin()));

            assertRejected(response, 404, "Custom field not found");
        }

        @Test
        void adminOfOtherOrganization_403_accessDenied() {
            CommonData data = newData();
            CustomField field = newCustomField(data.o1());

            Response response = api.call(delete("/custom-fields/" + field.getId()), api.login(data.o2().admin()));

            assertRejected(response, 403, "Access denied");
            assertTrue(customFieldRepository.existsById(field.getId()));
        }
    }

    @Nested
    class CurrencyDeletion {

        @Test
        void superAdminDeletesUnusedCurrency_200_currencyGone() {
            Currency currency = newCurrency();

            Response response = api.call(delete("/currencies/" + currency.getId()), superAdminToken());

            assertEquals(200, response.status(), response.toString());
            assertEquals(DELETED_BODY, response.body());
            assertFalse(currencyRepository.existsById(currency.getId()));
        }

        @Test
        void clientAdmin_403_currencyKept() {
            O1 o1 = newO1();
            Currency currency = newCurrency();

            Response response = api.call(delete("/currencies/" + currency.getId()), api.login(o1.admin()));

            assertEquals(403, response.status(), response.toString());
            assertTrue(currencyRepository.existsById(currency.getId()));
        }

        @Test
        void unknownCurrency_404() {
            Response response = api.call(delete("/currencies/" + Long.MAX_VALUE), superAdminToken());

            assertRejected(response, 404, "Currency not found");
        }
    }
}
