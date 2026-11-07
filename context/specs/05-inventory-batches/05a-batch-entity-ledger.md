# 05a — Batch entity, ledger entity, repositories

**Parent:** `05-inventory-batches.md`
**Persona:** `@pharmacy-backend`

## Goal

Two entities over the two tables, and the repository methods that make the FEFO
scan and the ledger walk cheap. No business rules here.

## Design

| Concern | Choice |
| --- | --- |
| `InventoryBatch.id` | `UUID`, **application-assigned** — no `@GeneratedValue` |
| Money | `BigDecimal`, columns `numeric(10,2)` |
| `status`, `location` | `@Enumerated(EnumType.STRING)`, mirrored by `CHECK` in the schema |
| `medicineId` / `itemId` | plain `UUID` columns. **No `@ManyToOne`** — AGENTS forbids it, and the ledger's `performed_by` needs an id before the row is flushed |
| `costPrice` | `BigDecimal`, never on any response a `CASHIER` can read |
| `StockTransaction.quantityChanged` | `int`, **signed** |
| `performedBy` | `UUID`, nullable — null means a system action |
| Timestamps | set in the service from `LocalDateTime.now()`; no `@PreUpdate`, no trigger |

**Why no `@ManyToOne` even for reads.** A batch's medicine and supplier are
display names, not traversals. A `@ManyToOne` would pull `Medicine` into the
session, and `Medicine` carries `searchBlob` and a generated `soundex_code` that
have no business being in memory during a stock query. The catalogue service
already resolves display names in one batched query per page; inventory does the
same.

## Repository methods that matter

```java
// FEFO candidate read — Unit 06 adds the pessimistic variant of this.
List<InventoryBatch> findByMedicineIdAndStatusAndQuantityAvailableGreaterThan(
        UUID medicineId, InventoryBatch.Status status, int quantity);

// The conditional deduction. Returns how many rows it actually changed, which
// is 0 when another transaction got there first.
@Modifying
@Query("""
       UPDATE InventoryBatch b
          SET b.quantityAvailable = b.quantityAvailable + :delta,
              b.updatedAt = :now
        WHERE b.id = :id
          AND b.quantityAvailable + :delta >= 0
        """)
int applyDelta(@Param("id") UUID id, @Param("delta") int delta, @Param("now") LocalDateTime now);
```

`applyDelta` is the concurrency guard, and its return value is the whole point. A
service that checks the balance, then updates, has a race; a service that issues
one statement whose predicate includes the post-condition cannot. `int` rows
changed, not an exception, so the caller decides whether a zero is fatal (it is)
and what to say about it (`InsufficientStockException`).

The ledger side:

```java
List<StockTransaction> findByBatchIdOrderByCreatedAtAsc(UUID batchId);

@Query("""
       SELECT t.batchId, SUM(t.quantityChanged)
         FROM StockTransaction t
        GROUP BY t.batchId
       HAVING SUM(t.quantityChanged) <> (SELECT b.quantityAvailable
                                           FROM InventoryBatch b
                                          WHERE b.id = t.batchId)
       """)
List<Object[]> findReconciliationMismatches();
```

## Traps worth naming

- **`save()` on a new entity fails** with "identifier must be manually assigned".
  Every service assigns the UUID itself. This is the AGENTS rule that exists so a
  ledger row can reference the batch in the same transaction; it costs a
  `UUID.randomUUID()` per create and buys referential safety in a batch insert.
- **`@Modifying` needs `clearAutomatically`/`flushAutomatically` care.** After a
  conditional update the in-memory entity is stale. The service re-reads rather
  than trusting the managed instance, because the managed instance still holds the
  pre-update balance and would reconcile against a number the database has
  already changed.
- **`quantity_received` never decreases.** It is a cumulative figure for
  shrinkage reporting; `quantity_available` is the balance. Conflating them makes
  write-offs look like returns.

## Verify

`Medicine` and `Supplier` are the reference for style. `ddl-auto: validate` is the
real check that every column name, type and nullability here matches V5.
