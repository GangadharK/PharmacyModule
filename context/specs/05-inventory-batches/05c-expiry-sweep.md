# 05c — Expiry sweep

**Parent:** `05-inventory-batches.md`
**Persona:** `@pharmacy-backend`

## Goal

Batches past their expiry date stop being dispensable, automatically, and the
reason is recorded.

## Why a job and not a query filter

FEFO already excludes `EXPIRED` rows, so nothing *dispenses* an expired batch
even before this job runs. What the job adds is that `EXPIRED` is a state the
system decided, with an author and a timestamp, rather than something every
reader has to re-derive from a date comparison. Near-expiry reporting (Unit 09)
and the Schedule H register both want "expired on the 4th", not "the expiry date
is in the past".

It is a job, not a filter, because a filter makes every reader pay for the same
comparison and none of them record it.

## The job

```java
@Component
@ConditionalOnProperty(name = "app.inventory.sweep.enabled", havingValue = "true")
public class ExpirySweepJob {

    @Scheduled(cron = "${app.inventory.sweep.cron:0 15 2 * * *}")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void expireBatchesPastTheirDate() { ... }
}
```

**02:15, not midnight.** A sweep at midnight competes with whatever else runs at
midnight, and an expiry sweep that has not finished by opening time means
pharmacists see stale statuses during the busiest hour of the day.

**`REQUIRES_NEW`, per batch.** The parent spec's transaction rule scopes a
transaction to one aggregate. One batch whose ledger row violates something must
not roll back a day of other batches' expiries. The cost is that the sweep is not
atomic across batches, which is correct here: partial progress on "expire what
is due" is strictly better than none, because the next run picks up the rest.

**Disabled by default in dev.** `app.inventory.sweep.enabled: false` in the `dev`
profile. This is a real ledger writer; running it while someone is testing stock
levels locally would move quantities under them and make an unrelated bug look
like data corruption. Production sets it true.

## What one batch's expiry does

1. `status` → `EXPIRED` (from `ACTIVE` or `QUARANTINED`; `DEPLETED` is skipped,
   there is nothing to expire).
2. `quantity_available` → `0`.
3. One `stock_transaction`: type `EXPIRY_WRITE_OFF`,
   **`quantity_changed = −remaining`**, `performed_by = NULL`, `note` naming the
   expiry date.

`remaining` is the balance at the time of the sweep, not `quantity_received`. A
batch that received 100 and dispensed 60 expires the 40 that are left; the first
60 are already accounted for by their own `SALE_OUT` rows and writing them off
again would corrupt both the balance and the shrinkage figure.

### Why −remaining and not 0

**This section originally argued for 0, and that argument was wrong.** It claimed
both choices reconcile. They do not. The invariant is
`SUM(quantity_changed) = quantity_available`, and step 2 sets the balance to `0`,
so the ledger must drop by `remaining` or the sum no longer equals the balance. A
zero delta makes the expiry sweep the single largest source of reported
corruption in the system — it manufactures a mismatch on every batch it touches,
and the mismatch is indistinguishable from real corruption.

That was verified rather than assumed. `InventoryLedgerTest.expiryWritesOffRemaining`
mutates the delta to `0` and asserts the ledger sum still equals the balance; with
the mutation the test fails, and `InventoryLedgerTest.writesOffRemainingNotReceived`
pins the difference between `−remaining` and `−quantity_received`.

A zero delta would also leave expired stock with no trace in the movement history,
which makes shrinkage — the number a pharmacy is judged on — uncomputable, because
stock would vanish from the books with nothing to explain where it went.

`chk_txn_changed` still *permits* zero for `EXPIRY_WRITE_OFF`, deliberately. That
constraint's job is to stop a no-op masquerading as an audit event for the types
where a no-op is meaningless; it is not the place to encode this method's
arithmetic. Tightening it would need a V6 migration to enforce a rule every writer
already upholds, so it is left alone.

`performed_by = NULL` is the one legitimate anonymous ledger row, and
`CurrentUserContext` is written to make it deliberate: a human action with no
caller is rejected before it reaches here, so a null in this column can only have
come from the sweep.

## Near-expiry is not this job

`expiringBefore` on the list endpoint answers "what needs attention", read-only.
The sweep answers "this is no longer usable", and writes. Keeping them apart
means a dashboard refresh can never mutate stock.

## Verify

- `InventoryLedgerTest` against real PostgreSQL: seed an `ACTIVE` batch dated
  yesterday and one dated next month, expire the first, assert it is `EXPIRED`
  with a `−remaining` ledger row and `performed_by` null, and that the second is
  untouched.
- A `QUARANTINED` batch past its date is expired too — a quarantined batch still
  expires, and skipping it would leave expired stock in a location someone can
  physically reach.
- A `DEPLETED` batch is left alone.
- Mutation-checked: replacing `−remaining` with `0` fails the suite. Verified.
- Live: `POST /batches/{id}/quarantine` on an already-`QUARANTINED` batch returns
  **409**, proving the transition guard is the same one the sweep relies on.
