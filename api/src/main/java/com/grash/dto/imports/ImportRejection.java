package com.grash.dto.imports;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * P-12c (PPB-20 b): a row that was not imported, and why.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A row that was not imported, with the reason")
public class ImportRejection {
    @Schema(description = "Position of the row in the imported list, starting at 1")
    private int row;

    @Schema(description = "Identifier given in the row")
    private Long id;

    @Schema(description = "Stable code of the reason, as in the 409 answers")
    private String code;

    @Schema(description = "Reason, in English")
    private String message;
}
