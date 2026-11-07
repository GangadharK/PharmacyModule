# Unit 09 — Alerts & Cycle Count

**Status:** Spec written, not yet implemented
**Depends on:** Unit 05 (`/inventory`), Unit 06 (`applyDelta`, FEFO locking)
**Unblocks nav:** Cycle Counts, Low Stock, Near Expiry — three of the five
inert links.
**Source of truth:** `ui-context.md`, `architecture-context.md` §5,
`00-build-plan.md` unit 09

---

## 1. Scope

Three screens over queries that do not exist yet, plus one write path:

| Screen | Endpoint | Write? |
| --- | --- | --- |
| Low Stock | `GET /inventory/alerts/low-stock` | no |
| Near Expiry | `GET /inventory/alerts/near-expiry` | no |
| Cycle Counts | `POST/GET /inventory/cycle-counts` | **yes** |

Schedule H / H1 register is explicitly **out** — it is a compliance artifact
with its own signature rules, and lumping it in would mean shipping a register
nobody has validated. Low Stock and Near Expiry are the operational alerts; the
register stays open.

---

## 2. Low Stock — sum across batches, not per batch

`GET /inventory/alerts/low-stock`

A medicine is low when **`SUM(quantity_available)` over its ACTIVE batches** is
`<= reorder_level`.

Summing, not testing batches individually, is the whole point. A medicine in
three batches of 4 with a reorder level of 10 holds 12 and is not low; a naive
"any batch below the reorder level" test flags all three. The reorder level is a
statement about the *medicine*, so the comparison has to be about the medicine
too.

`reorder_level` already exists on `medicine_catalogue`, and on
`non_medical_item`; the query reads the existing column rather than adding a
stock-threshold table that would then have to be kept in sync with it.

### 2.1 Response

`StockAlertResponse` per medicine:

- `medicineId`, `genericName`, `form`, `strength`
- `totalAvailable` — the sum
- `reorderLevel`
- `shortfall` — `max(0, reorderLevel - totalAvailable)`
- `severity` — `OUT_OF_STOCK` (total 0) / `CRITICAL` (total <= 25% of level) /
  `LOW` (otherwise)
- `activeBatchCount`

Sorted by `shortfall DESC`, then name. "Most short first" is the ordering a
person actually works the list in.

`CRITICAL` at 25% rather than at zero keeps a plain `LOW` label from meaning
"normal", which is the failure mode of any threshold nobody can justify — the
number is arbitrary, and the spec says so rather than pretending otherwise.

### 2.2 Non-medicines

Included in the same response, with `itemType: MEDICINE | NON_MEDICAL`. A
pharmacy orders dressings and gloves against the same reorder process, and a
stock-out alert that silently omits them is a broken alert.

---

## 3. Near Expiry — global, unlike the existing endpoint

`GET /inventory/alerts/near-expiry?withinDays=90`

The existing `GET /inventory/expiring` **requires a `medicineId`**. It answers
"when does this one medicine expire", which is a dispensing aid. The alert needs
"what is expiring across the whole pharmacy" — a different question, so a
different endpoint rather than an optional parameter that turns one method into
two.

`EXPIRED` batches are excluded. They are already written off, and listing them
as "expiring soon" would be alarming noise about stock that no longer exists.
`DEPLETED` and `QUARANTINED` are included — a quarantined batch still has to be
destroyed on its date.

`ExpiryAlertResponse` per batch: `batchId`, `batchNumber`, `medicineId`,
`genericName`, `quantityAvailable`, `expiryDate`, `daysRemaining`,
`expiryState` (`EXPIRED` / `CRITICAL` <= 30 / `WARNING` <= 90 / `OK`),
`status`.

Sorted `expiry_date ASC`. Default 90 days, capped at 365 so a typo cannot ask
for a decade of history.

---

## 4. Cycle Count — a record, not a stock tweak

The obvious implementation is a form: "enter what you counted, save," calling
`POST /inventory/{id}/adjust` per line with a note. It is fast to build and
**wrong**, because it answers the question "can I change stock?" when the feature
exists to answer "can I trust the stock figure, and who said otherwise?"

Three things a stock tweak cannot record, all of which are the point:

1. **Who counted**, and **when**. A variance is an accusation that the system was
   wrong. It needs a name on it.
2. **What the system claimed at the time of counting** — separately from what it
   says now. A sale during the count is not a variance, and silently absorbing
   one destroys the only signal a cycle count produces.
3. **That the count happened at all**, including when nothing was wrong. "We
   counted and the books were right" is a real, reportable outcome, and a
   stock-tweak flow records nothing when variances are zero.

### 4.1 Schema — `V6`

`cycle_count`

- `id` uuid pk
- `reference_no` varchar(32) unique not null — `CC-2026-000001`
- `status` varchar(16) not null — `OPEN` / `POSTED`
- `location` varchar(32) not null
- `counted_by` uuid not null → `app_user(id)`
- `notes` varchar(500)
- `created_at`, `posted_at`, `posted_by`

`cycle_count_line`

- `id` uuid pk, `cycle_count_id` → `cycle_count(id)`
- `batch_id` uuid not null → `inventory_batch(id)`
- `system_quantity` int **not null** — the snapshot, taken at session creation
- `counted_quantity` int nullable — null until a human enters a figure
- `variance` int — generated at post, not stored before then
- unique (`cycle_count_id`, `batch_id`)

