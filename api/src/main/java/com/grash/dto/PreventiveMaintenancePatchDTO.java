package com.grash.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@Schema(description = "DTO for patching preventive maintenance information")
public class PreventiveMaintenancePatchDTO extends WorkOrderBasePatchDTO {
    @Schema(description = "Name")
    private String name;

    /**
     * P-15 (E-2, D-P15-1): {@code true} pauses and {@code false} resumes the preventive maintenance; when present,
     * nothing else of the body is applied.
     */
    @Schema(description = "true pauses the preventive maintenance and false resumes it; when present, the other "
            + "fields are ignored")
    private Boolean disabled;
}
