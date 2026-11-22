package com.grash.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grash.integration.support.FaultInjector;
import com.grash.integration.support.FaultInjector.Operation;
import com.grash.integration.support.TestApi;
import com.grash.integration.support.TestApi.Response;
import com.grash.integration.support.TestOrganizations;
import com.grash.integration.support.TestOrganizations.CommonData;
import com.grash.integration.support.TestOrganizations.O1;
import com.grash.integration.support.TestRecords;
import com.grash.job.DeleteDemoCompaniesJob;
import com.grash.model.Asset;
import com.grash.model.Currency;
import com.grash.model.CustomField;
import com.grash.model.Customer;
import com.grash.model.Team;
import com.grash.model.User;
import com.grash.model.WorkOrder;
import com.grash.model.enums.CustomFieldEntityType;
import com.grash.model.enums.CustomFieldType;
import com.grash.model.enums.Status;
import com.grash.repository.CurrencyRepository;
import com.grash.repository.CustomFieldRepository;
import com.grash.repository.CustomerRepository;
import com.grash.repository.TeamRepository;
import com.grash.repository.UserRepository;
import com.grash.repository.WorkOrderRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

/**
 * P-11: the organization and its configuration are never deleted by the application, and teams, custom fields
 * and currencies in use are not deleted (PPB-02, PPB-10, PPB-21, PPB-22, PPB-28).
 */
@AutoConfigureMockMvc
class OrganizationConfigurationIntegrationTest extends AbstractIntegrationTest {

    /**
     * Paths of the organization and of the Configuration class (PPB-10).
     */
    private static final List<String> ORGANIZATION_PATHS = List.of("/companies", "/company-settings",
            "/general-preferences", "/ui-configurations", "/work-order-configurations",
            "/work-order-request-configurations", "/field-configurations", "/user-settings", "/shift-configurations");
    private static final List<String> CONFIGURATION_TABLES = List.of("company", "company_settings",
            "general_preferences", "ui_configuration", "work_order_configuration", "work_order_request_configuration",
            "field_configuration", "user_settings", "shift_configuration");
    /**
     * P-12c (H1-14): the DELETE operations of the specification, reviewed on 21/11/2026, sorted.
     */
    private static final List<String> REVIEWED_DELETE_PATHS = List.of(
            "/additional-costs/{id}", "/asset-categories/{id}", "/asset-downtimes/{id}", "/assets/{id}", "/auth",
            "/auth/{username}", "/checklists/{id}", "/comments/{id}", "/cost-categories/{id}", "/currencies/{id}",
            "/custom-fields/{id}", "/customers/{id}", "/deprecations/{id}", "/files/{id}", "/floor-plans/{id}",
            "/labors/{id}", "/locations/{id}", "/meter-categories/{id}", "/meters/{id}", "/multi-parts/{id}",
            "/part-categories/{id}", "/part-quantities/{id}", "/parts/{id}", "/preventive-maintenances/{id}",
            "/purchase-order-categories/{id}", "/purchase-orders/{id}", "/readings/{id}", "/relations/{id}",
            "/request-portals/{id}", "/requests/{id}", "/roles/{id}", "/schedules/{id}", "/subscription-plans/{id}",
            "/tasks/{id}", "/teams/{id}", "/time-categories/{id}", "/vendors/{id}", "/webhook-endpoints/{id}",
            "/work-order-categories/{id}", "/work-order-meter-triggers/{id}",
            "/work-orders/files/{id}/{fileId}/remove", "/work-orders/{id}", "/workflows/{id}");
    private static final String DELETED_BODY = "{\"success\":true,\"message\":\"Deleted successfully\"}";

    private final TestOrganizations organizations;
    private final TestRecords records;
    private final TestApi api;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final TeamRepository teamRepository;
    private final WorkOrderRepository workOrderRepository;
    private final CustomFieldRepository customFieldRepository;
    private final CurrencyRepository currencyRepository;
    private final CustomerRepository customerRepository;
    private final UserRepository userRepository;
    private final DeleteDemoCompaniesJob deleteDemoCompaniesJob;
    private final FaultInjector faults;
    private final List<CommonData> created = new ArrayList<>();
    private final List<User> createdUsers = new ArrayList<>();
    private final List<Long> createdCurrencies = new ArrayList<>();

