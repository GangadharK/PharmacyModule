# Layer Tracker — Pharmacy Management System (PMS)

Granular status per component and per endpoint. Rolls up into `progress-tracker.md`.

**Status key:** ⬜ Not started · 🔄 In progress · ✅ Done · ⚠️ Awaiting verification · ⛔ Blocked

**Spec files:** `specs/00-build-plan.md` (unit sequence) · `01-infrastructure.md` + `01a`–`01d` · `02-auth-rbac.md` + `02a`–`02e`. Units 03–11 are named in the build plan but not yet specified.

---

## Layer Tracker — Database

| Migration | Tables | Status | Spec | Notes |
|-----------|--------|--------|------|-------|
| `V1__create_identity.sql` | `users`, `refresh_token` | ✅ Done | 02 | Single tree, `db/migration/`. Applied to an empty PostgreSQL 16 database and verified by booting with `ddl-auto: validate`. Guarded by `MigrationGuardTest` (mutation-checked): contiguous versions, documented headers, no credentials, no user `INSERT`. Schema only — no user rows. |
| `V2__token_hash_varchar.sql` | — (`refresh_token.token_hash`) | ✅ Done | 22 | Schema-only fix, depends on V1. `char(64)` reports as `bpchar` and fails `ddl-auto: validate`. |
| `V3__username_case_insensitive.sql` | — (`users.username`) | ✅ Done | 22 | Drops the plain `UNIQUE`, adds `UNIQUE INDEX uk_users_username ON users (lower(username))`. See the `citext` note in `MDD.md` §3.9. |
| `V4__master_catalogue.sql` | `manufacturer`, `medicine_catalogue`, `non_medical_item`, `supplier` | ✅ Done | 03 | Applied and validated. Tracker had said "not started" — corrected against `flyway_schema_history`, which is the authority. |
| `V5__inventory_batches.sql` | `inventory_batch`, `stock_transaction` | ✅ Done | 05 | Applied and validated. Partial FEFO indexes confirmed present in `pg_indexes` with their `WHERE` predicates, not merely their names. All ten `CHECK`/`FK` guards exercised directly against the database and each refused the bad row. `chk_txn_changed` still *permits* zero for `EXPIRY_WRITE_OFF` by design — see `05c-expiry-sweep.md`. |
| `V6__cycle_counts.sql` | `cycle_count`, `cycle_count_line` | ✅ Done | 09 | Applied and validated by booting with `ddl-auto: validate`. The `system_quantity` snapshot is the point of the unit: a variance must be measured against what the books claimed when counting began, not at post time, or a sale during a count becomes a phantom discrepancy. Header states its dependencies per `MigrationGuardTest`; the first attempt referenced `app_user` and was corrected to `users` before it was ever applied. |
| `V6__create_purchase_orders.sql` | `purchase_order`, `purchase_order_item` | ⬜ Not started | 08 | |
| `V7__create_invoice_skeleton.sql` | `invoice`, `invoice_item` | ⬜ Not started | 08 | **Schema only** — no v1 code writes here (`MDD.md` §8). |
| `V8__seed_reference_data.sql` | lookup tables | ⬜ Not started | 03 | Reference data (units, locations) only. **Users are never seeded here** — that is `IdentitySeeder`, code-gated by `app.seed.enabled`. Whether reference data belongs in a migration at all is an open question; see the accepted-risk table in `progress-tracker.md`. |

**Schema invariants to verify once the migrations land:**

