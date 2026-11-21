package com.grash.service;

import com.grash.exception.ConflictException;
import com.grash.model.enums.EntityType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Dependents that block the deletion of an entity (PPB-02, PPB-26): its {@code <ENTITY>_IN_USE} code and message,
 * and, for each blocking type, how to tell that a dependent exists. The type's table is the one whose foreign key
 * rejects a dependent added between the check and the deletion.
 */
public final class DeletionRule {

    private final String code;
    private final String message;
    private final Map<EntityType, BooleanSupplier> dependents = new LinkedHashMap<>();

    private DeletionRule(String code, String message) {
        this.code = code;
        this.message = message;
    }

    public static DeletionRule inUse(String code, String message) {
        return new DeletionRule(code, message);
    }

    public DeletionRule blockedBy(EntityType type, BooleanSupplier exists) {
        dependents.put(type, exists);
        return this;
    }

    /**
     * Rejects with every type that has a dependent now.
     */
    void rejectIfInUse() {
        List<EntityType> types = dependents.entrySet().stream()
                .filter(dependent -> dependent.getValue().getAsBoolean())
                .map(Map.Entry::getKey)
                .toList();
        if (!types.isEmpty()) throw ConflictException.inUse(code, message, types);
    }

    /**
     * The rejection for a foreign key of {@code referencingTable}: with its type when it is a known dependent,
     * without types otherwise.
     */
    ConflictException conflictFor(String referencingTable) {
        List<EntityType> types = dependents.keySet().stream()
                .filter(type -> type.getTable().equals(referencingTable))
                .toList();
        return ConflictException.inUse(code, message, types);
    }
}
