# Unit 06 — FEFO Allocation Engine

**Status:** Implemented and verified — backend complete, 178 tests green, live HTTP
checks passed. See §9 for what was run and §7 for what changed from the plan.
**Depends on:** Unit 05 (`inventory_batch`, `stock_transaction`, the ledger invariant)
**Source of truth:** `architecture-context.md` §5 (primary), `MDD.md` §4.1 (secondary)

---

## 1. What this unit delivers

The decision of *which* batch a patient receives. Unit 05 built the shelves and
the ledger; this decides what comes off them, and is the first
concurrency-sensitive code in the system.

- `FefoAllocator` — a **pure function**. No Spring, no repository, no writes, no
  injected clock. The arithmetic, and nothing else.
- The locked candidate read (`SELECT … FOR UPDATE`) and the guarded deduction
  that applies an allocation, in one `@Transactional` method.
- `fefo-preview` (read-only, unlocked) and `dispense` endpoints.
- `FefoAllocatorTest` — 11 edge cases from `architecture-context.md` §5.3, no
  mocks, no database.
- A concurrency test that two threads dispensing the same batch cannot both win.

Built as specified, with three additions that the plan did not call for and that
§4.1, §4.2 and §9 explain: a plan assertion guarding the partial index, a literal
`status` that the index depends on, and a managed re-read that is what makes
`DEPLETED` actually persist.

## 2. Shape

```text
FefoAllocator.allocate(candidates, requestedQuantity, today)
    │
    ├── pure: no Spring, no repository, no writes, no clock
    ├── input:  an already-filtered, already-sorted candidate list
    └── output: AllocationResult { allocations, totalAllocated, shortfall }
```

The split is the whole point of the design. The service does querying, filtering
and locking; the allocator does arithmetic. Every edge case in §5.3 is then a
plain unit test that constructs a list and asserts a result — no database, no
Spring context, no mocks.

`today` is passed **in** rather than read from a clock. A pure function with a
clock is not pure, and the expiry-boundary behaviour (`expiry_date >= today`)
needs a testable "today" more than anything else in this unit.

## 3. `StockCandidate` — the allocator's input, not an entity

The allocator takes a `StockCandidate(id, batchNumber, expiryDate, available,
sellingPrice)` record, **not** `InventoryBatch`.

A JPA entity would work — it is a plain object — but passing one makes the pure
class depend on a `@Entity` and invites it to reach for an association it should
not have. A five-field record makes the input contract explicit, keeps
`ddl-auto` concerns out of the arithmetic, and means the pure test constructs
five values instead of a builder with twelve. The service maps entity →
candidate, which is a three-line projection.

`sellingPrice` is carried because `Allocation` includes `unitPrice` (§5.3's
`Output`), and a batch is priced individually — two batches of the same medicine
can have different selling prices, and the allocation has to say which it used.

## 4. Candidate selection — in SQL, not in Java

```sql
SELECT ... FROM inventory_batch
WHERE medicine_id = :medicineId AND location = :location
  AND status = 'ACTIVE' AND quantity_available > 0
  AND expiry_date >= :today
ORDER BY expiry_date ASC, batch_number ASC
```

Four things are load-bearing:

- **`expiry_date >= :today`, not `> CURRENT_DATE`.** Stock expiring *today* is
  still dispensable today; the sweep writes it off tomorrow. `>` would make stock
  vanish a day early and would make the behaviour depend on the server's timezone
  — the kind of bug that only appears on a machine in another region.
- **`status = 'ACTIVE'`** excludes quarantined and expired stock. Quarantine is
  the reason a pharmacist must be able to say "not this one" without deleting it.
- **`ORDER BY expiry_date ASC, batch_number ASC`.** The second key is what makes
  the result reproducible; see §7.3.
- **`FOR UPDATE`** on the issuing path only. See §6.

`location` is not in the index, deliberately (`MDD.md` §3.5): it is filtered on
but not ordered by, so including it would widen the key for nothing.

### 4.1 The status filter is a literal, and that is load-bearing