| Check | Status | How it was verified |
|-------|--------|--------------------|
| `quantity_available >= 0` enforced by `CHECK` | ✅ | `chk_batch_qty` rejected a direct `INSERT` of `-5`. Independently, mutating the `applyDelta` post-condition out of the query still failed the suite via this constraint — two guards, not one. |
| Exactly one of `medicine_id` / `item_id` on `inventory_batch` | ✅ | `chk_batch_polymorphic` rejected both the both-set and neither-set cases. |
| `SUM(quantity_changed) == quantity_available` per batch | ✅ | Enforced by `InventoryLedgerTest` after every mutating path, and **mutation-checked**: removing `adjust`'s ledger row fails 3 tests. |
| Every FK column indexed | ✅ | `fk_txn_batch`, `fk_batch_medicine`, `fk_batch_supplier` all carry indexes. |
| FEFO partial index on `(medicine_id, expiry_date)` | ✅ | `idx_fefo_search` and `idx_item_fefo` confirmed in `pg_indexes` **with** `WHERE status = 'ACTIVE' AND quantity_available > 0`. A partial index without its predicate is a full index with extra steps. **Used or not depends on the status filter being a literal** — a bound `status` parameter cannot be proven to satisfy a partial predicate, and the planner falls back to a `Seq Scan`. See `specs/06-fefo-engine.md` §4.1; `FefoQueryPlanTest` guards it. |
| `FULLTEXT` index on `medicine_catalogue.search_blob` | ⬜ |
| `soundex_code` generated column populated | ⬜ |
| `ddl-auto: validate` passes (Hibernate agrees with migrations) | ⬜ |

---

## Layer Tracker — Backend services

| Service | Status | Spec | Verify checklist |
|---------|--------|------|------------------|
| ~~`UuidConverter`~~ | 🗑️ **Deleted** | 22 | Existed only to bridge MySQL's `BINARY(16)`. Decision 22 removed MySQL, so the converter had no caller — and its 21 tests were 3% of the suite testing dead code. Native `uuid` needs no converter. |
| `GlobalExceptionHandler` | ✅ Done — 20 tests | 01 | Every exception type maps to the right status and a non-null `code`; 500 message masked |
| `JwtService` | ✅ Done — 11 tests | 02 | Issues and validates access + refresh tokens; rejects expiry and tampering |
| `AuthService` / user management | ✅ Done — 13 tests | 02 | BCrypt hashing; refresh rotation; no password in any response |
| `CurrentUserContext` | ✅ Done | 02 | Populated by the filter; cleared in `afterCompletion` |
| `MedicineService` | ✅ Done — 10 tests | 03 | CRUD; `search_blob` maintained on every write. ⚠️ Search is still a substring match; the soundex/trigram cascade is not built (see `SearchService`). |
| `SupplierService` | ✅ Done | 03 | CRUD; unique name enforced; no hard delete |
| `ManufacturerService` | ✅ Done | 03 | CRUD; hard delete refused while a medicine references the row |
| `NonMedicalItemService` | ✅ Done | 03 | CRUD; `category` and `unit` immutable after create |
| `activeOnly` list filter | ✅ Done — 4 tests | 03 | Tri-state: `true` active, `false` deactivated, **absent = no filter**. Tested against real PostgreSQL through the real service and mutation-checked. |
| `SearchService` | ⬜ Not started | 03 | Exact → boolean-prefix → soundex cascade, each with its confidence |
| `InventoryBatchService` | ✅ Done — 25 tests | 05 | Every mutation writes a `stock_transaction` in the same transaction; no `DELETE`. Ledger invariant mutation-checked 4 ways. Cost redaction mutation-checked. |
| **`FefoAllocator`** | ✅ Done — 28 tests | 06 | **Pure function. No Spring, no repository, no clock. Constructed with `new` in its test.** Returns `AllocationResult{allocations, totalAllocated, shortfall}`; `requireComplete` is what turns a short list into the typed `InsufficientStockException`, so preview can report a shortfall and dispense can refuse one from the same arithmetic. |
| `InventoryBatchService` FEFO | ✅ Done — 26 tests | 06 | `previewFefo` unlocked, `issueStock` locked, one `@Transactional`. Re-reads a managed entity after every delta — required, because `applyDelta`'s `clearAutomatically` detaches the caller's instance and a status set on it is silently lost. |
| `CycleCountService` | ✅ Done — 18 tests | 09 | OPEN → POSTED, with a `system_quantity` snapshot per line taken at session creation. A sale during a count is not a variance; absorbing one would destroy the only signal a count produces. `batchIds` and `medicineIds` are both accepted and unioned, deduplicated. Negative variances are bounded by the ledger's non-negative check; positive ones are not — see open question #12. |
| `InventoryAlertRepository` | ✅ Done — 10 tests | 09 | Two native queries. Low stock sums ACTIVE batches per medicine and per non-medical item, because the reorder level is a claim about the item, not about a batch. Near expiry filters by **status**, not date: a past-date ACTIVE batch is still listed, because it still needs writing off. |
| `InventoryAlertController` | ✅ Done | 09 | `GET /inventory/alerts/low-stock`, `GET /inventory/alerts/near-expiry`. Near-expiry window defaults to 90 days and is capped at 365. Read for every role; the cost redaction rules do not apply because neither response carries a cost price. |
| `CycleCountController` | ✅ Done | 09 | `GET/POST /inventory/cycle-counts`, `GET/PATCH .../{id}`, `POST .../{id}/post`. `@PreAuthorize("hasAnyRole('MANAGER','SUPER_ADMIN')")` on every method — a count is an accusation that the books were wrong, and CASHIER cannot make one. |
| `AlertService` | ⬜ Not started | 09 | Low-stock and near-expiry queries correct against seeded data |
| `PurchaseOrderService` | ⬜ Not started | 08 | State machine enforced; only `APPROVED` writes stock |
| `InvoiceTextParser` | ⬜ Not started | 10 | **Pure function. Regex edge cases. No Spring, no mocks.** |
| `OcrService` | ⬜ Not started | 10 | Draft only; never writes `inventory_batch` |
| `MedicineMatcher` | ⬜ Not started | 10 | Resolves OCR names via exact → prefix → soundex; returns confidence |
| `MockHmsClient` | 🔄 In progress | 10 | ⚠️ Present as a unit 01 seam, but gated on `@Profile("!hms-live")` rather than `app.hms.mode` — will not switch on the property. Fix in unit 10. |
| `ExpirySweepJob` | ✅ Done | 05 | 02:15 daily, **disabled by default** — an automated irreversible write must not be on by default. `REQUIRES_NEW` per batch, so a poison row cannot roll back its neighbours and silently stop the whole pharmacy's expiry reporting. Writes `EXPIRY_WRITE_OFF` with `−remaining` and null `performed_by`. |

