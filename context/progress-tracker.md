# Progress Tracker — Pharmacy Management System (PMS)

**Last updated:** September 2026
**Phase:** Unit 04 (medicine & supplier catalogue) UI shipped and verified. Units 01–03 complete.
PostgreSQL is the only engine (decision 22).

---

## Current Phase

**Units 02 and 03 are complete and verified. Unit 04's backend and its whole
frontend surface have landed; the catalogue and user screens are driven
end-to-end by `npm run verify:catalogue`.**

| Area | State |
|------|-------|
| Unit 01 — infrastructure, error envelope, shell, login UI | ✅ verified |
| Unit 02 — identity, JWT, RBAC, user management (API + UI) | ✅ verified |
| Unit 03 — catalogue API (medicines, suppliers, manufacturers, items) | ✅ verified |
| Unit 04 — catalogue UI (list, detail, create, edit, deactivate/reactivate, delete) | ✅ verified by `verify:catalogue` (46 checks) |
| Unit 05 — batch inventory with FEFO | ✅ verified — storage and ledger (119/119 backend) |
| Unit 06 — FEFO allocation engine | ✅ verified — pure allocator, locked read, both endpoints (178/178 backend) |
| Unit 07 — inventory UI | ✅ verified — batches, FEFO, ledger (37/37 browser checks) |

### What the catalogue UI ships

- **Medicines** — list with search, Active/Deactivated/All, dosage-form and
  schedule filters; detail; create; edit with `dosageForm` locked; deactivate and
  reactivate.
- **Suppliers** — list, detail, create, edit, deactivate/reactivate. No hard
  delete exists, so the UI offers no delete control.
- **Manufacturers** — list, detail, create, edit, and **hard** delete, which the
  server refuses while any medicine references the row.
- **Non-medical items** — list, detail, create, edit with `category` and `unit`
  locked, deactivate/reactivate.
- **Users** — superadmin-only list, detail, create, edit, deactivate/reactivate.
  Editing deliberately leaves a blank password field alone rather than writing an
  empty hash over the stored one; the suite signs in as the edited user to prove it.

Roles: catalogue reads are open to `CASHIER`, supplier reads to `PHARMACIST`, and
every write control to `PHARMACY_MANAGER` and above — enforced in the UI with
`RoleGate` and again on the server with `@PreAuthorize`.

### The `activeOnly` contract changed — read this before writing a client

`GET /medicines`, `/suppliers` and `/items` treat `activeOnly` as **tri-state**:
`true` returns active rows, `false` returns deactivated rows, and **omitting it
applies no filter** and returns both. It previously carried
`@RequestParam(defaultValue = "true")`, which made the list screens' **All**
state silently behave like **Active** — the controller substituted `true` for the
absent value, so the third state of the filter control was not expressible on the
wire. This now matches every other optional filter in the codebase. **Clients must
send an explicit `activeOnly=true` when they want active rows.**

### Five defects found by running the screens, not reading them

Each looked correct in source. The suite is worth more than the fixes:

1. **Row actions navigated away from the form.** `DataTable` put `onClick` on the
   `<tr>`, so clicking a row's **Edit** button also pushed the detail route and
   unmounted the dialog before it could be submitted. Fixed centrally: a click
   originating in a `button`/`a`/`input` no longer triggers row navigation, and the
   row is now keyboard-activatable as well.
2. **Write controls were visible to read-only roles.** The header's **Add** button
   was gated but each row's Edit/Deactivate cell was not, so a `CASHIER` was shown
   controls that would only ever 403. The action cells are gated now.
3. **The "All" filter did not exist on the server.** See the section above.
4. **Lists did not refresh after a delete or deactivation.** `useDelete`'s
   `onSuccess` closed the dialog and nothing else, so the table kept showing a row
   the server had already retired. Every list screen now reloads; `useReactivate`
   already did.
5. **Deactivation could not be undone from a list.** Same missing reload: the row
   stayed visible with no **Reactivate** control to click.

### Verification gates as they stand

| Gate | Command | Result |
|------|---------|--------|
| Backend tests | `./mvnw -o test` | **94/94 pass** |
| Frontend types | `npm run typecheck` | clean |
| Frontend lint | `npm run lint` | 0 errors (19 warnings, all pre-existing patterns) |
| Theme tokens | `npm run verify:theme` | 8/8 |
| Login screen | `npm run verify:ui` | 27/27 |
| Shell and navigation | `npm run verify:shell` | 34/34 |
| Auth and RBAC | `npm run verify:auth` | 25/25 |
| Catalogue and user CRUD | `npm run verify:catalogue` | 46/46 |
| `activeOnly` tri-state, live | `curl` against the running API | absent → both, `true` → active, `false` → deactivated |

`verify:catalogue` drives the real UI in Chrome against the real API: it creates
records, reads them back by id and by deep link, edits them, deactivates,
reactivates, and asserts the failure states (unknown id, no search matches). It
writes a screenshot and the page text to `frontend/.verify-logs/` on any failure,
because "FAIL" on its own does not say what was actually on screen. Rows it
creates are removed in a `finally` block.

`ActiveOnlyFilterTest` deserves a note: the filter was **inverted** at one point
and every other test still passed, because a mocked `CriteriaBuilder` accepts
whatever predicate it is handed and never evaluates it. It runs against real
PostgreSQL through the real service and counts the rows that come back, and it was
mutation-checked — restoring the broken expression fails exactly the one test that
describes it.

### Four defects found by running it, not reading it

Worth recording because each looked correct in source and did not work:

