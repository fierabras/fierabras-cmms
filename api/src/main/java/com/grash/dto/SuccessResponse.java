package com.grash.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Generic success response")
public class SuccessResponse {
    @Schema(description = "Whether the operation was successful")
    private boolean success;
    
    @Schema(description = "Response message")
    private String message;

    @Schema(description = "Stable identifier of the rule that rejected the request, only on some errors")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String code;

    public SuccessResponse(boolean success, String message) {
        this(success, message, null);
    }
}