---

## Layer Tracker — Backend APIs

One diagnostic endpoint. `PingController` is temporary and is removed in unit 02.

| # | Method | Path | Auth | Role | Status | Unit |
|---|--------|------|------|------|--------|------|
| 1 | GET | `/api/v1/system/ping` | none | none | ✅ Verified | 01 |

Verified against a live backend on 8081: returns 200 `PONG`, returns a 400 `ApiError` on `?fail=true`, and an unmapped path returns a 404 `ApiError`.

*Planned inventory — to be confirmed against each unit's spec:*

Verified live on 8081 for every row marked ✅ Verified, including the negative cases
(403 for a CASHIER write, absent `costPrice` key, `INSUFFICIENT_STOCK` with the
balance and ledger both unchanged afterwards).

| Method | Path | Role | Unit | Status |
|--------|------|------|------|--------|
| POST | `/api/v1/inventory` | PHARMACY_MANAGER+ | 05 | ✅ Verified |
| GET | `/api/v1/inventory` | CASHIER+ | 05 | ✅ Verified |
| GET | `/api/v1/inventory/{id}` | CASHIER+ | 05 | ✅ Verified |
| PUT | `/api/v1/inventory/{id}` | PHARMACY_MANAGER+ | 05 | ✅ Verified |
| POST | `/api/v1/inventory/{id}/adjust` | PHARMACY_MANAGER+ | 05 | ✅ Verified |
| POST | `/api/v1/inventory/{id}/quarantine` | PHARMACY_MANAGER+ | 05 | ✅ Verified |
| POST | `/api/v1/inventory/{id}/release` | PHARMACY_MANAGER+ | 05 | ✅ Verified |
| GET | `/api/v1/inventory/{id}/history` | CASHIER+ | 05 | ✅ Verified |
| GET | `/api/v1/inventory/expiring` | CASHIER+ | 05 | ✅ Verified |
| GET | `/api/v1/inventory/reconciliation` | PHARMACIST+ | 05 | ✅ Verified |

