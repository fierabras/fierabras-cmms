package com.grash.dto;

import com.grash.model.PurchaseOrder;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * P-16b (D-P16b-2): a new purchase order with, optionally, its lines, so that a workflow run on its creation sees
 * them. Without lines it is created as before.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@Schema(description = "DTO for creating a purchase order, optionally with its lines")
public class PurchaseOrderPostDTO extends PurchaseOrder {

    @Schema(description = "Lines of the purchase order (optional)")
    private List<@Valid PartQuantityCompletePatchDTO> partQuantities;
}