    @Autowired
    OrganizationConfigurationIntegrationTest(TestOrganizations organizations, TestRecords records, MockMvc mockMvc,
                                             ObjectMapper objectMapper, JdbcTemplate jdbcTemplate,
                                             TeamRepository teamRepository, WorkOrderRepository workOrderRepository,
                                             CustomFieldRepository customFieldRepository,
                                             CurrencyRepository currencyRepository,
                                             CustomerRepository customerRepository, UserRepository userRepository,
                                             DeleteDemoCompaniesJob deleteDemoCompaniesJob) {
        this.organizations = organizations;
        this.records = records;
        this.api = new TestApi(mockMvc, objectMapper);
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.teamRepository = teamRepository;
        this.workOrderRepository = workOrderRepository;
        this.customFieldRepository = customFieldRepository;
        this.currencyRepository = currencyRepository;
        this.customerRepository = customerRepository;
        this.userRepository = userRepository;
        this.deleteDemoCompaniesJob = deleteDemoCompaniesJob;
        this.faults = new FaultInjector(jdbcTemplate);
    }

    @AfterEach
    void cleanUp() {
        faults.removeAll();
        createdUsers.forEach(organizations::deleteUser);
        createdUsers.clear();
        created.forEach(organizations::delete);
        created.clear();
        createdCurrencies.forEach(id -> {
            jdbcTemplate.update("DELETE FROM audit_log WHERE company_id IS NULL AND entity_type = 'CURRENCY' "
                    + "AND entity_id = ?", id);
            jdbcTemplate.update("DELETE FROM currency WHERE id = ?", id);
        });
        createdCurrencies.clear();
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

    /**
     * Checks the 409 and returns its blocking types.
     */
    private List<String> assertInUse(Response response, String code) {
        JsonNode body = json(response);
        assertAll(
                () -> assertEquals(409, response.status(), response.toString()),
                () -> assertFalse(body.get("success").asBoolean(), response.toString()),
                () -> assertEquals(code, body.get("code").asText(), response.toString()),
                () -> assertFalse(response.body().toLowerCase().contains("constraint"), response.toString()));
        List<String> types = new ArrayList<>();
        if (body.has("blockingTypes")) body.get("blockingTypes").forEach(type -> types.add(type.asText()));
        return types;
    }

    private long count(String sql, Object... arguments) {
        Long result = jdbcTemplate.queryForObject(sql, Long.class, arguments);
        return result == null ? 0 : result;
    }

    private List<String> audit(Long companyId) {
        return jdbcTemplate.queryForList("SELECT action || ' ' || entity_type || ' ' || entity_id FROM audit_log "
                + "WHERE company_id = ? ORDER BY id", String.class, companyId);
    }

    private String superAdminToken() {
        User superAdmin = organizations.superAdmin();
        createdUsers.add(superAdmin);
        return api.login(superAdmin, "SUPER_ADMIN");
    }

    private Team team(O1 o1, User... members) {
        Team team = new Team();
        team.setName(TestRecords.unique("P-11 team"));
        team.setCompany(o1.company());
        team.setUsers(new ArrayList<>(List.of(members)));
        return teamRepository.saveAndFlush(team);
    }

    private CustomField customField(O1 o1) {
        CustomField field = new CustomField();
        field.setLabel(TestRecords.unique("P-11 field"));
        field.setFieldType(CustomFieldType.SHORT_TEXT);
        field.setEntityType(CustomFieldEntityType.ASSET);
        field.setCompanySettings(o1.company().getCompanySettings());
        return customFieldRepository.saveAndFlush(field);
    }

    /**
     * Inserted with SQL: the currencies seeded by the changelog leave the JPA sequence behind their ids.
     */
    private Currency currency() {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        Long id = jdbcTemplate.queryForObject("INSERT INTO currency (id, name, code) "
                + "SELECT max(id) + 1, ?, ? FROM currency RETURNING id", Long.class, "P-11 currency " + unique,
                "P11" + unique);
        createdCurrencies.add(id);
        return currencyRepository.findById(id).orElseThrow();
    }

    // =====================================================================================================
    // E-6: teams, custom fields and currencies in use are not deleted (PPB-02, PPB-23)
    // =====================================================================================================

    @Nested
    class TeamDeletion {

        @Test
        void ca106_teamCitedByWorkOrder_409TeamInUse_workOrderKeepsTeam_noRecord() {
            O1 o1 = newO1();
            Team team = team(o1);
            WorkOrder workOrder = records.workOrder(o1.company(), null, Status.OPEN, false);
            workOrder.setTeam(team);
            workOrderRepository.saveAndFlush(workOrder);

            Response response = api.call(delete("/teams/" + team.getId()), api.login(o1.admin()));

            assertTrue(assertInUse(response, "TEAM_IN_USE").contains("WORK_ORDER"), response.toString());
            assertEquals(team.getId(), jdbcTemplate.queryForObject("SELECT team_id FROM work_order WHERE id = ?",
                    Long.class, workOrder.getId()));
            assertTrue(audit(o1.company().getId()).isEmpty());
        }

        @Test
        void ca107_teamWithTwoMembersAndNoReferences_200_teamAndMembershipsGone_usersKept_deleteRecorded() {
            O1 o1 = newO1();
            Team team = team(o1, o1.admin(), o1.technician());

            Response response = api.call(delete("/teams/" + team.getId()), api.login(o1.admin()));

            assertEquals(200, response.status(), response.toString());
            assertEquals(DELETED_BODY, response.body());
            assertFalse(teamRepository.existsById(team.getId()));
            assertEquals(0, count("SELECT count(*) FROM t_team_user_associations WHERE id_team = ?", team.getId()));
            assertTrue(userRepository.existsById(o1.admin().getId()));
            assertTrue(userRepository.existsById(o1.technician().getId()));
            assertEquals(List.of("DELETE TEAM " + team.getId()), audit(o1.company().getId()));
            String cascade = jdbcTemplate.queryForObject("SELECT cascade_rows::text FROM audit_log WHERE "
                    + "company_id = ?", String.class, o1.company().getId());
            assertTrue(cascade.contains("\"t_team_user_associations\": 2"), cascade);
        }

        @Test
        void workOrderAddedBetweenCheckAndDeletion_409TeamInUse_never500() {
            O1 o1 = newO1();
            Team team = team(o1);
            WorkOrder workOrder = records.workOrder(o1.company(), null, Status.OPEN, false);

            try (FaultInjector.Fault fault = faults.runBefore("team", Operation.DELETE, "UPDATE work_order SET "
                    + "team_id = " + team.getId() + " WHERE id = " + workOrder.getId())) {
                Response response = api.call(delete("/teams/" + team.getId()), api.login(o1.admin()));

                assertEquals(List.of("WORK_ORDER"), assertInUse(response, "TEAM_IN_USE"));
                assertEquals(1, fault.hits());
            }
            assertTrue(teamRepository.existsById(team.getId()));
        }
    }

    @Nested
    class CustomFieldDeletion {

        @Test
        void ca104_customFieldWithValue_409CustomFieldInUse_valueKept_noRecord() {
            O1 o1 = newO1();
            CustomField field = customField(o1);
            Asset asset = records.asset(o1.company());
            jdbcTemplate.update("INSERT INTO custom_field_value (custom_field_id, asset_id, value) "
                    + "VALUES (?, ?, 'P-11')", field.getId(), asset.getId());

            Response response = api.call(delete("/custom-fields/" + field.getId()), api.login(o1.admin()));

            assertEquals(List.of("CUSTOM_FIELD_VALUE"), assertInUse(response, "CUSTOM_FIELD_IN_USE"));
            assertEquals(1, count("SELECT count(*) FROM custom_field_value WHERE custom_field_id = ?",
                    field.getId()));
            assertTrue(customFieldRepository.existsById(field.getId()));
            assertTrue(audit(o1.company().getId()).isEmpty());
        }

        @Test
        void unusedCustomField_200_deleteRecorded() {
            O1 o1 = newO1();
            CustomField field = customField(o1);

            Response response = api.call(delete("/custom-fields/" + field.getId()), api.login(o1.admin()));

            assertEquals(200, response.status(), response.toString());
            assertEquals(List.of("DELETE CUSTOM_FIELD " + field.getId()), audit(o1.company().getId()));
        }
    }

    @Nested
    class CurrencyDeletion {

        @Test
        void ca105_currencyOfPreferencesOfO1AndCustomerOfO2_409CurrencyInUse_bothKeepIt() {
            CommonData data = newData();
            Currency currency = currency();
            Long preferences = data.o1().company().getCompanySettings().getGeneralPreferences().getId();
            jdbcTemplate.update("UPDATE general_preferences SET currency_id = ? WHERE id = ?", currency.getId(),
                    preferences);
            Customer customer = new Customer();
            customer.setName(TestRecords.unique("P-11 customer"));
            customer.setCompany(data.o2().company());
            customer.setBillingCurrency(currency);
            Customer savedCustomer = customerRepository.saveAndFlush(customer);
            String token = superAdminToken();
            long records = count("SELECT count(*) FROM audit_log");

            Response response = api.call(delete("/currencies/" + currency.getId()), token);

            assertEquals(List.of("CUSTOMER", "GENERAL_PREFERENCES"), assertInUse(response, "CURRENCY_IN_USE"));
            assertEquals(currency.getId(), jdbcTemplate.queryForObject("SELECT currency_id FROM general_preferences "
                    + "WHERE id = ?", Long.class, preferences));
            assertEquals(currency.getId(), jdbcTemplate.queryForObject("SELECT billing_currency_id FROM customer "
                    + "WHERE id = ?", Long.class, savedCustomer.getId()));
            assertTrue(currencyRepository.existsById(currency.getId()));
            assertEquals(records, count("SELECT count(*) FROM audit_log"), "a rejection leaves no audit record");
        }

        @Test
        void unusedCurrency_200_deleteRecordedWithoutOrganization() {
            Currency currency = currency();

            Response response = api.call(delete("/currencies/" + currency.getId()), superAdminToken());

            assertEquals(200, response.status(), response.toString());
            assertFalse(currencyRepository.existsById(currency.getId()));
            assertEquals(1, count("SELECT count(*) FROM audit_log WHERE company_id IS NULL AND action = 'DELETE' "
                    + "AND entity_type = 'CURRENCY' AND entity_id = ? AND entity_name = ?", currency.getId(),
                    currency.getName()));
        }
    }

    // =====================================================================================================
    // E-7: no operation deletes the organization or its configuration (guard tests; PPB-10, PPB-28)
    // =====================================================================================================

    @Nested
    class NoOrganizationDeletion {

        /**
         * Paths of the generated specification that accept DELETE.
         */
        private List<String> deletePaths() {
            Response response = api.call(get("/v3/api-docs"), null);
            assertEquals(200, response.status(), response.toString());
            List<String> paths = new ArrayList<>();
            json(response).get("paths").fields().forEachRemaining(entry -> {
                if (entry.getValue().has("delete")) paths.add(entry.getKey());
            });
            return paths;
        }

        @Test
        void ca030_ca126_noOperationDeletesTheOrganizationOrItsConfiguration() {
            List<String> paths = deletePaths();

            assertTrue(paths.contains("/auth"), "the specification lists the DELETE operations: " + paths);
            assertEquals(List.of(), paths.stream()
                    .filter(path -> ORGANIZATION_PATHS.stream().anyMatch(path::startsWith)).toList());
        }

        @Test
        void ca076_noOperationPurgesRetiredRecordsOrAnOrganization() {
            assertEquals(List.of(), deletePaths().stream()
                    .filter(path -> path.matches(".*(archived|retired|disabled|purge|compan).*")).toList());
        }

        /**
         * P-12c (H1-14): every DELETE operation of the specification, whatever its path, has been reviewed against
         * PPB-10 and PPB-28 (none deletes an organization, its configuration or retired records in bulk). A new one
         * makes this test fail until it is reviewed and added here.
         */
        @Test
        void ca030_ca076_ca126_everyDeleteOperationIsReviewed() {
            assertEquals(REVIEWED_DELETE_PATHS, deletePaths().stream().sorted().toList());
        }

        /**
         * P-12c (H1-14): whatever the path or the HTTP method of the operation, the organization and its
         * configuration are deleted only by the demo job: their repositories delete only there and in their own
         * services, and nothing calls the delete methods of those services.
         */
        @Test
        void ca030_ca076_ca126_onlyTheDemoJobDeletesAnOrganizationOrItsConfiguration() throws IOException {
            Pattern call = Pattern.compile("\\b(company|companySettings|generalPreferences|uiConfiguration|"
                    + "workOrderConfiguration|workOrderRequestConfiguration|fieldConfiguration|userSettings|"
                    + "shiftConfiguration)(Repository|Service)\\.delete\\w*\\(");
            Pattern sql = Pattern.compile("(?i)delete\\s+from\\s+(" + String.join("|", CONFIGURATION_TABLES)
                    + ")\\b");
            List<String> found = new ArrayList<>();
            try (Stream<Path> files = Files.walk(Path.of("src", "main", "java"))) {
                for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                    for (String line : Files.readAllLines(file)) {
                        Matcher matcher = call.matcher(line);
                        while (matcher.find()) found.add(file.getFileName() + ": " + matcher.group());
                        if (sql.matcher(line).find()) found.add(file.getFileName() + ": " + line.trim());
                    }
                }
            }

            assertEquals(List.of(
                    "CompanyService.java: companyRepository.deleteById(",
                    "CompanySettingsService.java: companySettingsRepository.deleteById(",
                    "DeleteDemoCompaniesJob.java: companyRepository.deleteAllByDemoTrue(",
                    "FieldConfigurationService.java: fieldConfigurationRepository.deleteById(",
                    "GeneralPreferencesService.java: generalPreferencesRepository.deleteById(",
                    "ShiftConfigurationService.java: shiftConfigurationRepository.deleteById(",
                    "UiConfigurationService.java: uiConfigurationRepository.deleteById(",
                    "UserSettingsService.java: userSettingsRepository.deleteById(",
                    "WorkOrderConfigurationService.java: workOrderConfigurationRepository.deleteById(",
                    "WorkOrderRequestConfigurationService.java: workOrderRequestConfigurationRepository.deleteById("),
                    found.stream().sorted().toList());
        }

        @Test
        void ca077_deleteDemoCompaniesJob_withoutCloudVersion_keepsTheDemoCompany() {
            O1 o1 = newO1();
            jdbcTemplate.update("UPDATE company SET demo = true WHERE id = ?", o1.company().getId());

            deleteDemoCompaniesJob.execute(null);

            assertEquals(1, count("SELECT count(*) FROM company WHERE id = ?", o1.company().getId()));
        }

        @Test
        void ca132_editingGeneralPreferences_200_persisted_noOrganizationOrConfigurationRowCreatedOrDeleted() {
            O1 o1 = newO1();
            Long preferences = o1.company().getCompanySettings().getGeneralPreferences().getId();
            String token = api.login(o1.admin());
            Map<String, Long> before = configurationRowCounts();

            Response response = api.call(patch("/general-preferences/" + preferences)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"timeZone\":\"America/Mexico_City\",\"csvSeparator\":\",\"}"),
                    token);

            assertEquals(200, response.status(), response.toString());
            assertEquals("America/Mexico_City", jdbcTemplate.queryForObject("SELECT time_zone FROM "
                    + "general_preferences WHERE id = ?", String.class, preferences));
            assertEquals(before, configurationRowCounts());
        }

        private Map<String, Long> configurationRowCounts() {
            Map<String, Long> counts = new LinkedHashMap<>();
            CONFIGURATION_TABLES.forEach(table -> counts.put(table, count("SELECT count(*) FROM " + table)));
            return counts;
        }
    }
}