**No delete endpoint exists for a batch, by design.** AGENTS rule 2: stock is
written off with a ledger row, never removed. A hard delete would leave a gap in the
movement history indistinguishable from "this batch never existed".

| Method | Path | Role | Unit |
|--------|------|------|------|
| POST | `/api/v1/auth/login` | public | 02 |
| POST | `/api/v1/auth/refresh` | cookie | 02 |
| POST | `/api/v1/auth/logout` | any | 02 |
| GET | `/api/v1/auth/me` | any | 02 |
| GET | `/api/v1/auth/users` | SUPER_ADMIN | 02 |
| POST | `/api/v1/auth/users` | SUPER_ADMIN | 02 |
| GET/PUT | `/api/v1/medicines` | all roles | 03 |
| GET | `/api/v1/medicines/search` | all roles | 03 |
| GET/POST/PUT | `/api/v1/suppliers` | read: all · write: MANAGER, ADMIN | 03 |
| GET/POST/PUT | `/api/v1/manufacturers` | read: all · write: ADMIN | 03 |
| GET/POST/PUT | `/api/v1/non-medical-items` | read: all · write: MANAGER, ADMIN | 03 |
| GET/POST | `/api/v1/inventory` | read: all · write: PHARMACIST, MANAGER, ADMIN | 05 |
| GET | `/api/v1/inventory/{id}` | all | 05 |
| GET | `/api/v1/inventory/{id}/history` | all | 05 |
| GET | `/api/v1/inventory/expiring` | all | 05 |
| PUT | `/api/v1/inventory/{id}` | PHARMACIST, MANAGER, ADMIN | 05 |
| POST | `/api/v1/inventory/{id}/adjust` | PHARMACIST, MANAGER, ADMIN | 05 |
| POST | `/api/v1/inventory/{id}/quarantine` | PHARMACIST, MANAGER, ADMIN | 05 |
| POST | `/api/v1/inventory/{id}/release` | PHARMACIST, MANAGER, ADMIN | 05 |
| POST | `/api/v1/inventory/fefo-preview` | all | 06 |
| POST | `/api/v1/inventory/dispense` | all | 06 |
| GET | `/api/v1/inventory/reconciliation` | MANAGER, ADMIN | 06 |
| GET/POST | `/api/v1/purchase-orders` | read: all · write: PHARMACIST, MANAGER, ADMIN | 08 |
| POST | `/api/v1/purchase-orders/{id}/approve` | MANAGER, ADMIN | 08 |
| POST | `/api/v1/purchase-orders/{id}/cancel` | MANAGER, ADMIN | 08 |
| POST | `/api/v1/purchase-orders/ocr-upload` | PHARMACIST+ | 10 |
| GET | `/api/v1/alerts/low-stock` | MANAGER, ADMIN | 09 |
| GET | `/api/v1/alerts/near-expiry` | all | 09 |
| POST | `/api/v1/inventory/cycle-counts` | PHARMACIST+ | 09 |
| GET | `/api/v1/compliance/schedule-h-register` | MANAGER, ADMIN | 09 |

---

## Layer Tracker — Frontend components

### Shared / layout