`idx_fefo_search` is a **partial** index — it contains only rows where
`status = 'ACTIVE' AND quantity_available > 0`. PostgreSQL will only use a partial
index when it can *prove* that predicate holds for the rows the query wants.

Write `status = :status` with a bound parameter and the value is unknown at
planning time, so the proof fails. Nothing is wrong with the result, no test
fails, and the response is correct — but the planner drops the index and scans:

```
-- status = $3, forced generic plan, 3,200-row table
Seq Scan on inventory_batch
  Filter: (quantity_available > 0) AND (expiry_date >= $4)
          AND (medicine_id = $1) AND (location = $2) AND (status = $3)
```

Every dispense reads every batch in the system and throws away the ones for other
medicines as a residual filter. It only appears once PostgreSQL stops building
custom plans and switches to a generic one — the sixth execution of a hot query —
so it looks fine in development and in every functional test.

So `status = 'ACTIVE'` is written as a literal and
`FefoQueryPlanTest` asserts the resulting plan, including a counter-test proving
the parameterised form loses the index. Do not "tidy" it into a parameter.

Measured on this database with `plan_cache_mode = force_generic_plan`, both forms,
at realistic cardinality (400 medicines × 8 batches):

| Form | Plan |
| --- | --- |
| `status = 'ACTIVE'` | `Index Scan using idx_fefo_search`, `Incremental Sort` |
| `status = $3` | `Seq Scan` with all five predicates as residual filters |

### 4.2 No `@Lock` on the native query

The obvious way to express the lock is `@Lock(PESSIMISTIC_WRITE)`, and it does
not work here: Spring Data rejects it on a native query with *"Illegal attempt to
set lock mode for a native query"*. The `FOR UPDATE` is therefore written into the
SQL text, which is where it has to live anyway given §4.1. `FefoQueryPlanTest`
asserts the clause is present on the issuing read and absent from the preview.

## 5. Writing the deduction

The allocator writes nothing. The caller, inside one `@Transactional` method,
applies each allocation through the **existing** guarded update from Unit 05:

```text
UPDATE inventory_batch
   SET quantity_available = quantity_available - :take
 WHERE id = :batchId AND quantity_available >= :take
```

Zero rows affected means someone moved the balance since the locked read. Throw,
and the whole transaction rolls back — **a partial dispense is not
representable**, which is why every allocation's deduction and its `SALE_OUT`
ledger row go in the same transaction as every other allocation in the request.

Each deduction writes a `stock_transaction` with a **negative** delta. This
reuses `InventoryBatchService`'s single `post()` writer rather than adding a
second, so the claim "the service is the only writer of `stock_transaction`"
stays true — a second writer is how the ledger starts disagreeing with itself.

`InventoryBatchService.adjust()` picks its ledger type from the sign of the delta,
which is wrong for dispensing: a negative adjustment is `DAMAGE_WRITE_OFF`, but a
negative **dispense** is `SALE_OUT`. So `issueStock` passes the type explicitly
rather than reusing `adjust()`, whose sign-based typing is correct for its own
use and wrong for this one.

## 6. Preview is unlocked; issue is locked

Two paths, deliberately different:

| | `fefo-preview` | `dispense` |
|---|---|---|
| Lock | **none** | `PESSIMISTIC_WRITE` |
| Writes | none | allocations + ledger rows |
| Stale answer | harmless | impossible |

A preview is a "what would happen" question. Locking it would serialise every
pharmacist browsing the FEFO screen against real dispensing, to protect nothing —
it performs no writes, so a stale answer is not a wrong answer, just an old one.
Issuing cannot afford that.

Both run the **same** allocator, so the preview cannot disagree with the outcome
about which batches would be used — only about the quantities, if stock moved in
between.

`FOR UPDATE` locks the rows the scan returns, not the whole range: it does not
prevent a concurrent `INSERT` of a new, earlier-expiring batch. That is accepted.
The new row's own deduction still goes through the guarded update, so no negative
stock is possible — the lock is an optimisation against a lost update, and
invariant 1 is held by the `CHECK` and the guard independently of it.

## 7. Deviations from the written specs, and why

Three places where the context documents disagree with each other. All three are
resolved here, and all three are in the record rather than silently decided in
code.

