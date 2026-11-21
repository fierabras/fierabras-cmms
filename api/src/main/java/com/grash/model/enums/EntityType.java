package com.grash.model.enums;

/**
 * Type of a record, as written in the audit log and in the list of types that block a deletion (PPB-23, PPB-26),
 * with the table that stores it. Stored as text: adding a value needs no migration.
 */
public enum EntityType {
    API_KEY("api_key"),
    ASSET("asset"),
    PART("part"),
    ROLE("role"),
    USER("own_user"),
    WEBHOOK_ENDPOINT("webhook_endpoint"),
    WORK_ORDER("work_order");

    private final String table;

    EntityType(String table) {
        this.table = table;
    }

    public String getTable() {
        return table;
    }
}