Both `batchIds` and `medicineIds` are accepted, and they select different things:
`batchIds` is one carton on the shelf (a damaged lot, a recall re-check), while
`medicineIds` is every batch of a medicine, which is the ordinary "count the
paracetamol" request and cannot be expressed as batch ids because the client does
not know how many batches exist. Supplying both unions them, deduplicated — naming
a batch and its own medicine is a reasonable thing to send. Both empty means
everything countable at that location.

`system_quantity` is a snapshot rather than a live read, and that is requirement
2 above. Computed at post time, a line counted at 12 against a system that
dropped to 9 by then posts a variance of +3 for a shelf nobody touched.

### 4.2 Lifecycle

```
POST   /inventory/cycle-counts     {location, batchIds?, medicineIds?} -> OPEN
GET    /inventory/cycle-counts?status=OPEN                          -> paged
GET    /inventory/cycle-counts/{id}                                 -> session + lines
PATCH  /inventory/cycle-counts/{id}/lines/{lineId} {countedQuantity} -> line
POST   /inventory/cycle-counts/{id}/post                            -> POSTED
```

`POST` with no `batchIds` snapshots **every ACTIVE and QUARANTINED batch at that
location** — the usual case is a full-shelf count. Passing `batchIds` narrows it
to a spot check.

`PATCH` is a PATCH and not a POST, because it is genuinely idempotent: setting
the same counted figure twice must not append a second count.

`post` is the only write to stock, and it is where the invariant is enforced:

- for each line with `counted_quantity` non-null and `variance != 0`, one
  `applyDelta` plus one `ADJUSTMENT_IN`/`ADJUSTMENT_OUT` row carrying
  `reference_type = CYCLE_COUNT` and `reference_id = cycle_count.id`. The
  `reference_id` is what makes the ledger row traceable back to the count — the
  column already exists and has had nothing to point at until now.
- a line with `variance = 0` posts **no** ledger row, but its count is still
  persisted, which is outcome 3 above.
- a line with `counted_quantity` still null blocks the post. A half-finished
  count posting would silently discard the un-counted shelves.

### 4.3 Roles

Create / count / post: `PHARMACY_MANAGER`, `SUPER_ADMIN`. Reading the list is
`PHARMACY_MANAGER`, `SUPER_ADMIN`.

A cashier may read the FEFO page and see stock, but posting a variance is
reconciling the books, which is a manager's act.

### 4.4 Guards carried from `adjust`

A cycle count must not move a batch that is `EXPIRED`, `DEPLETED`, or
`QUARANTINED` — a physical count cannot change those facts, and allowing it would
let a recount resurrect written-off stock. Reuse the existing
`InvalidStateTransitionException` rather than inventing a parallel rule.

---

## 5. UI

Three pages, all read-mostly, on the shared `DataTable` via `ResourceListView`
for the same reason as `07-inventory-ui.md` §10.1 — these are server-paginated
lists, not grids needing virtualisation.

**`LowStockPage`** — medicine, form/strength, available vs reorder level,
shortfall, severity badge. Row click → that medicine's batches.

**`NearExpiryPage`** — batch, medicine, quantity, days remaining, expiry badge
(reusing `ExpiryBadge` from unit 07, whose 30/90-day bands already match
`CRITICAL`/`WARNING` exactly). Default `withinDays=90`.

**`CycleCountsPage`** — list of sessions with reference, location, status,
counter, variance count. "New count" opens a picker of ACTIVE batches at a
location. A session opens a drawer: one row per batch with system quantity,
a counted-quantity input, and live variance; `Post` at the bottom, disabled with
its reason while any line is uncounted.

No cost column on any of the three. A variance is a stock question, and the one
place cost is shown is a price list where someone is about to buy or sell.

---

## 6. Verify

`npm run verify:alerts`, plus `verify:inventory` re-run because cycle count
writes through the same `applyDelta`.

- low-stock sums across batches, so a medicine in 3 batches of 4 at reorder
  level 10 is **not** flagged, and one at 9 **is**;
- severity distinguishes `OUT_OF_STOCK` from `LOW`;
- near-expiry excludes `EXPIRED` and includes `QUARANTINED`;
- a count session snapshots system quantities, a variance posts exactly one
  `ADJUSTMENT_OUT` row carrying `reference_id = cycle_count.id`, and
  `SUM(quantity_changed) == quantity_available` still holds;
- a zero-variance count posts **no** ledger row and still marks the session
  `POSTED`;
- an uncounted line blocks the post with the reason shown;
- a CASHIER is refused the post endpoint and sees no cost on any of the three;
- a sale landing between count-start and post does **not** become a variance.

That last one is the regression test for the snapshot decision, and the reason
§4.1 stores `system_quantity` instead of reading it at post time.

## 7. Not verified

- **Whether 90 days is the right near-expiry window.** A defensible pharmacy
  default, not a researched one. `withinDays` is a parameter precisely so this
  can be changed per pharmacy without touching code.
- **Whether `CRITICAL` at 25% of the reorder level is defensible.** It is
  arbitrary, and a pharmacist should be asked to confirm it rather than inherit
  it.