### 7.1 Requested quantity of zero

`architecture-context.md` §5.3 lists both:

- "Zero request → empty allocation list, no error"
- "Zero or negative request → rejected at the boundary"

These contradict. Resolved as:

| Input | Behaviour | Why |
| --- | --- | --- |
| `0` | empty result, **no error** | "How many would you dispense?" → `0` is a well-formed question with a well-formed answer. The FEFO preview screen starts at 0 and increments; erroring there would make the screen's initial state an error state. |
| negative | `BusinessRuleException` | No sensible reading exists, and guessing which sign was meant is worse than refusing. |

The first row is taken from the first table entry; the second row is the
"rejected" half of the third, restricted to the inputs that are actually
meaningless.

### 7.2 `IllegalArgumentException` is not used

Both `MDD.md` §4.1 and `architecture-context.md` §5.3 say a bad request is
rejected with `IllegalArgumentException`. `AGENTS.md` and
`code-standards.md` §2.6 forbid it — SCMS throws it for everything, which is
precisely why this project has typed exceptions and a `code` field to branch on.
A client that gets `IllegalArgumentException` cannot distinguish this from the
missing-resource case, which is the whole reason the types exist.

Used instead: `BusinessRuleException` → HTTP 400, `code: BUSINESS_RULE_VIOLATION`.
`InsufficientStockException` is already typed for the shortfall case and already
carries `requested` / `available` for the UI's "only 12 of 20 available" message.

**`MDD.md` §4.1's pseudocode comment is stale and should be corrected to match**;
`architecture-context.md` §5.3 has the same line. Recorded rather than fixed in
place, because `MDD.md` is the schema source of truth and editing it is a
deliberate act.

### 7.3 Tie-break key

`MDD.md` §9.2 states the ordering rule as
`ORDER BY expiryDate ASC, receivedAt ASC`. **There is no `receivedAt` column.**
`inventory_batch` (§3.5) has `created_at` / `updated_at`, and `updated_at` changes
on every adjustment, so it cannot order by arrival.

Resolved: `batch_number ASC`, as `architecture-context.md` §5.2 and the
§5.3 tie-break row both specify. Batch numbers are assigned per delivery and are
usually sequential, so it is also the closest available proxy for "arrived first"
— and, unlike `created_at`, it is stable, which is what reproducibility needs.
A tie on `batch_number` itself falls back to `id` so the order is total.

### 7.4 `SALE_OUT` has no legal `reference_type`, so it posts NULL

`stock_transaction.reference_type` is constrained to `PURCHASE_ORDER`, `INVOICE`,
`RETURN` and `CYCLE_COUNT` (`V5` §3.9) — the four movement types a *procurement*
ledger needs. A sale is not one of them, and `V5` is applied and immutable, so
there is no `SALE` member to post.

The two halves cannot both be satisfied: an inventory movement must record its
cause, and there is no value that is both a cause of issue and an allowed
reference. Posting `PURCHASE_ORDER` would be a lie in the audit trail; dropping the
column would lose the link the moment billing exists.

Resolution: the transaction type carries the truth (`SALE_OUT`) and
`reference_type` / `reference_id` are left NULL until a sale document exists to
point at. The batch and quantity and actor are all still recorded, so the
reconciliation invariant of §5 is untouched — which is the property that
matters for this unit.

**Open question for the billing unit:** the moment a sale is persisted, a `SALE`
reference type is needed, and it cannot be a change to `V5`. It requires a new
migration to extend the enum, plus a prescription reference if a sale is ever
fulfilked from an HMS order. Recorded rather than pre-empted — billing is out of
scope for v1, and guessing the shape of a table that does not exist yet is how
migrations get written twice.

## 8. Endpoint contract

| Method | Path | Role | Locks | Writes |
| --- | --- | --- | --- | --- |
| POST | `/api/v1/inventory/fefo-preview` | CASHIER+ | no | no |
| POST | `/api/v1/inventory/dispense` | CASHIER+ | yes | yes |

`POST` for both, because both take a request body carrying a quantity — a
`GET` with a quantity in the query string invites a prefetch, a crawler, or a
"just checking" of an unintended magnitude.