1. **`.dark` was purged out of the compiled CSS.** Tailwind v3 purges class
   selectors absent from the `content` globs, and no `.tsx` mentioned `dark`, so
   the entire dark token block was dropped from the build. Adding the class to
   `<html>` did nothing, the light tokens kept applying, and a "dark mode"
   screenshot came out byte-identical to the light one (same md5). Fixed with
   `safelist: ['dark']`. `verify:theme` now asserts the rule exists and that
   contrast clears WCAG AA.
2. **`ToastContainer` was mounted inside `AppShell`**, which only renders for
   authenticated routes. Unit 02 needs to raise a toast when login fails, and
   the login page is outside the shell — so the one case that mattered most
   would have been a silent no-op. Moved to the app root, above the router.
3. **Login validation was unreachable.** The spec demanded a permanently
   `disabled` submit button *and* "submitting an empty form shows both field
   errors" *and* "Enter submits the form". A disabled submit button also blocks
   implicit submission — confirmed in Chrome, Enter produced zero errors and no
   request, now asserted in `verify:ui`. With `mode: 'onSubmit'` the Zod rules
   were dead code.
   Changed to `mode: 'onTouched'`, which reaches the same rules through a path a
   user can take, and corrected the contradiction in `01d`.

4. **The disabled button outlived its unit.** The `disabled` attribute and the
   no-op `onSubmit` were correct for unit 01, which has no auth endpoint, and
   both were left behind when unit 02 shipped the backend. The login page
   rendered, validated, and reported a reachable backend — so every existing
   check passed while sign-in was impossible to perform. The gap was structural:
   `verify:ui` only ever submitted *invalid* input (a disabled button cannot
   send a real request) and `verify:shell` seeded the Zustand store directly
   rather than signing in, so no check spanned the login call itself.
   Both scripts are fixed, and `scripts/verify-auth.mjs` now covers the path
   that was untested: a real credential exchange, the HttpOnly cookie, roles
   populated from `authorities`, a session surviving a hard refresh, a
   server-side 403 for a CASHIER, and a sign-out that does not come back.
4. **`tailwindcss-animate` was installed but not registered.** `dialog.tsx` uses
   `animate-in`/`animate-out`, so dialogs had no transition. Registered the
   plugin; CSS grew 20.58 kB → 22.61 kB.

Two more failures were *test* bugs rather than app bugs, and are noted because
both had previously been written as passing assertions: a hardcoded `rgb()`
expected value typed from memory, and reading the role badge out of a closed
dropdown's `textContent`. Neither would have caught a real regression.

### Resolved risk — dual database (decisions 18/21, closed by 22)

Decisions 18 and 21 committed to running PostgreSQL in development and MySQL 8.0.16
in production from parallel Flyway trees. **Decision 22 reversed that.** The table
that used to live here — the nine-row comparison of how the two engines diverge on
isolation, search, soundex, `CHECK`, UUID storage, `DATETIME` vs `TIMESTAMP`,
collation, and `ON UPDATE` — is deleted rather than archived, because every row
described a problem that no longer exists.

What it cost, recorded so it is not rediscovered:

- `CHAR(64)` reported as `bpchar`; `citext` reported as `Types#OTHER`. Both are
  PostgreSQL-side surprises that MySQL never produced, because MySQL was the
  engine that needed the workaround in the first place.
- `MigrationParityTest` existed to keep two trees honest and grew to check
  "identical object names, tolerating different types" — a rule that reads as
  over-cautious in isolation and was genuinely defensible only because the
  alternative was two production schemas.
- V2 and V3 exist solely to repair decisions made inside V1. Both are
  retained, applied, and load-bearing for schema history, but neither
  represents planned work.

What decision 22 bought, beyond simplicity:

| Concern | Before (two engines) | Now (PostgreSQL only) |
| :--- | :--- | :--- |
| FEFO concurrency | `FOR UPDATE` verified under `READ COMMITTED`, shipped under `REPEATABLE READ` | One isolation level, one behaviour. The concurrency test is meaningful. |
| FEFO index | Plain composite index with a residual predicate | True **partial index** — only dispensable rows are in it (`MDD.md` §3.5) |
| Search | `FULLTEXT` + `SOUNDEX`, no trigrams | `pg_trgm` + `tsvector` — typo tolerance was the reason PostgreSQL was picked in v1 |
| `CHECK (quantity_available >= 0)` | Needed MySQL 8.0.16+, silently ignored below | Unconditional |
| `UuidConverter` | 21 tests, no production caller | Deleted |
| Migration guard | Parity across two trees, mutation-checked | `MigrationGuardTest` — versions, headers, no credentials |

**Rule that follows:** a check that touches storage states the engine it ran on.
With one engine that is a formality, kept because the next person may add a second
and because it costs one word to write.

### Not verified

- **No visual review.** Screenshots are written to a temp path and the model used
  cannot read images, so layout, spacing and colour balance are **unreviewed by
  eye**. The DOM, token and contrast assertions pass; the design does not. A human
  should look at `frontend/scripts/screenshots.mjs` output before Unit 02.
- **Bundle is 179 kB gzip** with a Vite warning above 500 kB raw. Acceptable for
  a shell, but it grows with every feature unit. Revisit at Unit 04.
- ~~`UuidConverter` byte-order mismatch~~ — **resolved by decision 19, then made
  moot by decision 22.** The converter and its 21 tests are deleted; native
  `uuid` needs neither the swap nor the conversion.
