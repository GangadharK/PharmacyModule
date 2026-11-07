package com.potlatech.pharmacy.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.potlatech.pharmacy.common.exception.BusinessRuleException;

/**
 * The output of one allocation: how much of which batch, at which price.
 *
 * <p>Carries {@code batchId} and the human fields together because the caller
 * needs all of them to write the deduction, the ledger row and the response — and
 * resolving the batch to its number and expiry at write time would mean trusting
 * that the row had not changed since the read.
 */
public record FefoAllocation(
        UUID batchId,
        String batchNumber,
        LocalDate expiryDate,
        int quantity,
        BigDecimal unitPrice) {

    /** The ledger amount for this slice. Always a product, never a sum of money. */
    public BigDecimal lineTotal() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }
}
