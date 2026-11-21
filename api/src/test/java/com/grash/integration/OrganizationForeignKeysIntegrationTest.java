package com.grash.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P-11 (PPB-21, PPB-22): the foreign keys that point to users, teams, custom fields, currencies and the
 * configuration have their target rule and an index on their child column, checked against the catalog.
 */
class OrganizationForeignKeysIntegrationTest extends AbstractIntegrationTest {

    /**
     * The 34 foreign keys of P-11 that point to users, teams, custom fields, currencies and the configuration:
     * child table, child column, parent table and target rule ({@code a} NO ACTION, {@code c} CASCADE).
     */
    private static final List<String[]> FOREIGN_KEYS = List.of(
            new String[]{"additional_cost", "assigned_to_id", "own_user", "a"},
            new String[]{"asset", "primary_user_id", "own_user", "a"},
            new String[]{"comment", "user_id", "own_user", "a"},
            new String[]{"labor", "assigned_to_id", "own_user", "a"},
            new String[]{"preventive_maintenance", "primary_user_id", "own_user", "a"},
            new String[]{"request", "primary_user_id", "own_user", "a"},
            new String[]{"task_base", "user_id", "own_user", "a"},
            new String[]{"work_order", "completed_by_id", "own_user", "a"},
            new String[]{"work_order", "primary_user_id", "own_user", "a"},
            new String[]{"work_order_aud", "completed_by_id", "own_user", "a"},
            new String[]{"work_order_aud", "primary_user_id", "own_user", "a"},
            new String[]{"work_order_history", "user_id", "own_user", "a"},
            new String[]{"work_order_meter_trigger", "primary_user_id", "own_user", "a"},
            new String[]{"workflow_action", "user_id", "own_user", "a"},
            new String[]{"workflow_condition", "user_id", "own_user", "a"},
            new String[]{"preventive_maintenance", "team_id", "team", "a"},
            new String[]{"request", "team_id", "team", "a"},
            new String[]{"work_order", "team_id", "team", "a"},
            new String[]{"work_order_aud", "team_id", "team", "a"},
            new String[]{"work_order_meter_trigger", "team_id", "team", "a"},
            new String[]{"workflow_action", "team_id", "team", "a"},
            new String[]{"workflow_condition", "team_id", "team", "a"},
            new String[]{"custom_field_value", "custom_field_id", "custom_field", "a"},
            new String[]{"customer", "billing_currency_id", "currency", "a"},
            new String[]{"general_preferences", "currency_id", "currency", "a"},
            new String[]{"company", "company_settings_id", "company_settings", "a"},
            new String[]{"company_settings", "general_preferences_id", "general_preferences", "a"},
            new String[]{"company_settings", "ui_configuration_id", "ui_configuration", "a"},
            new String[]{"company_settings", "work_order_configuration_id", "work_order_configuration", "a"},
            new String[]{"company_settings", "work_order_request_configuration_id",
                    "work_order_request_configuration", "a"},
            new String[]{"own_user", "shift_configuration_id", "shift_configuration", "a"},
            new String[]{"own_user", "user_settings_id", "user_settings", "a"},
            new String[]{"field_configuration", "work_order_configuration_id", "work_order_configuration", "c"},
            new String[]{"field_configuration", "work_order_request_configuration_id",
                    "work_order_request_configuration", "c"});

    private final JdbcTemplate jdbcTemplate;

    @Autowired
    OrganizationForeignKeysIntegrationTest(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Test
    void the34ForeignKeys_haveTheirTargetRule_andLeadAValidIndex() {
        List<String> wrong = new ArrayList<>();
        for (String[] fk : FOREIGN_KEYS) {
            List<String> rules = jdbcTemplate.queryForList("SELECT c.confdeltype::text FROM pg_constraint c "
                    + "JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = c.conkey[1] "
                    + "WHERE c.contype = 'f' AND c.conrelid = ?::regclass AND c.confrelid = ?::regclass "
                    + "AND a.attname = ?", String.class, fk[0], fk[2], fk[1]);
            Long indexes = jdbcTemplate.queryForObject("SELECT count(*) FROM pg_index i "
                    + "JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = i.indkey[0] "
                    + "WHERE i.indrelid = ?::regclass AND a.attname = ? AND i.indisvalid "
                    + "AND i.indpred IS NULL", Long.class, fk[0], fk[1]);
            String name = fk[0] + "." + fk[1] + " -> " + fk[2];
            if (!rules.equals(List.of(fk[3]))) wrong.add(name + ": rule " + rules + ", expected " + fk[3]);
            if (indexes == null || indexes < 1) wrong.add(name + ": no index on the child column");
        }
        assertEquals(34, FOREIGN_KEYS.size());
        assertTrue(wrong.isEmpty(), String.join("\n", wrong));
    }
}
