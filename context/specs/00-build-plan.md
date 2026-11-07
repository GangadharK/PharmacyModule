# Build Plan — Pharmacy Management System (PMS)

**Status:** Active
**Last updated:** September 2026

---

## Overview

Eleven build units. Each produces one visible, verifiable result. Ordered by dependency: infrastructure, then security, then backend features, each immediately followed by its own UI so the application is always runnable and demonstrable end to end.

**Unit of work:** backend and UI for one feature are separate units. This is sign-off decision 5 — the frontend ships after each backend unit rather than accumulating in one large phase.

---

## Unit Sequence

### Phase A — Foundation (Units 01–02)

| Unit | Name | Scope | Dependencies |
|------|------|-------|--------------|
| 01 | Infrastructure & App Foundation | `pom.xml` realignment to PostgreSQL, `application.yml`, Flyway, `GlobalExceptionHandler`, `ApiError`, OpenAPI, Vite + React scaffold, `AppShell`/`Sidebar`/`Header`/`PageHeader`, `ApiClient`, Zustand stores, `LoginPage`, `ToastContainer` | None |
| 02 | Identity, RBAC & Audit Actor | `users` + `refresh_token` tables, BCrypt, `JwtService`, `JwtAuthenticationFilter`, `CurrentUserContext`, login / refresh / logout / me, SUPER_ADMIN user management, seed users | Unit 01 |

### Phase B — Feature 1: Master Catalogue (Units 03–04)

| Unit | Name | Scope | Dependencies |
|------|------|-------|--------------|
| 03 | Master Catalogue (backend) | `manufacturer`, `medicine_catalogue`, `non_medical_item`, `supplier` tables; CRUD services and controllers; `search_blob` + `soundex_code`; the exact → boolean-prefix → soundex search cascade; cost-price redaction for CASHIER | Unit 02 |
| 04 | Catalogue UI | `MedicinesPage` + `MedicineForm` + `ScheduleBadge`, `SuppliersPage`, `ManufacturersPage`, `NonMedicalItemsPage` | Unit 03 |

### Phase C — Feature 2: Batch Inventory & FEFO (Units 05–07, 09)

| Unit | Name | Scope | Dependencies |
|------|------|-------|--------------|
| 05 | Inventory Batches & Stock Ledger (backend) | `inventory_batch`, `stock_transaction`; batch CRUD; quarantine / release; append-only ledger; conditional-UPDATE guard against negative stock; `ExpirySweepJob`; seeded batches | Unit 03 |
| 06 | FEFO Allocation Engine | `FefoAllocator` as a pure function, 11 edge-case tests; locked candidate read; `fefo-preview` and `reconciliation` endpoints | Unit 05 |
| 07 | Inventory UI | `BatchesPage` (AG Grid), `ExpiryBadge`, `FefoPreviewPage` + `FefoAllocationTable`, `CostPriceCell` | Unit 06 |
| 09 | Alerts & Cycle Count (backend) | low-stock and near-expiry queries, Schedule H/H1 register, cycle-count adjustment posting | Unit 05 — placed here because it depends only on the batch and ledger tables, and nothing else waits on it |

### Phase D — Feature 3: Purchase Entry & OCR (Units 08, 10, 11)

| Unit | Name | Scope | Dependencies |
|------|------|-------|--------------|
| 08 | Purchase Entry (backend) | `purchase_order`, `purchase_order_item`, `invoice`/`invoice_item` schema; DRAFT → APPROVED state machine; **only APPROVED creates inventory batches**; adjustment and cancellation write-off | Unit 05 |
| 10 | OCR-Assisted Purchase Entry (backend) | Tesseract integration, `InvoiceTextParser` (pure), `MedicineMatcher`, `ocr-upload` returning a reviewable draft; `MockHmsClient` + `HmsClient` seam | Unit 08 |
| 11 | Purchasing UI | `PurchaseOrdersPage`, `PurchaseOrderForm`, `OcrUploadPage`, `OcrReviewTable`, `OcrConfidenceBar`, `ApproveDialog` | Unit 10 |

