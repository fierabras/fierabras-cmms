package com.grash.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.grash.model.*;
import com.grash.model.enums.DateFormat;
import com.grash.model.enums.PlanFeatures;
import com.grash.model.enums.RoleCode;
import com.grash.model.enums.RoleType;
import com.grash.repository.*;
import com.grash.service.RoleService;
import com.grash.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * INC-06 contract tests (criteria C1-C5): a signup with a role always requires an invitation for that role and email,
 * whatever the value of {@code security.invitation-via-email}.
 * <p>
 * The tests call the public {@code POST /auth/signup} endpoint. They are not {@code @Transactional}: each request
 * commits, and {@link #cleanUp()} deletes what the test created. The invitation-via-email flag is toggled on the
 * {@link UserService} target and restored after each test; integration classes share the {@code cmms-test-db} lock,
 * so they never run concurrently.
 */
@AutoConfigureMockMvc
class Inc06SignupInvitationIntegrationTest extends AbstractIntegrationTest {

    private static final String NOT_INVITED = "You are not invited to this organization for this role";
    private static final String ALLOWED_ADMIN = "admin@test.com"; // allowed-organization-admins in application-test.yml

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private UserService userService;
    @Autowired
    private RoleService roleService;
    @Autowired
    private RoleRepository roleRepository;
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
    private UserInvitationRepository userInvitationRepository;

    private final List<Long> createdCompanyIds = new ArrayList<>();
    private final List<Long> createdPlanIds = new ArrayList<>();
    private final List<String> usedEmails = new ArrayList<>();
    private Object originalInvitationViaEmail;

    @BeforeEach
    void rememberFlag() {
        originalInvitationViaEmail = ReflectionTestUtils.getField(target(), "enableInvitationViaEmail");
    }

    @AfterEach
    void cleanUp() {
        ReflectionTestUtils.setField(target(), "enableInvitationViaEmail", originalInvitationViaEmail);
        for (String email : usedEmails) {
            jdbcTemplate.update("DELETE FROM user_invitation WHERE email = ?", email);
            // A company created by signup (C5) is owned by the new user; ON DELETE CASCADE removes its users.
            List<Long> ownedCompanies = jdbcTemplate.queryForList(
                    "SELECT company_id FROM own_user WHERE email = ? AND owns_company", Long.class, email);
            createdCompanyIds.addAll(ownedCompanies);
        }
        for (Long companyId : createdCompanyIds) {
            List<Long> subscriptionIds = jdbcTemplate.queryForList(
                    "SELECT subscription_id FROM company WHERE id = ?", Long.class, companyId);
            jdbcTemplate.update("DELETE FROM company WHERE id = ?", companyId);
            subscriptionIds.stream().filter(Objects::nonNull)
                    .forEach(id -> jdbcTemplate.update("DELETE FROM subscription WHERE id = ?", id));
        }
        createdPlanIds.forEach(id -> jdbcTemplate.update("DELETE FROM subscription_plan WHERE id = ?", id));
        for (String email : usedEmails) {
            assertEquals(0, usersWithEmail(email), "cleanup left the user " + email);
        }
        createdCompanyIds.clear();
        createdPlanIds.clear();
        usedEmails.clear();
    }

    @Test
    @DisplayName("INC-06 C1: invitation via email disabled, custom role of another company, no invitation -> 406")
    void customRoleWithoutInvitation_invitationViaEmailDisabled_rejected() throws Exception {
        setInvitationViaEmail(false);
        Role role = createCustomRole(newCompany("C1"));
        String email = newEmail();

        HttpResult result = signup(email, role.getId());

        assertNotInvited(result, email);
    }

    @Test
    @DisplayName("INC-06 C2: invitation via email enabled, custom role of another company, no invitation -> 406")
    void customRoleWithoutInvitation_invitationViaEmailEnabled_rejected() throws Exception {
        setInvitationViaEmail(true);
        Role role = createCustomRole(newCompany("C2"));
        String email = newEmail();

        HttpResult result = signup(email, role.getId());

        assertNotInvited(result, email);
    }

    @Test
    @DisplayName("INC-06 C3: invitation via email disabled, invited for the role -> 2xx in the company of the role")
    void customRoleWithInvitation_invitationViaEmailDisabled_succeeds() throws Exception {
        setInvitationViaEmail(false);
        Company company = newCompany("C3");
        Role role = createCustomRole(company);
        String email = newEmail();
        userInvitationRepository.save(new UserInvitation(email, role));

        HttpResult result = signup(email, role.getId());

        assertTrue(result.is2xx(), "signup with an invitation: " + result);
        Long companyId = jdbcTemplate.queryForObject("SELECT company_id FROM own_user WHERE email = ?", Long.class,
                email);
        assertEquals(company.getId(), companyId, "the user must belong to the company of the role");
    }

    @Test
    @DisplayName("INC-06 C4: global default role without invitation -> 406, not 500")
    void defaultRoleWithoutInvitation_rejected() throws Exception {
        setInvitationViaEmail(false);
        Role role = roleService.findDefaultRoleWithCode(RoleCode.TECHNICIAN).orElseThrow();
        assertNull(role.getCompanySettings(), "precondition: the default role is global");
        String email = newEmail();

        HttpResult result = signup(email, role.getId());

        assertNotInvited(result, email);
    }

    @Test
    @DisplayName("INC-06 C5: signup without a role is still governed by allowed-organization-admins")
    void signupWithoutRole_governedByAllowedOrganizationAdmins() throws Exception {
        setInvitationViaEmail(false);
        String notAllowed = newEmail();

        HttpResult rejected = signup(notAllowed, null);

        assertAll(
                () -> assertEquals(406, rejected.status(), "email outside the allowed list: " + rejected),
                () -> assertTrue(rejected.body().contains("You are not allowed to create an account without being " +
                        "invited"), "message: " + rejected.body()),
                () -> assertEquals(0, usersWithEmail(notAllowed), "no user may be created"));

        assertEquals(0, usersWithEmail(ALLOWED_ADMIN), "precondition: " + ALLOWED_ADMIN + " is not registered");
        usedEmails.add(ALLOWED_ADMIN);
        HttpResult accepted = signup(ALLOWED_ADMIN, null);

        assertAll(
                () -> assertTrue(accepted.is2xx(), "email in the allowed list: " + accepted),
                () -> assertEquals(1, usersWithEmail(ALLOWED_ADMIN), "the organization admin must be created"));
    }

    // =====================================================================================================
    // Helpers
    // =====================================================================================================

    private void assertNotInvited(HttpResult result, String email) {
        assertAll(
                () -> assertEquals(406, result.status(), "signup without an invitation: " + result),
                () -> assertTrue(result.body().contains(NOT_INVITED), "message: " + result.body()),
                () -> assertEquals(0, usersWithEmail(email), "no user may be created"));
    }

    private HttpResult signup(String email, Long roleId) throws Exception {
        SecurityContextHolder.clearContext();
        ObjectNode body = objectMapper.createObjectNode()
                .put("email", email)
                .put("password", "Inc06-Passw0rd!")
                .put("firstName", "Inc06")
                .put("lastName", "User")
                .put("phone", "5550000000")
                .put("companyName", "INC-06 signup")
                .put("employeesCount", 5);
        if (roleId != null) body.putObject("role").put("id", roleId);
        MvcResult result = mockMvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
        SecurityContextHolder.clearContext();
        return new HttpResult(result.getResponse().getStatus(), result.getResponse().getContentAsString());
    }

    private Company newCompany(String label) {
        SecurityContextHolder.clearContext();
        SubscriptionPlan plan = subscriptionPlanRepository.save(SubscriptionPlan.builder()
                .name("INC-06 plan " + label)
                .monthlyCostPerUser(0.0)
                .yearlyCostPerUser(0.0)
                .features(EnumSet.allOf(PlanFeatures.class))
                .build());
        createdPlanIds.add(plan.getId());
        Subscription subscription = subscriptionRepository.save(Subscription.builder()
                .usersCount(50)
                .subscriptionPlan(plan)
                .build());

        Company company = companyRepository.save(new Company("INC-06 " + label, 10, subscription));
        createdCompanyIds.add(company.getId());
        CompanySettings settings = company.getCompanySettings();
        settings.setCompany(company);
        companySettingsRepository.save(settings);
        GeneralPreferences preferences = settings.getGeneralPreferences();
        preferences.setCurrency(currencyRepository.findFirstBy().orElseThrow());
        preferences.setDateFormat(DateFormat.DDMMYY);
        preferences.setTimeZone("UTC");
        generalPreferencesRepository.save(preferences);
        return company;
    }

    private Role createCustomRole(Company company) {
        SecurityContextHolder.clearContext();
        return roleRepository.save(Role.builder()
                .name("INC-06 custom role")
                .roleType(RoleType.ROLE_CLIENT)
                .code(RoleCode.USER_CREATED)
                .paid(true)
                .companySettings(company.getCompanySettings())
                .build());
    }

    private String newEmail() {
        String email = "inc06-" + UUID.randomUUID() + "@example.com";
        usedEmails.add(email);
        return email;
    }

    private long usersWithEmail(String email) {
        Long result = jdbcTemplate.queryForObject("SELECT count(*) FROM own_user WHERE lower(email) = lower(?)",
                Long.class, email);
        return result == null ? 0 : result;
    }

    private void setInvitationViaEmail(boolean enabled) {
        ReflectionTestUtils.setField(target(), "enableInvitationViaEmail", enabled);
    }

    private UserService target() {
        return AopTestUtils.getUltimateTargetObject(userService);
    }

    private record HttpResult(int status, String body) {
        boolean is2xx() {
            return status >= 200 && status < 300;
        }
    }
}
