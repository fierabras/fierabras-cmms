package com.grash;

import com.grash.dto.UserSignupRequest;
import com.grash.model.*;
import com.grash.model.enums.PlanFeatures;
import com.grash.model.enums.RoleCode;
import com.grash.model.enums.RoleType;
import com.grash.repository.GeneralPreferencesRepository;
import com.grash.service.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.ZoneId;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

// AGPLv3 fork contract tests for the idempotent PlanFeatures upsert.
@ExtendWith(MockitoExtension.class)
class ApplicationInitializerTest {

    @InjectMocks
    private ApplicationInitializer applicationInitializer;

    @Mock
    private UserService userService;
    @Mock
    private UserInvitationService userInvitationService;
    @Mock
    private GeneralPreferencesRepository generalPreferencesRepository;
    @Mock
    private RoleService roleService;
    @Mock
    private CompanyService companyService;
    @Mock
    private SubscriptionPlanService subscriptionPlanService;
    @Mock
    private SubscriptionService subscriptionService;

    private SubscriptionPlan plan(String code, Set<PlanFeatures> features) {
        SubscriptionPlan p = new SubscriptionPlan();
        p.setId(code.hashCode() + 0L);
        p.setCode(code);
        p.setName(code);
        p.setFeatures(features != null ? new HashSet<>(features) : new HashSet<>());
        return p;
    }

    @Test
    void applyAgplPlanFeatures_incompletePlans_getAllFeaturesAndSaved() {
        Set<PlanFeatures> partial = new HashSet<>(Arrays.asList(PlanFeatures.FILE, PlanFeatures.METER));
        SubscriptionPlan free = plan("FREE", partial);
        SubscriptionPlan starter = plan("STARTER", partial);
        when(subscriptionPlanService.getAll()).thenReturn(List.of(free, starter));
        when(subscriptionPlanService.create(any())).thenAnswer(inv -> inv.getArgument(0));

        applicationInitializer.applyAgplPlanFeatures();

        Set<PlanFeatures> all = new HashSet<>(Arrays.asList(PlanFeatures.values()));
        assertEquals(all, free.getFeatures());
        assertEquals(all, starter.getFeatures());
        verify(subscriptionPlanService).create(free);
        verify(subscriptionPlanService).create(starter);
    }

    @Test
    void applyAgplPlanFeatures_alreadyComplete_noWrite() {
        Set<PlanFeatures> all = new HashSet<>(Arrays.asList(PlanFeatures.values()));
        SubscriptionPlan business = plan("BUSINESS", all);
        when(subscriptionPlanService.getAll()).thenReturn(List.of(business));

        applicationInitializer.applyAgplPlanFeatures();

        verify(subscriptionPlanService, never()).create(any());
    }

    @Test
    void applyAgplPlanFeatures_nullFeatures_filledAndSaved() {
        SubscriptionPlan p = plan("UPSTREAM_NEW", null);
        when(subscriptionPlanService.getAll()).thenReturn(List.of(p));
        when(subscriptionPlanService.create(any())).thenAnswer(inv -> inv.getArgument(0));

        applicationInitializer.applyAgplPlanFeatures();

        assertEquals(new HashSet<>(Arrays.asList(PlanFeatures.values())), p.getFeatures());
        verify(subscriptionPlanService).create(p);
    }

    @Test
    void applyAgplPlanFeatures_secondRunAfterConvergence_idempotent() {
        Set<PlanFeatures> partial = new HashSet<>(List.of(PlanFeatures.FILE));
        SubscriptionPlan p = plan("FREE", partial);
        when(subscriptionPlanService.getAll()).thenReturn(List.of(p));
        when(subscriptionPlanService.create(any())).thenAnswer(inv -> inv.getArgument(0));

        applicationInitializer.applyAgplPlanFeatures();
        Set<PlanFeatures> expected = new HashSet<>(p.getFeatures());

        // Second pass: features already match -> no save
        clearInvocations(subscriptionPlanService);
        when(subscriptionPlanService.getAll()).thenReturn(List.of(p));

        applicationInitializer.applyAgplPlanFeatures();

        assertEquals(expected, p.getFeatures());
        verify(subscriptionPlanService, never()).create(any());
    }

    // TD-INC06-03: the super admin is configured through SUPER_ADMIN_* and disabled by default.

    private static final String CONFIGURED_EMAIL = "root@example.com";
    private static final String CONFIGURED_PASSWORD = "configured-secret";
    private static final long SUPER_ADMIN_COMPANY_ID = 7L;

    private Role superAdminRole() {
        Company company = new Company();
        company.setId(SUPER_ADMIN_COMPANY_ID);
        CompanySettings companySettings = new CompanySettings();
        companySettings.setCompany(company);
        Role role = Role.builder()
                .id(70L)
                .name("Super admin")
                .code(RoleCode.ADMIN)
                .roleType(RoleType.ROLE_SUPER_ADMIN)
                .companySettings(companySettings)
                .build();
        when(roleService.findByCodeAndRoleType(RoleCode.ADMIN, RoleType.ROLE_SUPER_ADMIN)).thenReturn(List.of(role));
        return role;
    }

