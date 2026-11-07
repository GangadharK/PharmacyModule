package com.potlatech.pharmacy.repository.projection;

import java.util.UUID;

/**
 * Raw row behind a {@code StockAlertResponse}: the aggregate and the threshold,
 * with no severity.
 *
 * <p>Severity is computed in the DTO's factory, not in SQL. It is a three-way
 * classification with a judgement in it, and a {@code CASE} expression would bury
 * that judgement in a string where nobody reviewing the query would read it.
 */
public interface LowStockRow {

    UUID getId();

    String getItemName();

    String getItemType();

    String getForm();

    String getStrength();

    Integer getTotalAvailable();

    Integer getReorderLevel();

    Integer getActiveBatchCount();
}
