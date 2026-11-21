package com.grash.exception;

import com.grash.model.enums.EntityType;
import org.springframework.http.HttpStatus;

import java.io.Serial;
import java.util.Collection;
import java.util.List;

/**
 * Rejection by a persistence rule (PPB-26): always 409, with a stable code and a message in English. The
 * {@code <ENTITY>_IN_USE} rejections also carry the types that block the operation, without counts or identifiers.
 */
public class ConflictException extends CustomException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * A deletion without a rule rejected by a foreign key: the record is in use, by something not identified yet.
     */
    public static final String RECORD_IN_USE = "RECORD_IN_USE";
    private static final String RECORD_IN_USE_MESSAGE = "This record is used by other records and cannot be deleted";

    /**
     * Names of the blocking types, sorted; {@code null} when there are none.
     */
    private final List<String> blockingTypes;

    public ConflictException(String code, String message) {
        this(code, message, null);
    }

    private ConflictException(String code, String message, List<String> blockingTypes) {
        super(message, HttpStatus.CONFLICT, code);
        this.blockingTypes = blockingTypes;
    }

    /**
     * An {@code <ENTITY>_IN_USE} rejection; without known types, the answer has no list.
     */
    public static ConflictException inUse(String code, String message, Collection<EntityType> types) {
        List<String> names = types.stream().map(Enum::name).distinct().sorted().toList();
        return new ConflictException(code, message, names.isEmpty() ? null : names);
    }

    public static ConflictException recordInUse() {
        return new ConflictException(RECORD_IN_USE, RECORD_IN_USE_MESSAGE);
    }

    public List<String> getBlockingTypes() {
        return blockingTypes;
    }
}
