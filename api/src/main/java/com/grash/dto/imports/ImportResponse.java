package com.grash.dto.imports;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@Builder
@AllArgsConstructor
@Schema(description = "Response containing import operation results")
public class ImportResponse {
    @Schema(description = "Number of records created")
    private int created;
    
    @Schema(description = "Number of records updated")
    private int updated;

    @Schema(description = "Rows that were not imported, with the reason")
    @Builder.Default
    private List<ImportRejection> rejected = new ArrayList<>();
}
