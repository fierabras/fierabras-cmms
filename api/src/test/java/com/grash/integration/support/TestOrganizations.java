package com.grash.integration.support;

import com.grash.model.Company;
import com.grash.model.Role;
import com.grash.model.Subscription;
import com.grash.model.User;
import com.grash.model.UserSettings;
import com.grash.model.enums.RoleCode;
import com.grash.repository.UserRepository;
import com.grash.service.CompanyService;
import com.grash.service.CurrencyService;
import com.grash.service.RoleService;
import com.grash.service.SubscriptionPlanService;
import com.grash.service.SubscriptionService;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Creates the "common starting data" of {@code PPB_Criterios_Aceptacion}: organization O1 with its owner U0,
 * administrator A and technician T, and organization O2 with its own administrator.
 * <ul>
 *     <li>Each organization is created as {@code UserService.signup} does: {@code BUSINESS} plan, currency
 *     {@code $}, default roles.</li>
 *     <li>U0 owns O1 ({@code ownsCompany = true}) and has the default ADMIN role.</li>
 *     <li>A has the default ADMIN role: view of {@code SETTINGS}, {@code editOther} and {@code deleteOther} on
 *     every entity. A does not own O1.</li>
 *     <li>T has the default TECHNICIAN role: no {@code editOther} and no {@code deleteOther}. Create the records
 *     of a test with another user, so that T is not their creator.</li>
 *     <li>The administrator of O2 owns O2 and has the default ADMIN role.</li>
 * </ul>
 * Every call creates new organizations and users with unique names and emails, so tests do not depend on each
 * other. Everyone signs in with {@link #PASSWORD} (see {@link TestApi#login(User)}).
 */
@TestComponent
public class TestOrganizations {

    public static final String PASSWORD = "P00-Passw0rd!";

    public record O1(Company company, User owner, User admin, User technician) {
    }

    public record O2(Company company, User admin) {
    }

    public record CommonData(O1 o1, O2 o2) {
    }

    private final SubscriptionPlanService subscriptionPlanService;
    private final SubscriptionService subscriptionService;
    private final CurrencyService currencyService;
    private final CompanyService companyService;
    private final RoleService roleService;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate transactionTemplate;
    private final JdbcTemplate jdbcTemplate;

    public TestOrganizations(SubscriptionPlanService subscriptionPlanService, SubscriptionService subscriptionService,
                             CurrencyService currencyService, CompanyService companyService, RoleService roleService,
                             UserRepository userRepository, PasswordEncoder passwordEncoder,
                             TransactionTemplate transactionTemplate, JdbcTemplate jdbcTemplate) {
        this.subscriptionPlanService = subscriptionPlanService;
        this.subscriptionService = subscriptionService;
        this.currencyService = currencyService;
        this.companyService = companyService;
        this.roleService = roleService;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.transactionTemplate = transactionTemplate;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Creates and commits O1 and O2 (inside a {@code @Transactional} test, they join its transaction).
     */
    public CommonData create() {
        SecurityContextHolder.clearContext();
        String run = UUID.randomUUID().toString().substring(0, 8);
        return transactionTemplate.execute(status -> {
            Company o1 = newCompany("P-00 O1 " + run);
            User owner = newUser(o1, RoleCode.ADMIN, "u0", true);
            User admin = newUser(o1, RoleCode.ADMIN, "a", false);
            User technician = newUser(o1, RoleCode.TECHNICIAN, "t", false);
            Company o2 = newCompany("P-00 O2 " + run);
            User o2Admin = newUser(o2, RoleCode.ADMIN, "o2-admin", true);
            return new CommonData(new O1(o1, owner, admin, technician), new O2(o2, o2Admin));
        });
    }

    /**
     * Deletes O1 and O2 with everything that the database removes in cascade from them, and their subscriptions.
     * Optional: tests must not depend on it, since no test may assume that the database is empty.
     */
    public void delete(CommonData data) {
        for (Company company : List.of(data.o1().company(), data.o2().company())) {
            List<Long> subscriptionIds = jdbcTemplate.queryForList(
                    "SELECT subscription_id FROM company WHERE id = ?", Long.class, company.getId());
            jdbcTemplate.update("DELETE FROM company WHERE id = ?", company.getId());
            subscriptionIds.stream().filter(Objects::nonNull)
                    .forEach(id -> jdbcTemplate.update("DELETE FROM subscription WHERE id = ?", id));
        }
    }

    private Company newCompany(String name) {
        Subscription subscription = Subscription.builder()
                .usersCount(300)
                .monthly(false)
                .startsOn(new Date())
                .subscriptionPlan(subscriptionPlanService.findByCode("BUSINESS").orElseThrow())
                .build();
        subscriptionService.create(subscription);
        Company company = new Company(name, 10, subscription);
        company.getCompanySettings().getGeneralPreferences()
                .setCurrency(currencyService.findByCode("$").orElseThrow());
        return companyService.create(company);
    }

    private User newUser(Company company, RoleCode roleCode, String label, boolean ownsCompany) {
        Role role = roleService.findDefaultRoleWithCode(roleCode).orElseThrow(
                () -> new IllegalStateException("default role " + roleCode + " is missing"));
        String unique = UUID.randomUUID().toString();
        User user = new User();
        user.setFirstName("P-00");
        user.setLastName(label.toUpperCase());
        user.setEmail("p00-" + label + "-" + unique + "@example.com");
        user.setUsername(unique);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setRole(role);
        user.setCompany(company);
        user.setOwnsCompany(ownsCompany);
        user.setEnabled(true);
        user.setEnabledInSubscription(true);
        user.setSuperAccountRelations(new ArrayList<>());
        user.setUserSettings(new UserSettings());
        return userRepository.save(user);
    }
}
