# Unit 07 — Inventory UI

**Status:** Implemented and verified — 37/37 browser checks, typecheck and build clean
**Depends on:** Unit 06 (`fefo-preview`, `dispense`), Unit 05 (`/inventory`, history)
**Source of truth:** `ui-context.md`, `architecture-context.md` §5

---

## 1. What this unit delivers

The first screens a pharmacist uses on an actual shift. Everything so far has
been a list of records; this is the first screen where somebody *acts* — hands
stock over, and the number they hand over has to be right.

- `BatchesPage` — the stock list, AG Grid, filtered by status, location and
  expiry window.
- `ExpiryBadge` — how long a batch has left, and how alarmed to be about it.
- `CostPriceCell` — renders cost only for roles entitled to it.
- `FefoPreviewPage` + `FefoAllocationTable` — "I need 30 of this", and what the
  system will actually give them.
- A ledger-history view per batch, because *"why does this say 8?"* is a question
  about the ledger, not about the batch.

## 2. What this unit deliberately does not do

- **No receiving form.** Unit 05's `POST /inventory` is the contract, but a
  receive screen belongs to Unit 08's purchase entry, where a delivery is
  received *against a purchase order* rather than typed in blind. A standalone
  "add a batch" form is the wrong shape and would need deleting.
- **No adjust / quarantine / release controls.** The endpoints exist and the
  backend is verified, but these are destructive-to-audit operations. They go in
  with Unit 09's alerts and cycle count, where the screen is already showing the
  batch that needs attention.
- **No sales.** That is Unit 11, spec not yet written, and it depends on a
  migration this unit does not author.

The nav items for those two already exist in `navigation.ts` at their unit
numbers, so nothing is lost by not building them here.

## 3. Shape

```text
features/inventory/
  api/inventory.api.ts          inventory.api.ts
  batches/BatchesPage.tsx
  batches/BatchHistoryDrawer.tsx
  fefo/FefoPreviewPage.tsx
  fefo/FefoAllocationTable.tsx
  components/ExpiryBadge.tsx
  components/CostPriceCell.tsx
  components/StockStatusBadge.tsx
  index.ts
```

`types/api.ts` gains the wire types. `BatchesPage` reuses
`useResourceScreen` + `ResourceListView` rather than reimplementing pagination —
the trap that hook's javadoc describes is a per-screen mistake, and there are now
seven resources that would each repeat it.

## 4. `BatchesPage`

One row per batch, default sort **expiry ascending** — the list exists to answer
"what runs out first", and the backend already defaults to that order.

| Column | Notes |
| --- | --- |
| Medicine | name; `itemName` for non-medical items |
| Batch | `batchNumber` |
| Expiry | `ExpiryBadge` — never a bare date, see §4.1 |
| Available | `quantityAvailable` of `quantityReceived`, right-aligned, tabular |
| Location | label, not raw enum |
| Status | `StockStatusBadge` |
| Cost | `CostPriceCell` — role-gated, see §4.2 |
| Selling | always visible |

Filters: status, location, and an `expiringWithinDays` select (30 / 60 / 90 /
180). All three map to real backend query parameters. The status filter is a
**status enum** (`ACTIVE` / `QUARANTINED` / `EXPIRED` / `DEPLETED`), not the
tri-state active filter — batches are not soft-deleted, and rendering a
status control that silently does nothing is worse than rendering none. So
`useResourceScreen` is called with `statusFilter: false` and the enum filter is
passed as an extra filter.

Row click opens the history drawer.

### 4.1 `ExpiryBadge` shows a countdown, not a date

A date requires the reader to compare it to today; a pharmacist scanning a
column is answering "is this a problem", not "what is the date".

| `daysUntilExpiry` | Rendering |
| --- | --- |
| `< 0` | "Expired 12d ago" — destructive styling |
| `0`–`30` | "12d" + `destructive` |
| `31`–`90` | "45d" + `warning` |
| `91`–`180` | "120d", neutral |
| `> 180` or `null` | the date itself, muted — no countdown noise |

`null` means no expiry recorded, which is a data-quality state and not the same as
"expires in 400 days"; it gets its own muted rendering rather than being coerced
to `> 180`.

### 4.2 `CostPriceCell` is a server guarantee, not a UI decision

`AGENTS.md` rule 7: the CASHIER response body contains **no `cost_price` field at
all**. So the cell does not format-and-hide; it checks for the field's existence
and renders an em dash when it is absent. A cashier therefore cannot be shown a
cost by a stale cached user object or a mis-set role, because there is nothing in
the payload to show.

This is the reason `CostPriceCell` exists as a component rather than a
`showCost && <Money/>` inline: the correct behaviour on a missing field is a
distinct branch, and seven screens will need it.

## 5. `FefoPreviewPage`

Two panes. Left is a medicine picker and a quantity; right is the allocation the
backend *would* make.

- **Preview on demand, not on keystroke.** The button is explicit. Live preview
  means a request per digit, and a pharmacist typing `30` would fire requests for
  `3` and `30`.
- Quantity `0` is legal and returns an empty allocation — Unit 06 §7.1. The
  screen must not treat an empty result as an error, and must not disable the
  dispense button on it, because dispensing zero is a no-op the user may
  legitimately mean.