- ~~`ddl-auto: validate` has never run~~ — **resolved.** `User` and
  `RefreshToken` are mapped and the application boots against a freshly migrated
  PostgreSQL 16 database with `ddl-auto: validate`. Every migration from here is
  verified the same way, which is the only check that proves a migration and its
  entity agree.

---

## Dependent Tools & Versions

| Tool | Version | Path | Notes |
|------|---------|------|-------|
| Git | 2.54.0 (Apple) | `/usr/bin/git` | |
| Docker | 29.7.2 | `/usr/local/bin/docker` | Available; not yet used by this module |
| Node.js | v26.3.1 | — | Frontend toolchain |
| npm | bundled with Node 26 | — | |
| Java | 21.0.11 (Temurin LTS) | — | Matches `pom.xml` `<java.version>21` |
| Maven | 3.9.16 | `/opt/homebrew/Cellar/maven/3.9.16` | Wrapper `mvnw` also present in `Pharmacy/` |
| Python | 3.14.5 | — | Not used by this module |
| **PostgreSQL** | **16.14** | `/opt/homebrew/bin/psql` | Sole engine (decision 22). DB `pharmacy`, user `pharmacy`/`pharmacy`. **The role has no `CREATEDB`** — drop/recreate the database as a superuser via `psql -d postgres`. |
| Tesseract | — | — | Needed from Unit 10 (OCR). Verify `tesseract --version` before that unit. |
| Homebrew | 6.0.21 | `/opt/homebrew/bin/brew` | Installed PostgreSQL. |

**Note:** the MySQL server and the old `pharmacy` MySQL database may still be installed locally from decisions 1/16. Nothing in this module references them. Removing the server itself is left to the user — it is not part of the codebase.

---

## Build status

### Backend

| Unit | Name | Status | Tables | APIs | Spec |
|------|------|--------|--------|------|------|
| 01 | Infrastructure & App Foundation | ✅ Verified | — (no business tables) | 1 (ping) | `specs/01-infrastructure.md` |
| 02 | Identity, RBAC & Audit Actor | ⬜ Not started | `users`, `refresh_token` | — | `specs/02-auth-rbac.md` |
| 03 | Master Catalogue | ⬜ Not started | `manufacturer`, `medicine_catalogue`, `non_medical_item`, `supplier` | — | `specs/03-master-catalogue.md` |
| 04 | Catalogue Frontend | ⬜ Not started | — | — | `specs/04-catalogue-ui.md` |
| 05 | Inventory Batches & Stock Ledger | ✅ Done | `inventory_batch`, `stock_transaction` (V5 applied) | 25 tests, 4 mutations caught | `specs/05-inventory-batches.md` |
| 06 | FEFO Allocation Engine | ⬜ Not started | — | — | `specs/06-fefo-engine.md` |
| 07 | Inventory Frontend | ⬜ Not started | — | — | `specs/07-inventory-ui.md` |
| 08 | Purchase Entry | ⬜ Not started | `purchase_order`, `purchase_order_item` | — | `specs/08-purchase-entry.md` |
| 09 | Alerts & Cycle Count | ✅ Done | `cycle_count`, `cycle_count_line` (V6 applied) | 206 backend + 44 `verify:alerts` | `specs/09-alerts-and-cycle-count.md` |
| 10 | OCR-Assisted Purchase Entry | ⬜ Not started | — | — | `specs/10-ocr-purchase.md` |
| 11 | Purchasing Frontend | ⬜ Not started | — | — | `specs/11-purchasing-ui.md` |

### Frontend

| Unit | Name | Status | Spec |
|------|------|--------|------|
| — | Scaffolded as part of Unit 01 | ✅ Verified | `specs/01-infrastructure.md` |
| 04 | Catalogue screens | ⬜ Not started | `specs/04-catalogue-ui.md` |
| 07 | Inventory screens | ⬜ Not started | `specs/07-inventory-ui.md` |
| 11 | Purchasing + OCR screens | ⬜ Not started | `specs/11-purchasing-ui.md` |

Granular per-component and per-endpoint status lives in `layer-tracker.md`.

---

## API Inventory

One endpoint exists. `PingController` is a temporary diagnostic for the unit 01 integration gate and is **removed in Unit 02** once `/auth/login` can carry that role.

| # | Method | Path | Auth | Role | Status | Unit |
|---|--------|------|------|------|--------|------|
| 1 | GET | `/api/v1/system/ping` | none | none | ✅ Verified | 01 |
| 2 | GET | `/actuator/health` | none | none | ✅ Verified | 01 |

Both confirmed against a running backend on port 8081 with the database attached. `/api/v1/system/ping?fail=true` returns 400 and an unmapped path returns 404, both as `ApiError` envelopes — that is the error handler working, not two extra endpoints.

---

## Completed

