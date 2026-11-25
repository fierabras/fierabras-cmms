package com.grash.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.grash.model.abstracts.CompanyAudit;
import com.grash.model.enums.PartTransactionOrigin;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ManyToOne;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/**
 * P-16b (PPB-04, PPB-30): one movement of the ledger of a part. The quantity is signed: an entry is positive, an exit
 * negative, so the stock of the part is the sum of its movements. Only {@code PartLedgerService} creates movements;
 * no operation of the application changes or deletes one ({@code @Immutable}).
 */
@Entity
@Immutable
@Data
@NoArgsConstructor
@Schema(description = "Movement of the ledger of a part")
public class PartTransaction extends CompanyAudit {
    @NotNull
    @Schema(description = "Signed quantity: positive for an entry, negative for an exit",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private double quantity;

    @ManyToOne(fetch = FetchType.LAZY)
    @NotNull
    private Part part;

    @ManyToOne(fetch = FetchType.LAZY)
    @Nullable
    private WorkOrder workOrder;

    @ManyToOne(fetch = FetchType.LAZY)
    @Nullable
    private PurchaseOrder purchaseOrder;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PartTransactionOrigin origin;

    private String description;

    private boolean isDemo;

    public PartTransaction(Part part, double quantity, PartTransactionOrigin origin, WorkOrder workOrder,
                           PurchaseOrder purchaseOrder, String description) {
        this.part = part;
        this.quantity = quantity;
        this.origin = origin;
        this.workOrder = workOrder;
        this.purchaseOrder = purchaseOrder;
        this.description = description;
    }

    /**
     * The signed cost of the movement, at the current cost of the part.
     */
    public double getCost() {
        return part.getCost() * quantity;
    }

    /**
     * What the movement consumed: positive for a consumption, negative for a reversal.
     */
    @JsonIgnore
    public double getConsumedQuantity() {
        return -quantity;
    }

    @JsonIgnore
    public double getConsumedCost() {
        return part.getCost() * getConsumedQuantity();
    }

    @JsonIgnore
    public boolean isConsumption() {
        return origin == PartTransactionOrigin.CONSUMPTION;
    }
}