- A shortfall is rendered **in the table, in red**, with "requested 30, available
  8". It is not an error toast: the preview succeeded, and its answer is "not
  enough". Treating a successful answer as a failure is what makes users stop
  trusting a preview.
- Dispense is a separate, confirming action, and it is disabled while
  `!complete`.

## 6. Error handling

Branch on `error.code`, never on message text (`AGENTS.md`).

| `code` | Where it can occur | Rendering |
| --- | --- | --- |
| `INSUFFICIENT_STOCK` | dispense | inline on the form, with `requested` / `available` |
| `VALIDATION_FAILED` | both | field errors, mapped to inputs |
| `BUSINESS_RULE_VIOLATION` | dispense | inline |
| `NOT_FOUND` | picker, history | empty state with the id |

`INSUFFICIENT_STOCK` carries `requested` and `available` for exactly this — the
UI must use those fields and not parse the message.

## 9. Verify

`npm run verify:inventory` — 37 checks, driven through the real UI against the
real API, in the style of `verify:catalogue`.

- `npm run typecheck` and `npm run build` — no `any`, no unused.

**Verified in the browser:**

- the batches list renders a seeded batch with its available quantity, and the
  `ExpiryBadge` cell reads a countdown (`12d`) rather than a date;
- the status filter narrows the rows, and the rendered table then shows only the
  active badge;
- row click opens the ledger drawer, which shows the receiving movement and a
  running balance;
- FEFO preview splits across batches in expiry order — the critical band first,
  the calm band not touched at 70 units — and the rows appear in that order;
- a shortfall renders `Short by 10` **in the table**, raises no error state, and
  disables dispense with a reason;
- a quantity of `0` renders an empty allocation without an error;
- dispensing moves the balance by exactly the requested amount, writes one
  negative `SALE_OUT` row, and reconciliation stays at zero mismatches;
- the Batches nav link is a real anchor resolving to a real page, with no
  "not available" placeholder.

**The security check, which is the reason the suite exists:**

- a **manager** sees both the cost and the selling figure;
- a **cashier** sees **no cost figure** — the cell renders an em dash — while
  still seeing the selling price, which they are entitled to;
- asserted at **both** layers: the API omits the `costPrice` key for a cashier
  and includes it for a manager, and the rendered cell differs accordingly. The
  API assertion is the load-bearing one; the DOM assertion catches a regression
  that reorders the columns or swaps the two cells.

Note the earlier form of that check banned *all* currency symbols and failed — the
cashier's selling price is legitimate. Rule 7 redacts the cost, not the price.

**Also verified, and worth recording because it is a gate that had to move:**
`verify:shell` asserted that an unshipped nav item is not an `<a>`, using
**Batches** as its exemplar. Batches shipped with this unit, so the gate began
failing — correctly. The exemplar moved to *Purchase Orders* and the gate gained
the complementary check that a shipped inventory item **is** a link. The gate's
intent is unchanged; only its subject is, and it will need moving again at unit 9
or 11.

## 10. Deviations from this spec, and why

### 10.1 No AG Grid — the shared `DataTable` instead

`00-build-plan.md` unit 07 specifies AG Grid Community. Implemented with the
existing `ResourceListView` / `DataTable` that all four catalogue resources use.

AG Grid's reason for existing is handling row counts a plain table cannot. That
case does not apply here: `/inventory` is **server-paginated**, so at most one
page of rows is ever in the DOM. Column virtualisation over 20 rows costs a
dependency, a second theming system to keep aligned with `ui-context.md`, and a
second table idiom for a pharmacist to learn — in exchange for nothing.

Consistency is the deciding factor: a pharmacist moving between Medicines and
Batches should meet the same row click, the same empty state and the same
pagination. Revisit when a screen genuinely needs client-side sorting or
virtualisation over thousands of rows, and if so then add AG Grid for that screen.

### 10.2 The medicine picker searches; it is not a capped dropdown

§3 originally specified a picker over a client-side list. Built that way, it
fetched `size: 200` once and filtered in the browser, on the reasoning that a
pharmacy's catalogue is small.

It is not small. The dev catalogue passed 200 medicines during verification, and
because the list is name-ordered, everything sorting past the 200th entry was
simply **unselectable** — a silently truncated dropdown that looks complete. That
is worse than no dropdown, and it was caught only because the verification
fixture's own medicine sorted into the truncated tail.

So the picker is a search box over `GET /medicines?search=`, debounced 250 ms,
with a 50-row result page, and an explicit "no active medicine matches" line so
an empty result is distinguishable from a slow one. FEFO no longer depends on the
catalogue being small enough to fetch.

## 11. Open question carried in

`specs/06-fefo-engine.md` §7.4 — a dispense posts `SALE_OUT` with a **NULL
`reference_type`**, because no value in the enum is a truthful cause of issue.
The dispense screen therefore **must not** ask the user to pick a reference type.
It is a server-side omission the UI has to respect rather than compensate for.

## 12. Not verified

- **A human reading the FEFO page on a counter display.** "Does the split read
  clearly at arm's length under bad lighting" is a judgement no assertion makes.
  Every automated gate above can pass on a page a pharmacist finds unusable.