- **Phase 0 — Pre-flight environment check.** All tools verified present. Database gap recorded and later resolved.
- **Phase 0a — Pre-existing project audit.** `Pharmacy/` catalogued: bare scaffold, nothing to preserve but `pom.xml` and the `com.greedit.pharmacy` package.
- **Phase 1 — Planning conversation.** Sign-off obtained on database, framework version, v1 scope, frontend sequencing, HMS integration, and developer-machine mode.
- **Phase 1b — Design doc reconciliation.** `MDD.md` rewritten to v2.0: Spring Boot 4.0.8, filled-in `users` and `supplier` tables, explicit v1 scope, and a §9 recording what is borrowed from SCMS and what is rejected. (The v2.0 engine choice was MySQL; decision 22 has since reverted it and §6 is back on `pg_trgm`.)
- **SCMS reference study.** Backend (`apps/core-service`) and frontend (`apps/web-internal`) conventions extracted in detail — entity/DTO/controller/service idioms, the FEFO implementation, AG Grid setup, Zustand stores, RHF+Zod forms.
- **Phase 2 — Six context files.** `project-overview.md`, `architecture-context.md`, `ui-context.md`, `code-standards.md`, `ai-workflow-rules.md`, `progress-tracker.md` written, plus supplemental `layer-tracker.md`.
- **Phase 2b — `AGENTS.md` entry point.** Global rules, three agent personas, a context-switching protocol, and a table of deliberate departures from SCMS so its conventions are not copied by accident.
- **Phase 3 — Build plan and specs.** `specs/00-build-plan.md` with 11 units, a dependency graph, effort estimates, a migration plan, and per-phase verification gates. `specs/01-infrastructure.md` with sub-specs `01a`–`01d`, and `specs/02-auth-rbac.md` with sub-specs `02a`–`02e`.

## In Progress