| Component | Status | Spec | Verify checklist |
|-----------|--------|------|------------------|
| `AppShell` / `MainLayout` | ✅ Done | 01 | Auth gate; only `main` scrolls — asserted in `verify:shell` |
| `Sidebar` | ✅ Done | 01 | Collapses 64↔256px (asserted); groups expand; active item highlighted; role-filtered |
| `Header` | ✅ Done | 01, wired 02 | Avatar menu, role badges, logout — asserted in `verify:shell`. Sign-out now also calls `/auth/logout`; previously it cleared local state only, leaving a valid refresh cookie. |
| `PageHeader` | ✅ Done | 01 | Title, description, actions slot |
| `ToastContainer` | ✅ Done | 01 | **Mounted at the app root and a toast actually appears** — asserted in `verify:ui`. SCMS's is never mounted. |
| `LoginPage` | ✅ Done | 01, wired 02 | Validates on blur; **submit calls `/auth/login`** and redirects to `?next=` (same-origin only). Disabled only while in flight, with a spinner. Error toast branches on `code`, never on `message`. |
| `ApiClient` | ✅ Done | 01 | Single fetch path; 401 → logout; error carries `code`. Exercised against the live backend via the Vite proxy. ⚠️ On 401 it logs out rather than attempting a silent retry, so a 15-minute access token expiring mid-shift ends the session. See progress-tracker open question 8. |
| `auth.store` | ✅ Done | 01, wired 02 | In-memory token, no web storage. `isRestoring` starts **true** so a reload does not bounce to `/login` mid-restore. |
| `ui.store` | ✅ Done | 01 | Sidebar collapse, persisted — asserted across a reload in `verify:shell` |
| `config/navigation.ts` | ✅ Done | 01, fixed 02 | Single source for sidebar + routes, so they cannot drift. Unshipped items are dimmed and are not links. `Suppliers` gained `requiresRole: 'PHARMACIST'` to match the backend, which gates `GET /suppliers` to PHARMACIST+. |
| `ProtectedRoute` / `PublicOnlyRoute` | ✅ Done | 01, wired 02 | Redirect to `/login?next=…`; preserves `next`. Both wait on `isRestoring` and show `SessionSplash`, so a reload cannot redirect on an undecided state. |
| `shadcn/ui` primitives | ✅ Done | 01 | `avatar` `badge` `button` `card` `dialog` `dropdown-menu` `input` `label` `scroll-area` `select` `separator` `textarea` `toast` `tooltip` |
| `index.css` tokens | ✅ Done | 01 | ⚠️ `.dark` needed a `safelist` entry or Tailwind purged it. `verify:theme` guards it. |

### Users (Unit 02)

| Component | Status | Verify checklist |
|-----------|--------|------------------|
| `UsersPage` | ✅ Done | ✅ Superadmin-only list; search, Active/Deactivated/All, role filter, pagination; create; edit; deactivate/reactivate. Reloads after a status change. |
| `UserDetail` | ✅ Done | ✅ Read by id and deep link; not-found vs error separated; last-sign-in shown or "Never signed in". |
| `UserForm` + `user.schema.ts` | ✅ Done | ✅ RHF + Zod. The password field is **blank on edit and omitted from the payload when untouched** — the suite signs in as the edited user afterwards to prove the stored hash survived. GSTIN is uppercased before validation. |
| Self-demotion / self-deactivation | ✅ Done | ✅ Guarded in the UI, and the server rejects it independently. |
| Route guard | ✅ Done | ✅ `RoleRoute` redirects a non-superadmin off `/settings/users`; asserted by `verify:catalogue`. |

### Catalogue (Unit 04)

