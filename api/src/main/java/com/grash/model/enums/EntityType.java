package com.grash.model.enums;

/**
 * Type of a record, as written in the audit log and in the list of types that block a deletion (PPB-23, PPB-26),
 * with the table that stores it. Stored as text: adding a value needs no migration.
 */
public enum EntityType {
    ADDITIONAL_COST("additional_cost"),
    API_KEY("api_key"),
    ASSET("asset"),
    ASSET_CATEGORY("asset_category"),
    ASSET_DOWNTIME("asset_downtime"),
    COMMENT("comment"),
    COMPANY("company"),
    COST_CATEGORY("cost_category"),
    CURRENCY("currency"),
    CUSTOM_FIELD("custom_field"),
    CUSTOM_FIELD_VALUE("custom_field_value"),
    CUSTOMER("customer"),
    DEPRECATION("deprecation"),
    FILE("file"),
    FLOOR_PLAN("floor_plan"),
    GENERAL_PREFERENCES("general_preferences"),
    LABOR("labor"),
    LOCATION("location"),
    METER("meter"),
    METER_CATEGORY("meter_category"),
    PART("part"),
    PART_QUANTITY("part_quantity"),
    PREVENTIVE_MAINTENANCE("preventive_maintenance"),
    READING("reading"),
    RELATION("relation"),
    REQUEST("request"),
    REQUEST_PORTAL("request_portal"),
    REQUEST_PORTAL_FIELD("request_portal_field"),
    ROLE("role"),
    TASK("task"),
    TASK_BASE("task_base"),
    TEAM("team"),
    TIME_CATEGORY("time_category"),
    USER("own_user"),
    WEBHOOK_ENDPOINT("webhook_endpoint"),
    WORK_ORDER("work_order"),
    WORK_ORDER_CATEGORY("work_order_category"),
    /**
     * The revisions of a work order ({@code work_order_aud}).
     */
    WORK_ORDER_HISTORY("work_order_aud"),
    WORK_ORDER_METER_TRIGGER("work_order_meter_trigger"),
    WORKFLOW_ACTION("workflow_action"),
    WORKFLOW_CONDITION("workflow_condition");

    private final String table;

    EntityType(String table) {
        this.table = table;
    }

    public String getTable() {
        return table;
    }
}