    private User existingSuperAdmin(Role role, boolean enabled) {
        User user = new User();
        user.setId(71L);
        user.setEmail("legacy@example.com");
        user.setPassword("$2a$10$existingHash");
        user.setRole(role);
        user.setEnabled(enabled);
        when(userService.findByCompany(SUPER_ADMIN_COMPANY_ID)).thenReturn(List.of(user));
        return user;
    }

    private void configure(boolean enabled, String email, String password) {
        ReflectionTestUtils.setField(applicationInitializer, "superAdminEnabled", enabled);
        ReflectionTestUtils.setField(applicationInitializer, "superAdminEmail", email);
        ReflectionTestUtils.setField(applicationInitializer, "superAdminPassword", password);
    }

    @Test
    @DisplayName("TD-INC06-03 B1: disabled and no super admin -> not created")
    void superAdmin_disabledAndMissing_notCreated() {
        configure(false, CONFIGURED_EMAIL, CONFIGURED_PASSWORD);
        superAdminRole();
        when(userService.findByCompany(SUPER_ADMIN_COMPANY_ID)).thenReturn(List.of());

        applicationInitializer.initializeSuperAdmin();

        verify(userService, never()).signup(any());
        verify(userInvitationService, never()).create(any());
    }

    @Test
    @DisplayName("TD-INC06-03 B2: disabled and an enabled super admin exists -> it is disabled")
    void superAdmin_disabledAndExisting_isDisabled() {
        configure(false, "", "");
        User superAdmin = existingSuperAdmin(superAdminRole(), true);

        applicationInitializer.initializeSuperAdmin();

        assertFalse(superAdmin.isEnabled());
        verify(userService).save(superAdmin);
        verify(userService, never()).signup(any());
    }

    @Test
    @DisplayName("TD-INC06-03 B3: enabled with email and password and no super admin -> created with them")
    void superAdmin_enabledWithCredentials_createdWithConfiguredValues() {
        configure(true, CONFIGURED_EMAIL, CONFIGURED_PASSWORD);
        Role role = superAdminRole();
        when(userService.findByCompany(SUPER_ADMIN_COMPANY_ID)).thenReturn(List.of());

        applicationInitializer.initializeSuperAdmin();

        ArgumentCaptor<UserSignupRequest> signup = ArgumentCaptor.forClass(UserSignupRequest.class);
        verify(userService).signup(signup.capture());
        assertEquals(CONFIGURED_EMAIL, signup.getValue().getEmail());
        assertEquals(CONFIGURED_PASSWORD, signup.getValue().getPassword());
        assertSame(role, signup.getValue().getRole());
        ArgumentCaptor<UserInvitation> invitation = ArgumentCaptor.forClass(UserInvitation.class);
        verify(userInvitationService).create(invitation.capture());
        assertEquals(CONFIGURED_EMAIL, invitation.getValue().getEmail());
    }

    @Test
    @DisplayName("TD-INC06-03 B4: enabled without password -> not created, startup does not fail")
    void superAdmin_enabledWithoutPassword_notCreated() {
        configure(true, CONFIGURED_EMAIL, "");
        superAdminRole();
        when(userService.findByCompany(SUPER_ADMIN_COMPANY_ID)).thenReturn(List.of());

        assertDoesNotThrow(() -> applicationInitializer.initializeSuperAdmin());

        verify(userService, never()).signup(any());
        verify(userInvitationService, never()).create(any());
    }

    @Test
    @DisplayName("TD-INC06-03 B4: enabled without email -> not created, startup does not fail")
    void superAdmin_enabledWithoutEmail_notCreated() {
        configure(true, " ", CONFIGURED_PASSWORD);
        superAdminRole();
        when(userService.findByCompany(SUPER_ADMIN_COMPANY_ID)).thenReturn(List.of());

        assertDoesNotThrow(() -> applicationInitializer.initializeSuperAdmin());

        verify(userService, never()).signup(any());
        verify(userInvitationService, never()).create(any());
    }

    @Test
    @DisplayName("TD-INC06-03 B5: enabled and a disabled super admin exists -> enabled, password and email untouched")
    void superAdmin_enabledAndExisting_isEnabledWithoutTouchingCredentials() {
        configure(true, CONFIGURED_EMAIL, CONFIGURED_PASSWORD);
        User superAdmin = existingSuperAdmin(superAdminRole(), false);

        applicationInitializer.initializeSuperAdmin();

        assertTrue(superAdmin.isEnabled());
        assertEquals("$2a$10$existingHash", superAdmin.getPassword());
        assertEquals("legacy@example.com", superAdmin.getEmail());
        verify(userService).save(superAdmin);
        verify(userService, never()).signup(any());
    }

    @Test
    @DisplayName("TD-INC06-03: the state already matches the configuration -> no write")
    void superAdmin_stateAlreadyMatches_noWrite() {
        configure(false, "", "");
        existingSuperAdmin(superAdminRole(), false);

        applicationInitializer.initializeSuperAdmin();

        verify(userService, never()).save(any());
        verify(userService, never()).signup(any());
    }
}