| Component | Status | Verify checklist |
|-----------|--------|------------------|
| `MedicinesPage` | ✅ Done | ✅ List, search, Active/Deactivated/All + dosage/schedule filters, pagination, create, edit, deactivate/reactivate. Row actions gated to `PHARMACY_MANAGER`+. |
| `MedicineDetail` | ✅ Done | ✅ Read by id and by deep link; not-found vs error states separated; edit and deactivate gated; `dosageForm` locked on edit. |
| `MedicineForm` + `medicine.schema.ts` | ✅ Done | ✅ RHF + Zod; `dosageForm` immutable on edit; optional fields omitted rather than sent as null. |
| `ScheduleBadge` | ✅ Done, deviation | ✅ H/H1/OTC present. ⚠️ Reuses the `scheduled`/`otc` variants rather than the warning tint `ui-context.md` describes — the two disagree and one must change. |
| `SuppliersPage` / `SupplierDetail` / `SupplierForm` | ✅ Done | ✅ Read `PHARMACIST`+, write `PHARMACY_MANAGER`+; GSTIN uppercased server-side and asserted by the suite; no delete control, because no hard delete exists. |
| `ManufacturersPage` / `ManufacturerDetail` / form | ✅ Done | ✅ CRUD with **hard** delete; the server refuses while a medicine references the row, and both outcomes are covered (204, and 400 `BUSINESS_RULE_VIOLATION`). |
| `NonMedicalItemsPage` / detail / form | ✅ Done | ✅ CRUD; `category` and `unit` immutable on edit and disabled in the form. |
| Catalogue grid | ✅ Done, deviation | ✅ Semantic `<table>` via `DataTable`, not AG Grid — not installed, and catalogue lists do not need it. Row navigation ignores clicks on cell controls and is keyboard-activatable. |
| Shared list/detail machinery | ✅ Done | ✅ `useResourceScreen`, `useResourceList`, `useResourceItem`, `useCreate`, `useUpdate`, `useDelete`, `useReactivate`; explicit loading / empty / error early returns. |
| `RoleGate` / `RoleRoute` | ✅ Done | ✅ Route-level guards plus per-control gating, so a read-only role is not shown a control that would 403. |

### Inventory (Unit 07)

| Component | Status | Verify checklist |
|-----------|--------|------------------|
| `BatchesPage` | ✅ Done | Expiry-ascending; status/location/expiry-window filters; row click opens the ledger. Shared `DataTable`, **not** AG Grid — the endpoint is server-paginated so there is nothing to virtualise; see `specs/07-inventory-ui.md` §10.1. Quarantine/adjust controls deliberately deferred to Unit 09. |
| `ExpiryBadge` | ✅ Done | Countdown (`12d`) not a date; bands at 30/90/180; past 180 it renders the date. `null` expiry gets its own muted "No expiry" rather than being coerced to a calm badge. Text always present, never colour alone. |
| `FefoPreviewPage` | ✅ Done | Explicit preview button, **not** per-keystroke. A shortfall renders as an answer in the table, never an error state. No reference-type field, per `06-fefo-engine.md` §7.4. Medicine picker searches the API — a capped dropdown silently truncated the tail. |
| `FefoAllocationTable` | ✅ Done | Per-batch rows in FEFO order (never re-sorted); totals row; red `Short by N` line. |
| `CycleCountsPage` | ✅ Done | Start count (location dialog), status filter, reference search. Role-gated to `PHARMACIST` minimum in nav and route, matching the endpoint's 403. Asserted in both directions by `verify:shell` and `verify:alerts`. |
| `CycleCountSession` | ✅ Done | Full-screen dialog: system vs counted per line, expected variance shown before saving, save-on-blur. Posting is refused until every line is counted, and the refusal names how many are missing. |
| `LowStockPage` | ✅ Done | Server-paged, no client filter. Shortfall renders as `7 units short` with a real space, not `ml-1` — a margin class leaves the text as one word for a screen reader. Severity comes from the server and is spelled out, never colour alone. |
| `NearExpiryPage` | ✅ Done | 30/60/90/180/365-day window chips matching the endpoint's cap; `ExpiryBadge` reused so the countdown bands stay in one place. |
| `AlertSeverityBadge` | ✅ Done | Single renderer for both alert screens, so a stock-out cannot render `LOW` on one screen and `OUT` on the other. |
| `CostPriceCell` | ✅ Done | **Renders an em dash when the field is absent** — branches on the payload, not the role, so a withheld price is never in the client heap to leak. Verified at both the API and DOM layers by `verify:inventory`. |

### Purchasing (Unit 11)

