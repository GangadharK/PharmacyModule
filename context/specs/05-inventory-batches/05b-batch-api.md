# 05b — Batch API

**Parent:** `05-inventory-batches.md`
**Persona:** `@pharmacy-backend`

## Goal

Create, read, edit, quarantine, release and adjust batches, with every quantity
change leaving a ledger row in the same transaction.

## Endpoints

Base `/api/v1/inventory`. Read `CASHIER`+, write `PHARMACY_MANAGER`+.

| Method | Path | Body / params | Success |
| --- | --- | --- | --- |
| `GET` | `/batches` | `medicineId`, `itemId`, `supplierId`, `status`, `location`, `expiringBefore`, `search`, `page`, `size` | 200 `PageResponse` |
| `GET` | `/batches/{id}` | — | 200 |
| `POST` | `/batches` | `CreateBatchRequest` | 201 |
| `PATCH` | `/batches/{id}` | `UpdateBatchRequest` | 200 |
| `POST` | `/batches/{id}/quarantine` | — | 200 |
| `POST` | `/batches/{id}/release` | — | 200 |
| `POST` | `/batches/{id}/adjust` | `AdjustStockRequest` | 200 |
| `GET` | `/batches/{id}/transactions` | `page`, `size` | 200 `PageResponse` |
| `GET` | `/reconciliation` | — | 200 list of mismatches |

`@PreAuthorize("hasAnyRole('SUPER_ADMIN','PHARMACY_MANAGER')")` on every write,
UPPER_CASE only (AGENTS rule 8). The role list is spelled out rather than
inherited from a constant for the same reason SCMS's mixed case does not work:
one interpretation of the hierarchy, in the annotation, every time.

## Request shapes

`CreateBatchRequest` — exactly one of `medicineId` / `itemId`; the pair is
validated in the service, because a `CHECK` violation surfaces as an opaque
constraint name and a client deserves better than that.

```java
public record CreateBatchRequest(
        UUID medicineId,
        UUID itemId,
        UUID supplierId,
        @NotBlank @Size(max = 100) String batchNumber,
        @PastOrPresent LocalDate manufacturingDate,
        @NotNull LocalDate expiryDate,
        @NotNull @PositiveOrZero Integer quantityReceived,
        @NotNull @DecimalMin("0.00") BigDecimal costPrice,
        @NotNull @DecimalMin("0.00") BigDecimal sellingPrice,
        InventoryBatch.Location location,
        String note) { }
```

`expiryDate` is `@NotNull` and not `@Future` — receiving a batch that expires
next month is legitimate and common in this domain. Rejecting it would be
correct-looking and wrong.

`AdjustStockRequest.quantityChange` is a **signed** `int`, `0` rejected. A
zero-delta adjustment is a no-op that looks like an audit event, and the audit
trail should mean something.

## Service rules

**`create`** — assigns the UUID, requires the polymorphic pair, defaults
`status = ACTIVE` and `location = MAIN_PHARMACY`, and writes the opening
`PURCHASE_IN` ledger row with `quantityChanged = quantityReceived` in the same
transaction. A batch with no ledger row is not a batch, it is an unexplained
number.

**`update`** — metadata only. `quantityReceived` and `quantityAvailable` are
absent from `UpdateBatchRequest` on purpose: the only ways to change stock are
`adjust` and, from Unit 06, FEFO. An `update` that could move quantity would be
a way to change stock without a ledger row.

Immutable: `medicineId`, `itemId`. Changing what a batch *is* would silently
re-point its whole history at a different product.

**`adjust`** — one conditional `applyDelta`, then a ledger row, in one
transaction. A zero-row result is `InsufficientStockException` (400,
`INSUFFICIENT_STOCK`) carrying the batch number and both quantities, because
"insufficient stock" without saying how much is left is not actionable at a
counter. When the balance reaches 0 the service sets `status = DEPLETED`.

**`quarantine` / `release`** — state transitions, `InvalidStateTransitionException`
(409) when the batch is not in the required state, and a **zero-delta** ledger
row recording who did it and when. See parent §4.1 for why the delta is zero and
the row is still written.

## Cost redaction

`InventoryBatchResponse.costPrice` is `@JsonInclude(NON_NULL)`, and the mapper
passes `null` for a `CASHIER`. The key is absent from the body, not null —
AGENTS rule 7 asks for absence, and a client that distinguishes the two learns
something it should not.

`sellingPrice` is never redacted. A cashier must be able to quote it.

## Verify

Live, in this order, because the order is the point:

1. `POST /batches` with `quantityReceived = 100` → ledger has one `PURCHASE_IN` of `+100`.
2. `POST /adjust` with `-30` → balance 70, ledger sums to 70.
3. `POST /adjust` with `-80` → **400** `INSUFFICIENT_STOCK`, balance still 70,
   ledger still sums to 70. A failed adjustment must leave no trace.
4. `GET /reconciliation` → empty.
5. As `CASHIER`, `GET /batches/{id}` → the body has no `cost_price` key at all,
   and does have `selling_price`.
