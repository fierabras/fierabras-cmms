package com.grash.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

import java.util.List;

/**
 * Body of a 409 rejected by a persistence rule (PPB-26): the usual body plus, in the {@code <ENTITY>_IN_USE}
 * rejections, the types that block the operation. Never used for a successful answer.
 */
@Getter
@Schema(description = "Rejection by a persistence rule")
public class ConflictResponse extends SuccessResponse {

    @Schema(description = "Types of the records that block the operation, without counts or identifiers; only in "
            + "the <ENTITY>_IN_USE rejections")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final List<String> blockingTypes;

    public ConflictResponse(String message, String code, List<String> blockingTypes) {
        super(false, message, code);
        this.blockingTypes = blockingTypes;
    }
}