Unit 09 is specified in Phase C but implemented in numeric order, after Unit 08's numbering was fixed. Its spec is self-contained and depends only on Unit 05.

---

## Dependency Graph

```text
Unit 01  Infrastructure & App Foundation
  └─► Unit 02  Identity, RBAC & Audit Actor
        └─► Unit 03  Master Catalogue (backend)
              ├─► Unit 04  Catalogue UI
              └─► Unit 05  Inventory Batches & Stock Ledger
                    ├─► Unit 06  FEFO Allocation Engine
                    │     └─► Unit 07  Inventory UI
                    ├─► Unit 08  Purchase Entry (backend)
                    │     └─► Unit 10  OCR-Assisted Purchase Entry
                    │           └─► Unit 11  Purchasing UI
                    └─► Unit 09  Alerts & Cycle Count (backend)
```

Units 04, 07 and 11 are the UI leaves. They are demo gates — each one makes the preceding backend work visible to a human.

---

## Implementation Order

```text
 1.  Unit 01  Infrastructure & App Foundation   (@pharmacy-infra + @pharmacy-backend + @pharmacy-frontend)
 2.  Unit 02  Identity, RBAC & Audit Actor      (@pharmacy-backend)
 3.  Unit 03  Master Catalogue                  (@pharmacy-backend)
 4.  Unit 04  Catalogue UI                      (@pharmacy-frontend)
 5.  Unit 05  Inventory Batches & Ledger        (@pharmacy-backend)
 6.  Unit 06  FEFO Allocation Engine            (@pharmacy-backend)
 7.  Unit 07  Inventory UI                      (@pharmacy-frontend)
 8.  Unit 08  Purchase Entry                    (@pharmacy-backend)
 9.  Unit 09  Alerts & Cycle Count              (@pharmacy-backend)
10.  Unit 10  OCR-Assisted Purchase Entry       (@pharmacy-backend)
11.  Unit 11  Purchasing UI                     (@pharmacy-frontend)
```

---

## Effort Estimates

| Unit | Name | Effort | Complexity | Risk |
|------|------|--------|------------|------|
| 01 | Infrastructure & App Foundation | 10 h | High | Many moving parts, but each is individually boring |
| 02 | Identity, RBAC & Audit Actor | 8 h | High | Getting roles wrong locks out every later endpoint |
| 03 | Master Catalogue | 8 h | Medium | The soundex cascade needs real tuning against seeded data |
| 04 | Catalogue UI | 6 h | Medium | The Cashier cost-redaction rule must be visible in the UI |
| 05 | Inventory Batches & Ledger | 10 h | High | Ledger reconciliation is the invariant everything else depends on |
| 06 | FEFO Allocation Engine | 6 h | High | Concurrency correctness; the edge cases are where the bugs live |
| 07 | Inventory UI | 8 h | Medium | AG Grid Community has no server-side row model — read its limits |
| 08 | Purchase Entry | 8 h | High | Approval is the only path that creates stock |
| 09 | Alerts & Cycle Count | 5 h | Medium | Near-expiry thresholds interact with the expiry sweep |
| 10 | OCR-Assisted Purchase Entry | 12 h | High | Depends on the quality of a real supplier's invoice PDF |
| 11 | Purchasing UI | 8 h | Medium | The OCR review table is the most complex screen in v1 |
| **Total** | | **89 h** | | |

---

## Database Migration Plan

| Migration | Tables | Unit | Description |
|-----------|--------|------|-------------|
| `V1__create_identity.sql` | `users`, `refresh_token` | 02 | Identity, BCrypt hashes, roles, refresh rotation |
| `V2__create_master_catalogue.sql` | `manufacturer`, `medicine_catalogue`, `non_medical_item`, `supplier` | 03 | Master data, `search_blob`, `soundex_code` generated column, `FULLTEXT` index |
| `V3__create_inventory.sql` | `inventory_batch`, `stock_transaction` | 05 | The batch and the append-only ledger, with the FEFO composite index |
| `V4__create_purchase_orders.sql` | `purchase_order`, `purchase_order_item` | 08 | PO header and lines, state machine columns |
| `V5__create_invoice_skeleton.sql` | `invoice`, `invoice_item` | 08 | **Schema only. No v1 code writes here** — migrated so billing is additive later |
| `V6__seed_reference_data.sql` | seeds | 01→09 | Rolled out incrementally: users at 02, catalogue at 03, batches arranged for a multi-batch FEFO split at 05, POs at 08 |