| Component | Status | Verify checklist |
|-----------|--------|------------------|
| `PurchaseOrdersPage` | ⬜ Not started | List, filter by status, open detail |
| `PurchaseOrderForm` | ⬜ Not started | Multi-line entry with batch/expiry/qty/price per line |
| `OcrUploadPage` | ⬜ Not started | Upload image → parsed draft |
| `OcrReviewTable` | ⬜ Not started | Per-line edit; low-confidence rows flagged; correct an OCR misread |
| `OcrConfidenceBar` | ⬜ Not started | Shows value with the 0.6 threshold marked |
| `ApproveDialog` | ⬜ Not started | Manager-only; confirms stock will be created |

---

## Layer Tracker — Tests

| Test class | Status | Cases | Unit |
|------------|--------|-------|------|
| `GlobalExceptionHandlerTest` | ✅ Passing | 20 | 01 |
| `PageResponseTest` | ✅ Passing | 9 | 01 |
| `PharmacyApplicationTests` | ✅ Passing | 2 | 01 |
| `MigrationGuardTest` | ✅ Passing | 3 | 01 |
| `SpecificationsTest` | ✅ Passing | 6 | 01 |
| `AuthServiceTest` | ✅ Passing | 13 | 02 |
| `JwtServiceTest` | ✅ Passing | 11 | 02 |
| `MedicineServiceTest` | ✅ Passing | 10 | 03 |
| `CatalogueSearchTextTest` | ✅ Passing | 16 | 03 |
| `ActiveOnlyFilterTest` | ✅ Passing | 4 | 03 |
| `InventoryLedgerTest` | ✅ Passing | 25 | 05 |
| `verify:theme` (headless Chrome) | ✅ Passing | 8 | 01 |
| `verify:ui` (headless Chrome) | ✅ Passing | 27 | 01 |
| `verify:shell` (headless Chrome) | ✅ Passing | 36 | 01, 02, 09 |
| `verify:auth` (headless Chrome) | ✅ Passing | 25 | 02 |
| `verify:catalogue` (headless Chrome) | ✅ Passing | 46 | 02, 04 |
| `verify:inventory` (headless Chrome) | ✅ Passing | 37 | 07 |
| `verify:alerts` (headless Chrome) | ✅ Passing | 49 | 09 |
| **Backend total** | ✅ **178/178** | **178** | |
| `FefoAllocatorTest` | ✅ Passing | 28 | 06 |
| `FefoServiceTest` | ✅ Passing | 26 | 06 |
| `FefoQueryPlanTest` | ✅ Passing | 5 | 06 |
| `CycleCountServiceTest` | ✅ Passing | 18 | 09 |
| `InventoryAlertRepositoryTest` | ✅ Passing | 10 | 09 |
| `InvoiceTextParserTest` | ⬜ Not started | ~14 | 10 |
| `MedicineMatcherTest` | ⬜ Not started | ~8 | 10 |
| `InventoryServiceTest` | ⬜ Not started | ~8 | 05 |
| `PurchaseOrderServiceTest` | ⬜ Not started | ~7 | 08 |
| `ResponseMapperCostRedactionTest` | ⬜ Not started | ~4 | 03 |
| Controller tests (`@WebMvcTest`) | ⬜ Not started | per endpoint | per unit |

`UuidConverterTest` was deleted along with the MySQL-era `UuidConverter`, so it is
no longer in this list. The suites that replaced browser-driven coverage for the
login and shell are `verify:auth` and `verify:catalogue`; they drive the real UI
against the real API rather than asserting on component props.

**Target: every controller in the API inventory has a `@WebMvcTest` covering status codes, validation, and the three auth cases.**

Backend total: **42 tests, all passing** (`./mvnw clean test`).
Frontend gates: **228 assertions across 7 headless-Chrome suites** (`npm run verify`), plus `tsc` and `oxlint` clean: theme 8, ui 27, shell 36, auth 25, catalogue 46, inventory 37, alerts 49.

The three frontend suites need `npm run dev` and the backend both running. They drive the real app in Chrome rather than asserting on a jsdom render tree, which is why they caught four defects that type checking, linting and a production build all passed over.
