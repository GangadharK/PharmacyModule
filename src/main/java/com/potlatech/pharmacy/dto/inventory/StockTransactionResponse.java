package com.potlatech.pharmacy.dto.inventory;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.potlatech.pharmacy.entity.StockTransaction;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One ledger row as read by a client.
 *
 * <p>Contains no cost, because a ledger row has no cost of its own — the cost
 * belongs to the batch. A cashier reading the history of a batch's movements gets
 * the same rows as a pharmacist, which is correct: the movement trail is not
 * commercially sensitive.
 *
 * <p><strong>{@code actorName} is null exactly when {@code performedBy} is null</strong>
 * — that is, for a sweep-initiated expiry write-off. Showing the name inline rather
 * than only the id means the history table can render "System" for an automated
 * action without the frontend inventing that word.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StockTransactionResponse(
        UUID id,
        UUID batchId,
        StockTransaction.TransactionType transactionType,
        Integer quantityChanged,
        StockTransaction.ReferenceType referenceType,
        UUID referenceId,
        String note,
        UUID performedBy,
        String actorName,
        LocalDateTime createdAt) {

    public static StockTransactionResponse from(StockTransaction txn, String actorName) {
        return new StockTransactionResponse(
                txn.getId(),
                txn.getBatchId(),
                txn.getTransactionType(),
                txn.getQuantityChanged(),
                txn.getReferenceType(),
                txn.getReferenceId(),
                txn.getNote(),
                txn.getPerformedBy(),
                actorName,
                txn.getCreatedAt());
    }
}