CASHIER may dispense: physically handing over stock is the role's job. The
response still omits `cost_price` (§4.3), so a cashier learns *which* batch was
used and *what it sells for*, and never what it cost.

Response carries `shortfall` explicitly, so a caller can render "you asked for 20,
only 12 are available" from a 400 body rather than parsing a message.

## 9. Verify

Three layers, because the algorithm, the query, and the transaction each fail in
a way the others cannot see.

**1 — `FefoAllocatorTest`, 28 tests, no Spring and no database.** The cases of
§5.3 plus boundaries: zero request, single sufficient, single insufficient, total
exceeded, spans two batches, exact boundary (no zero-quantity allocation),
negative rejected, expired and quarantined never allocated, tie broken by
`batch_number` then `id`, location filter, and `sum == requested − shortfall`
across randomised inputs.

**2 — `FefoServiceTest`, 26 tests against real PostgreSQL.** The allocator is
correct over a list; that says nothing about whether SQL returns the right list.
These seed rows and check what the repository actually produced, and cover
`DEPLETED` / `EXPIRED` persistence, the shortfall path leaving *no* ledger rows
behind, the two-batch ledger, preview writing nothing, and nested-`@Transactional`
concurrency where two threads issue against one batch and exactly one wins.

**3 — `FefoQueryPlanTest`, 5 tests on the plan, not the result.** Asserts the
partial index is chosen, that the parameterised form loses it (§4.1), that
`status = 'ACTIVE'` is a literal, that the tie-break is total, and that
`FOR UPDATE` is on the issuing read and absent from the preview.

**Mutation-checked.** An allocator that quietly returns a short list is the exact
failure this unit exists to prevent, and a passing suite proves nothing if the
assertions are absent. Eight deliberate defects were each reverted in turn and
each failed the suite: dropping the `batch_number` tie-break, making the expiry
filter inclusive, removing the expiry defence from the allocator, removing
`FOR UPDATE`, letting a short allocation through, writing `DAMAGE_WRITE_OFF`
instead of `SALE_OUT`, parameterising the status literal, and dropping the managed
re-read that persists `DEPLETED`. All eight were caught; all eight were restored
from a clean snapshot and the suite returned green.

**Live, against the dev server.** The behaviour the tests describe, confirmed
end-to-end over HTTP:

- Preview of 12 against three batches staggered 30 days apart returned
  `LIVE-SOON` ×10 then `LIVE-SOONEST` ×2 — earliest expiry first, partial second
  batch, and `LIVE-LATE` untouched. Preview left all three balances at 10.
- Dispense of the same 12 moved them to `0` / `8` / `10` and wrote exactly one
  `SALE_OUT` per consumed batch, negative, with the acting user recorded.
- `0` is a successful empty result; `-3` is `VALIDATION_FAILED`; `99` is
  `INSUFFICIENT_STOCK` carrying `requested` / `available`; every one of the three
  left all balances untouched.
- Quarantining `LIVE-LATE` dropped availability by exactly its 10 units and made a
  30-unit request short — the quarantined batch was never allocated.
- Four simultaneous requests for 10 units of 10-unit stock produced **one** `201`
  and three `INSUFFICIENT_STOCK`, finishing at `0` with no negative balance
  anywhere in the database and reconciliation clean.
- CASHIER dispensed successfully (201) and received no `costPrice` field in either
  the dispense response or the batch list, while being refused quarantine with 403.
- Reconciliation reported `0` mismatches throughout.

**The bug this caught.** Dispense ran, the balances were correct, and the batch
still read `ACTIVE` after going to zero. `applyDelta` is a `@Modifying` query with
`clearAutomatically = true`, so it detaches the very entity the caller is holding;
the subsequent `setStatus(DEPLETED)` was applied to a detached instance and
silently thrown away. The unit tests passed throughout, because they asserted on
the object they had just mutated rather than re-reading the row. Fixed by
re-reading a managed instance after every delta. `InventoryLedgerTest` now
`flush()`es and `clear()`s before asserting, so the test asserts what was
persisted rather than what the code intended.