`V6` is a single file that grows across units, or a series of `V6*`, `V7*`… files. Decide at Unit 01 and record it here. **Append-only: never edit an applied migration.**

---

## Spec Files

| Unit | Spec File | Sub-Specs |
|------|-----------|-----------|
| 01 | `01-infrastructure.md` | `01a-backend-foundation.md`, `01b-error-handling.md`, `01c-frontend-shell.md`, `01d-auth-ui.md` |
| 02 | `02-auth-rbac.md` | `02a-user-entity-repository.md`, `02b-jwt-service.md`, `02c-login-refresh-logout-api.md`, `02d-rbac-user-management.md`, `02e-current-user-context.md` |
| 03 | `03-master-catalogue.md` | `03a-medicine-api.md`, `03b-supplier-api.md`, `03c-manufacturer-nonmedical-api.md`, `03d-search-cascade.md`, `03e-cost-redaction.md` |
| 04 | `04-catalogue-ui.md` | `04a-medicines-page.md`, `04b-medicine-form.md`, `04c-suppliers-page.md`, `04d-manufacturers-page.md`, `04e-nonmedical-page.md` |
| 05 | `05-inventory-batches.md` | `05a-batch-entity-ledger.md`, `05b-batch-api.md`, `05c-expiry-sweep.md` |
| 06 | `06-fefo-engine.md` | `06a-fefo-allocator.md`, `06a-fefo-allocator-tests.md`, `06b-fefo-api.md` |
| 07 | `07-inventory-ui.md` | `07a-batches-page.md`, `07b-fefo-preview-page.md`, `07c-expiry-badge.md` |
| 08 | `08-purchase-entry.md` | `08a-po-entities.md`, `08b-po-api.md`, `08c-approval-stock-write.md` |
| 09 | `09-alerts-cycle-count.md` | `09a-alerts-api.md`, `09b-cycle-count.md`, `09c-schedule-h-register.md` |
| 10 | `10-ocr-purchase.md` | `10a-invoice-parser.md`, `10b-medicine-matcher.md`, `10c-ocr-api.md`, `10d-hms-stub.md` |
| 11 | `11-purchasing-ui.md` | `11a-purchase-orders-page.md`, `11b-purchase-order-form.md`, `11c-ocr-upload-page.md`, `11d-ocr-review-table.md` |

---

## Verification Gates Per Phase

| Phase | Gate |
|-------|------|
| A | App boots on an empty PostgreSQL schema, Flyway applies, `/actuator/health` is `UP`, Swagger reachable, a login returns a JWT, a protected endpoint returns 401 without one and 200 with one |
| B | A medicine can be created and found by exact name, by prefix, and by a deliberately misspelled soundex query. A CASHIER response contains no `cost_price` |
| C | `FefoAllocatorTest` passes all 11 cases. Ledger reconciliation returns zero rows. A multi-batch FEFO split is demonstrated in the UI |
| D | A PO approved in the UI creates exactly the expected batches and exactly one ledger row per line. An OCR upload returns a draft that can be corrected and approved without retyping it |

---

## Out of Scope for v1

Recorded so unit boundaries stay clean. Full rationale in `project-overview.md` §5 and `MDD.md` §8.

| Feature | Revisit when |
|---------|---------------|
| OPD billing & dispensing | HMS ships `GET /api/v1/appointments/{id}/prescription` |
| IPD billing / ward requisitions | HMS exposes a ledger-posting API |
| Returns processing | A sale flow exists to return against |
| Short book, stock valuation, margin reports | Billing ships |
| Invoice PDFs | Billing ships |
| Notifications | A real outbound-alert requirement appears |
| Multi-tenancy | Only if this becomes a multi-hospital product |