- **Unit 09 — alerts and cycle count. Complete, frontend and backend verified.**
  The spec's headline claim is that these were three inert links, and they were:
  the three nav entries had no route and no endpoint behind them. The backend is
  now done and verified — `206/206` tests, including 18 new cycle-count tests and
  10 new alert-query tests — with the caveats that Unit 11's work is parked below
  and that the low-stock severity threshold and the count-selection contract were
  settled this session (open question #12 records what was deliberately left open).

  What exists: `V6` (two tables), the two alert queries, the count lifecycle with
  a system-quantity snapshot per line, and `CYCLE_COUNT_ADJUST` ledger rows that
  reference the count. Verified live against a running app before any test existed:
  the RBAC split, the sale-during-a-count case that must not become a variance,
  the shortage/surplus pair, and the rollback when a variance exceeds live stock.

  The frontend shipped on the same run: the two alert screens, the count list and
  the count session, plus a new `verify:alerts` suite at 49 checks. Four defects
  surfaced that `tsc`, lint and the production build had all passed over:

  - the low-stock shortfall rendered as `7units short`, because the gap between
    the number and its unit was a margin class and not a character. Screen readers
    announced it as one word, and it is now a real space.
  - the Cycle Counts nav item carried no role gate, so a CASHIER was offered a
    link to a page the endpoint refuses with 403. The link is now gated like the
    endpoint, and `verify:shell` asserts the split in both directions.
  - `verify:shell` itself still asserted the old rule that alerts are manager-only,
    which the unit 09 RBAC decision had deliberately reversed. The suite was the
    stale artefact, not the code, so the assertion was corrected to state the
    decision rather than the assumption it was born from.
  - the count session merged a saved line into local state and left
    `uncountedLines` at its opening value, so **Post could never enable** however
    many lines were counted. It now re-reads the count after a save, because those
    counters gate the one control on the screen that writes to the stock ledger and
    so must be the server's own numbers rather than a local derivation. Locked
    down by a UI-level check in `verify:alerts`; the API was always right, which is
    exactly why only a browser test could have caught it.

  Four further failures in the new suite were its own, and are worth recording
  because each would have passed a weaker check: the batch ledger is a plain array
  and not a Spring `Page` (reading `.content` reported a correct posting as lost);
  a count covers the whole location, so `lines[0]` is whatever sorted first and
  may hold zero stock, which turns `systemQuantity - 2` into invalid input rather
  than a shortage; the run tag is digits, so a `\b`-anchored assertion on the whole
  page was matching the tag instead of the number under test; and `apiSend` hands
  back the raw `Response`, so reading `.body` yielded a `ReadableStream` that
  stringifies to `{}` and made a working endpoint look broken. That last one is why
  the suite now asserts the reference number rather than trusting the field.

  The suite closes any count it opened, including after a failure: it counts the
  outstanding lines at their system figures and posts, which writes no ledger row
  and leaves a completed session instead of an abandoned one for the next run.

  Not started: the three screens, `verify:alerts`, and the nav bump to 9.

- **Unit 11 — sales and billing.** Now the current unit, and the first work in
  this project that is genuinely out of the original v1 scope. It was requested
  explicitly, and four decisions were taken rather than guessed:
  1. **Stock is deducted.** Invoice creation runs the locked FEFO read, pins each
     `invoice_item` to the batch it came from, and posts `SALE_OUT` in the same
     transaction. This is what makes revenue tie back to stock, and it depends on
     open question #11 being settled — see below.
  2. **Prescription comes from the existing `HmsClient` seam** behind
     `MockHmsClient`. The real `practi` endpoint does not exist and `Prescription`
     is a guessed DTO (open question #2), so only the mapping changes when it
     ships. No fake contract is invented here.
  3. **GST with a configurable rate**, not a hardcoded zero. MDD §3.8 has
     `tax_amount` but no rate anywhere, so this needs a rate source and a
     rounding rule decided before the migration is written.
  4. **It lands after Unit 07's UI**, which is now done and verified.

### Corrected during Unit 06

Three things the written specifications got wrong or left contradictory, all
resolved and recorded rather than quietly decided in code. Full reasoning in
`specs/06-fefo-engine.md` §7.

- **Zero was specified twice, contradictorily.** `architecture-context.md` §5.3
  says both "zero → empty allocation list, no error" and "zero or negative →
  rejected at the boundary". Resolved as: `0` succeeds and returns nothing,
  because the preview screen starts at zero and erroring would make its initial
  state an error state; only negatives are refused.
- **`IllegalArgumentException` is not used.** `MDD.md` §4.1 and
  `architecture-context.md` §5.3 both specify it; `AGENTS.md` forbids it. Typed
  exceptions used instead. `MDD.md` §4.1's pseudocode comment is stale and should
  be corrected — recorded here rather than edited in place, since `MDD.md` is the
  schema source of truth and editing it is a deliberate act.
- **`SALE_OUT` cannot carry a legal reference type.** `stock_transaction.reference_type`
  allows only `PURCHASE_ORDER`, `INVOICE`, `RETURN` and `CYCLE_COUNT`, so a sale
  posts a NULL reference. See the open question below.

### The bug the FEFO unit caught

Dispense worked, the balances were right, and the batch still read `ACTIVE` after
falling to zero. `applyDelta` is a `@Modifying` query with `clearAutomatically = true`,
so it detaches the entity the caller is holding; the following `setStatus(DEPLETED)`
was applied to a detached instance and discarded. Every unit test passed, because
each asserted on the object it had just mutated rather than re-reading the row.
Fixed by re-reading a managed instance after each delta, and
`InventoryLedgerTest` now `flush()`es and `clear()`s before asserting so it checks
what was persisted rather than what the code intended. Worth remembering for the
rest of this project: a status change after a bulk query needs a re-read.

### Corrected during Unit 05

The expiry write-off was specified as a **zero**-delta ledger row, on the reasoning
that expired stock is not "leaving" anywhere. That argument was wrong and the
implementation follows the corrected design instead. The invariant is
`SUM(quantity_changed) = quantity_available`, and the write-off sets the balance to
`0` — so a zero delta makes the sweep manufacture a reconciliation mismatch on
every batch it touches, and leaves shrinkage uncomputable. It is now `−remaining`.
This was not a judgement call made on paper: mutating the delta back to `0` fails
`InventoryLedgerTest`, which is why the change is trustworthy rather than plausible.
See `specs/05-inventory-batches/05c-expiry-sweep.md`.
- **Backlog cleanup behind the shipped UI** (does not block anything):
  - force JWT HS256 rather than trusting the header's algorithm;
  - decide 400 vs 401 for invalid credentials and a missing refresh token, and
    carry it into the documented envelope;
  - single-flight access-token refresh with one retry, instead of every parallel
    request discovering the expired token at once;
  - remove `PingController` now that `/auth/login` exists;
  - replace catalogue substring search with the exact/prefix/trigram/soundex
    behaviour the spec describes.

## Next Up

1. **Unit 11 — sales and billing.** Spec first, then migration `V6`, then the
   API, then the screen. Three things to settle before the migration is written,
   because `V5` is immutable and a second attempt is a second guess:
   the `SALE` reference type (open question #11), the GST rate source and
   rounding rule, and the invoice number format (open question #5).
2. **Have a human eyeball the catalogue, user and inventory screens.** The automated gates
   assert behaviour and roles; nothing in them can judge layout, density, or
   whether a confirmation reads clearly on a counter display.
3. **Record the two UI deviations from `ui-context.md`**, both deliberate and both
   still undocumented: the catalogue uses a semantic `<table>` rather than AG Grid
   (not installed, and the row counts do not justify it), and `ScheduleBadge`
   reuses the `scheduled`/`otc` variants rather than the warning tint the context
   file describes. Reconcile the file or the code — they currently disagree.
4. **`ui-context.md`'s route map is stale.** It documents `/app/catalog/...` paths
   while `navigation.ts` and `App.tsx` ship flat `/medicines`, `/suppliers`,
   `/manufacturers`, `/non-medical-items`, `/settings/users`. The code is the
   source of truth; the context file needs correcting.
5. **Settle the deferred input questions** before Unit 05 needs them: whether
   users carry optional email/phone, whether brand lookup is autocomplete or a
   picker, whether item category is a picker, and whether prescription lines
   default to a required-prescription medicine.

### Two things that cost time, recorded so they are not rediscovered

1. **Flyway silently did nothing until `spring-boot-flyway` was added.** Spring
   Boot 4 moved auto-configuration out of `spring-boot-autoconfigure` into one
   module per technology. With only `flyway-core` on the classpath, the app
   **started successfully, connected to the database, reported health UP, and
   created no schema at all** — no Flyway log line, because the auto-configuration
   class was never evaluated. This is different from the well-known Flyway-10
   split (`flyway-database-postgresql`), which fails loudly. Fixed by adding
   `org.springframework.boot:spring-boot-flyway`; `pom.xml` now explains both.

2. **The schema-drift test passed while injected drift went undetected.** The
   column-parsing regex lacked `DOTALL`, so it never matched a multi-line
   `CREATE TABLE` body, returned an empty set for both trees, and
   `emptySet.equals(emptySet)` reported "no drift" forever. Found by deliberately
   adding a table to one tree and checking that the test *failed* — it did not.
   Fixed, plus a guard asserting the parser found something before it compares, so
   a broken parser can never again look like a clean result. The test is now
   mutation-checked. The MySQL-era parity checks — table, column, index, extra
   file — had no meaning once there was one tree.

---

## Seeding: deviation from the Unit 02 spec

`specs/02-auth-rbac/02a-user-entity-repository.md` asks for four seeded users,
one per role, with BCrypt hashes in the migration. **The V1 migrations contain
schema only and no user rows**, and seeding is to be done by an `IdentitySeeder`
gated on `app.seed.enabled` — true in the `dev` profile, false by default.
**This deviation is now written into 02a itself** rather than living only here, so
the spec and the code cannot disagree.

Reason: a Flyway migration runs in production exactly as it does in development,
so following the spec literally creates a `SUPER_ADMIN` with a published password
in the customer's database, on every deploy, with no way to notice from the code.
A known-password administrator is a standing remote-takeover, not a trade-off.

Every migration carries this rationale in a comment, and
`MigrationGuardTest.noCredentialsInMigrations` asserts no hash can be
reintroduced by accident. **If this is unwanted, amend the spec rather than the
guard.**

Still outstanding: `app.seed.enabled` is not yet in `application.properties`, and
`IdentitySeeder` is not yet written. Until both land, a fresh database has no
users and `/auth/login` cannot succeed for anyone.

---

## Decisions

| # | Decision | Rationale | Date |
|---|----------|-----------|------|
| 1 | ~~**MySQL 8.0**, not PostgreSQL~~ | Chosen over the MDD's original `pg_trgm` plan. Search reworked to `FULLTEXT` + `SOUNDEX`. **Superseded by 18, then by 22** — MySQL is gone entirely. | Sept 2026 |
| 2 | **Spring Boot 4.0.8** | Matches the parent `practi` HMS. MDD updated from 3.x. | Sept 2026 |
| 3 | **Java 21** | Already in the scaffold. | Sept 2026 |
| 4 | **v1 scope = 3 features**: master catalogue, batch inventory + FEFO, purchase entry + OCR | Billing, returns, full reports, PDFs and notifications deferred. See `project-overview.md` §5. | Sept 2026 |
| 5 | **Frontend ships after each backend unit** | Chosen over backend-only and over a single large frontend phase. | Sept 2026 |
| 6 | **HMS integration is a stubbed Feign client** | `practi` exposes no prescription endpoint. `MockHmsClient` is `@Primary` under `hms.mode=mock`. | Sept 2026 |
| 7 | **Single-machine mode** | No separate Developer machine. | Sept 2026 |
| 8 | **SCMS is reference material, not a template** | Pharmacy is client-facing and standalone. No modular monolith, no multi-tenancy. | Sept 2026 |
| 9 | **`BigDecimal` for all money** | SCMS uses `Double`. Floating point cannot represent `0.1`. | Sept 2026 |
| 10 | **FEFO under `SELECT … FOR UPDATE`** | SCMS's unlocked version has a concurrent-dispense race. | Sept 2026 |
| 11 | **Self-issued JWT, no Keycloak** | FDD §1 requires standalone operation. SCMS needs a realm and six containers. | Sept 2026 |
| 12 | **Flyway + `ddl-auto: validate`** | SCMS uses hand-run SQL and `ddl-auto: update`, which hides drift. | Sept 2026 |
| 13 | **React 19 + Zustand, no React Query** | `ui-context.md` §8. SCMS proves plain `ApiClient` suffices for this screen count. | Sept 2026 |
| 14 | **Pharmacy teal as `--primary`** | SCMS's near-black primary reads as an admin console, not a pharmacy. | Sept 2026 |
| 15 | **`cost_price` omitted for Cashier** | FDD §2. Cannot be expressed with `@PreAuthorize` alone, so the response mapper varies by role. | Sept 2026 |
| 16 | ~~**MySQL via Homebrew native install, not Docker**~~ — **superseded by 22** | User's choice at the time. No `docker-compose.yml` in v1, and PostgreSQL is installed the same way. The principle (no Docker) still stands. | Sept 2026 |
| 17 | **Refresh cookie `secure` flag is env-gated** | `app.cookie.secure` defaults `true`; the dev profile sets `false`. A `Secure` cookie is dropped over plain HTTP, so without this the refresh path looks broken in local dev. Production keeps the secure default. | Sept 2026 |
| 18 | ~~**Dual database: PostgreSQL for dev, MySQL for production**~~ — **superseded by 22** | User's choice, replacing decision 1. The parent HMS `pacti` runs PostgreSQL, so dev-stack parity is the motivation. MySQL stays the production target. `MDD.md` §3 rewritten to vendor-neutral conventions, with per-dialect DDL under `db/migration/postgresql/` and `db/migration/mysql/`. **The accepted risk is recorded below and is real — do not read this decision as "it works the same on both".** | Sept 2026 |
| 19 | ~~UUID canonical big-endian on both engines; the `UUID_TO_BIN(uuid, 1)` swap is dropped~~ — **moot after 22** | Forced by decision 18. Hibernate maps `java.util.UUID` to Postgres `uuid` and MySQL `binary(16)` natively, both canonical big-endian. The MySQL swap flag existed only for time-ordered inserts, which does not apply to v4 random UUIDs, so nothing is lost — and one canonical layout means one convention for migrations, ad-hoc SQL and the JPA layer. | Sept 2026 |
| 20 | **Schema owned by Flyway, seeded by code** | Supersedes the seed-INSERT instruction in sub-spec 02a. A migration runs in production as well as development, so seeding a `SUPER_ADMIN` with a published password there creates a standing remote-takeover. `V1` is schema-only; `IdentitySeeder` is gated on `app.seed.enabled`. `MigrationGuardTest.noCredentialsInMigrations` blocks the regression. | Sept 2026 |
| 21 | ~~**Name parity required across engines; type parity is not**~~ — **superseded by 22** | User's call, after two `ddl-auto: validate` failures (`CHAR`→`bpchar`, `citext`→`Types#OTHER`) that were fixed by diverging rather than by matching types. Both engines store `varchar` and both enforce case-insensitive usernames, via a `lower()` functional index on PostgreSQL and a collation on MySQL. `MigrationParityTest` checked names only and declared engine-specific migrations; **types were verified by booting each engine**, which is the gate that actually found both defects. | Sept 2026 |
| 22 | **PostgreSQL 16 only — MySQL support deleted** | User's call, reversing decision 18. Removes the MySQL JDBC driver, `flyway-mysql`, the `mysql` profile, the second Flyway tree, `UuidConverter` and `MigrationParityTest`. **Not only a simplification:** the FEFO index becomes a true *partial* index (PostgreSQL supports partial indexes, MySQL did not), `CHECK` constraints become unconditional, and search returns to the `pg_trgm` + `tsvector` plan the v1 draft originally chose PostgreSQL for. Verification changed shape too — "boots on both engines" becomes "boots on the one engine", which is the check that actually proves an entity matches its migration. | Sept 2026 |

---

## Open Questions

Each of these blocks work. Answer before the unit that needs it.

| # | Question | Blocks | Owner | Status |
|---|----------|--------|-------|--------|
| 1 | **MySQL provisioning resolved** — Homebrew native install, not Docker. Installing now. | — | User | ✅ Resolved (decision 16) |
| 2 | **The HMS Feign contract is a guess.** `practi` has no `GET /api/v1/appointments/{id}/prescription`. The `PrescriptionDTO` shape in `MDD.md` §4.3 is inferred from FDD §3.4 and is expected to change. No v1 unit calls it. | Billing (post-v1) | User | ⬜ Open — low priority |
| 3 | **Is `quantity_available` the only quantity that matters?** The MDD draft had `quantity_received` alongside it. Confirm whether a `reserved` concept is needed for IPD ward requisitions, which would add a third column. | Unit 05 | User | ⬜ Open |
| 4 | **Tax treatment on purchase orders.** `purchase_order` has `subtotal`, `tax_amount`, `grand_total` but no tax rate. GST-inclusive or exclusive? Is a rate table needed? | Unit 08 | User | ⬜ Open |
| 5 | **Invoice number and PO number format.** Currently free-text `VARCHAR(50)`. A generated sequence would be better for audit. Confirm the required format. | Units 08, (billing) | User | ⬜ Open |
| 6 | **Seed data volume.** Proposed: ~15 medicines across dosage forms and both schedule classes, 3 suppliers, 2 manufacturers, 4 users (one per role), and ~6 inventory batches deliberately arranged so FEFO has a multi-batch split to demonstrate. Confirm sufficient. | Unit 01 | User | ⬜ Open |
| 7 | **No in-app password reset.** Unit 02 has no `POST /auth/forgot-password`. A forgotten SUPER_ADMIN password has no in-app recovery, and this is a single-machine deployment. Acceptable for v1, or is a CLI reset script needed? | Unit 02 | User | ⬜ Open |
| 8 | **Access token held in memory only.** No React Query, no `localStorage`, per `ui-context.md` §8. Silent restore from the refresh cookie is now implemented, so a hard refresh no longer logs anyone out and the guards show a splash instead of redirecting. **Remaining gap:** `ApiClient` treats a 401 as terminal and logs out, so a 15-minute token expiring *mid-session* ends the shift's session rather than refreshing once and retrying. Single-flight refresh-and-retry in `ApiClient` is the fix. Acceptable for v1, or should this land before Unit 03? | Unit 02 | User | ⬜ Open |
| 9 | **Auth failures answer 400, not 401.** A rejected sign-in, a missing refresh cookie, and a deactivated account all return `400 BUSINESS_RULE_VIOLATION`. For a credential failure, 401 is the more correct REST answer, and 400 makes the browser log a console error on every anonymous cold load. The identical message for unknown-user and wrong-password is deliberate and must be kept. Changing the *status* does not require changing the message. `verify:ui`/`verify:shell` currently subtract these expected 400s rather than ignore them. Change now, or leave until the billing unit? | Unit 02 | User | ⬜ Open |
| 10 | **`SessionUser.authorities` is an untyped `string[]`.** The backend sends Spring `ROLE_`-prefixed authorities and the frontend strips the prefix in `toUser`. A future role the build does not recognise is dropped rather than trusted, which fails closed — but it also means a new backend role silently hides nav items until the frontend knows it. Should the wire contract carry `Role[]` unprefixed instead, so an unknown value is a visible contract break rather than a quiet omission? | Unit 02 | User | ⬜ Open |
| 11 | **A sale has no legal `reference_type`.** `stock_transaction.reference_type` is constrained to `PURCHASE_ORDER`, `INVOICE`, `RETURN`, `CYCLE_COUNT` — the procurement ledger's four. Unit 06's `dispense` posts `SALE_OUT` with a NULL reference, because no allowed value is a truthful cause of issue. It is recorded rather than papered over: posting `PURCHASE_ORDER` would be a lie in the audit trail, and the reconciliation invariant does not need the reference. When billing persists a sale, this needs a **new migration** (`V5` is applied and immutable) to add a `SALE` reference type, plus a prescription reference if a sale is ever fulfilled from an HMS order. | Billing (post-v1) | User | ⬜ Open |
| 12 | **A cycle count accepts any counted figure, including an absurd one.** Verified live: counting 999 against a batch holding 45 posted a +954 variance, which is arithmetically correct and operationally absurd. There is no plausibility threshold and no second signature. A negative variance *is* bounded — it cannot exceed the live balance, because the ledger enforces non-negative stock. Deliberately left undecided: a threshold is a business judgement (how many units, or what share of the snapshot, before a manager must countersign) and inventing one here would be a policy the pharmacy never agreed to. Revisit before the cycle-count screen ships. | Unit 09 | User | ⬜ Open — acknowledged, not blocking |

---

## Deferred

Explicitly not doing. Recorded so it is not rediscovered as an omission.

| Item | Trigger to revisit |
|------|--------------------|
| OPD billing & dispensing | HMS ships the prescription endpoint |
| IPD billing / ward requisitions | HMS exposes a ledger-posting API |
| Returns processing | Sale flow exists to return against |
| Short book, stock valuation, margin reports | Billing ships |
| Invoice PDFs (OpenPDF) | Billing ships |
| Notifications (Novu / WhatsApp) | A real outbound-alert requirement appears |
| Multi-tenancy | Only if this becomes a multi-hospital product |
| React Query | Only if a screen needs background refetch or cross-route cache reuse |
| MinIO for uploads | Only if local filesystem becomes insufficient |
| Dark mode | Only if requested — SCMS's dormant `.dark` block is deliberately not copied |
| Route-level code splitting | Only if the bundle grows past what feels slow on the target hardware |

---

## Verification Status

### Gates passed

| Gate | Engine | Result |
|------|--------|--------|
| `./mvnw -o test` | — | ✅ **94/94** — Units 01–04 |
| Catalogue/user CRUD, real browser + real API | `npm run verify:catalogue` | ✅ **46/46** |
| Auth shell and RBAC | `npm run verify:auth` | ✅ **25/25** |
| `activeOnly` tri-state | live `curl` | ✅ absent/`true`/`false` each return the documented set |
| Flyway applies V1→V3 to an empty database, app boots, `/actuator/health` UP | **PostgreSQL 16.14** | ✅ 3 migrations applied, `ddl-auto: validate` accepted `User` + `RefreshToken`, health `UP` |
| `MigrationGuardTest` detects injected drift | — | ✅ credential `INSERT` fails; restore passes |
| Case-insensitive username collides | **PostgreSQL** | ✅ `UNIQUE INDEX … ON users (lower(username))` rejects `Admin` vs `admin` |

The suite went 57 → 34 because `UuidConverterTest` (21 cases) and
`MigrationParityTest` (5 cases) were deleted. That is 26 tests removed and
**nothing replaced** — the count is lower because there is less code, not because
coverage quietly disappeared. Re-run `./mvnw clean test` after Unit 02 lands; the
auth stack should take the count well above 34 again.

### Gates deferred

| Gate | Why |
|------|-----|
| FEFO concurrency test under contention | Needs Unit 05/06. Single-engine now, so the existing `FOR UPDATE` rationale in `MDD.md` §4.1 can be tested honestly rather than on one engine and assumed on another. |
| `pg_trgm` extension install by a non-superuser | Not yet needed. `citext` is the same class of problem: the local `pharmacy` role owns its database so it can install extensions, but a restricted production role may need an admin to run `CREATE EXTENSION pg_trgm` once. Raises at Unit 03. |

### Invariant checks

| Invariant | Check | Last run | Result |
|-----------|-------|----------|--------|
| Ledger reconciliation returns zero rows | `SUM(quantity_changed) == quantity_available` per batch | Sept 2026 | ✅ `/inventory/reconciliation` returned `0` mismatches after live FEFO dispensing |
| Cashier receives no `cost_price` | Inspect response body as `CASHIER` | Sept 2026 | ✅ No `costPrice` key in the dispense response or batch list; CASHIER dispensed 201, quarantine 403 |
| FEFO edge-case suite | `FefoAllocatorTest` 28 + `FefoServiceTest` 26 + `FefoQueryPlanTest` 5 | Sept 2026 | ✅ 178/178 backend green; mutation-checked against 8 reverted defects, all caught |
| Cost redaction reaches the screen, not just the API | `verify:inventory`, cashier vs manager | Sept 2026 | ✅ Cashier's cost cell renders an em dash while the selling price still renders; API omits the key for a cashier |
| A shortfall is an answer, not an error | `verify:inventory` | Sept 2026 | ✅ `Short by 10` in the table, no error state, dispense disabled with a reason |
| Two concurrent dispenses cannot both win | One batch, N threads, total exceeding stock | Sept 2026 | ✅ 4 simultaneous requests for 10 units of 10 → one 201, three `INSUFFICIENT_STOCK`, no negative balance |
| An entity matches its migration | Boot with `ddl-auto: validate` | Sept 2026 | ✅ `User` + `RefreshToken` validated against V1 |
| A migration cannot contain a credential | `MigrationGuardTest.noCredentialsInMigrations` | Sept 2026 | ✅ mutation-checked |
| Migration versions are contiguous | `MigrationGuardTest.contiguousVersions` | Sept 2026 | ✅ V1–V3, no gaps |

Dropped from this table: "Both engines migrate in lockstep" and the
`UuidConverter` vector check. Both tested code that decision 22 deleted.

---

## Notes

- The workspace git repo (`/Users/rockerss/ws`) is a shared monorepo containing many unrelated projects. `Pharmacy/` was added recently and is still untracked in places. Be deliberate with `git add` — stage only `Pharmacy/` paths.
- `practi/frontend` belongs to the HMS, not this module. The PMS UI goes in `Pharmacy/frontend/`.
- `Pharmacy/src/main/resources/templates/` and `static/` are empty scaffold leftovers. Delete them in Unit 01 — this is a SPA + API, not a server-rendered app.
