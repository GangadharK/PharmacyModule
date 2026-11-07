# Project Overview — Pharmacy Management System (PMS)

**Module:** `practi/Pharmacy`
**Status:** v1 in build
**Last updated:** September 2026

---

## 1. What this is

A **hospital pharmacy management system**. It runs as a standalone service but is designed to sit alongside the parent Hospital Management System (`practi`, package `com.potla`) and consume its prescription and patient-identifier APIs when those become available.

In one sentence: **it tracks every batch of medicine the hospital holds, tells the pharmacist which batch to dispense first, and turns supplier invoices into stock.**

## 2. Why it exists

A hospital pharmacy has one hard problem that ordinary inventory systems get wrong: **medicine expires.** Stock that is worthless on its expiry date is pure financial loss, and a hospital holds batches from many suppliers with wildly different expiry horizons. Issuing the *oldest* batch first is not an optimisation — it is the difference between recovering cost and writing it off.

This module is built around that single fact. Every design decision below serves FEFO (First Expired, First Out).

Secondary drivers:

- **Regulatory.** Schedule H and H1 drugs may only be dispensed against a valid prescription, and every dispensing event is a statutory record. A pharmacist must be able to produce that register on demand.
- **Financial.** Cost price and selling price differ per batch, not per medicine. Margin reporting has to be per-batch to be truthful.
- **Accuracy.** A cashier must be able to sell medicine without being able to alter stock, and must never see cost price.

## 3. Primary users

| Role | What they actually do all day | What the system gives them |
| :--- | :--- | :--- |
| **Pharmacist** | Verifies prescriptions, dispenses, counts stock, handles returns | FEFO batch suggestion per dispense line, expiry alerts, cycle counts, Schedule H/H1 register |
| **Cashier** | Rings up a prescription, takes payment, prints a receipt | Fast medicine search, invoice creation. **Cannot** see cost price, adjust stock, or approve anything |
| **Pharmacy Manager** | Approves purchase orders, authorises write-offs, watches margin | PO approval queue, write-off authorisation, margin reports, low-stock dashboard |
| **Super Admin** | Maintains suppliers, manufacturers, users, global pricing | Full master-data CRUD, user management, all financial reports |
| **Nurse / Ward Staff** | Raises a ward requisition against a patient | *Via the HMS, not this system.* They never log in here — see §5 |

## 4. Core flow — the one that matters

```text
Supplier delivers medicine
        │
        ▼
Pharmacist records the delivery ──── or ──── Pharmacist photographs the
        │                                        supplier's paper invoice
        │                                                    │
        │                                          OCR extracts the text
        │                                                    │
        │                                          pharmacist corrects
        │                                          the misreads
        ▼                                                    ▼
              Purchase order  ──►  Manager approves  ──►  APPROVED
                                                            │
                                                            ▼
                                              One inventory_batch row per
                                              line, each carrying its own
                                              batch number, mfg date, expiry
                                              date, cost price, selling price
                                                            │
        ┌───────────────────────────────────────────────────┘
        ▼
  A patient is prescribed Medicine X, quantity 15
        │
        ▼
  FEFO engine: walk Medicine X's batches by nearest expiry,
  deduct 10 from the batch expiring soonest, 5 from the next
        │
        ▼
  Every deduction writes a stock_transaction row
        │
        ▼
  Ledger reconciles: SUM(transactions) == quantity_available, for every batch
```

The last line is the system's integrity guarantee. If it ever fails, stock numbers are lying to a pharmacist making a clinical decision.

## 5. Explicitly out of scope for v1

Signed off. Deferred, not forgotten — the schema is migrated so later units are additive.

| Deferred | Why | Triggers later work |
| :--- | :--- | :--- |
| **OPD billing & dispensing** | The HMS prescription endpoint does not exist upstream. Billing needs a prescription to bill against. | HMS ships `GET /api/v1/appointments/{id}/prescription` |
| **IPD billing / ward requisitions** | Same dependency, plus an HMS ledger-posting API | HMS exposes a ledger endpoint |
| **Returns processing** | Needs `SALE_OUT` history to return against; there is no sale flow in v1 | Billing ships |
| **Full report suite** — short book, stock valuation, margin | Only the Schedule H/H1 register and the reconciliation query are in v1 | Billing ships |
| **Invoice PDFs** (OpenPDF) | No invoice flow to render | Billing ships |
| **Notifications** (Novu / WhatsApp) | FDD asks only for dashboard widgets, not outbound alerts | A real alerting requirement appears |

**The FEFO engine ships in v1 even though billing does not.** It is the core of the system, it is independently testable, and billing later becomes a consumer of an already-correct allocator rather than a source of one.

## 6. Success criteria

v1 is done when all of the following are demonstrably true:

1. A pharmacist can record a supplier delivery by hand, and a manager can approve it, and stock appears — traceable to a specific batch, expiry date and cost price.
2. A pharmacist can photograph a supplier invoice, get a parsed draft, correct the misreads, and approve it into real stock.
3. Given a medicine with three batches expiring on different dates, the FEFO engine provably dispenses from the earliest-expiring batch first, splits across batches when it must, and refuses cleanly when there is not enough.
4. Every single change to stock quantity has a matching audit row, and the reconciliation query returns zero rows.
5. A cashier can search a medicine and start an invoice but is served **no cost price** anywhere in the API surface, and cannot reach any stock-mutation endpoint.
6. The near-expiry and low-stock dashboards surface real data from the database, not placeholders.

## 7. How this relates to the rest of the repo

```text
practi/
├── src/            ← practi (HMS). PostgreSQL :5432/practice, port 8080.
│                     Owns users, doctors, patients, appointments.
│                     Does NOT yet expose a prescription endpoint.
│
├── frontend/       ← HMS React app (plain JSX + Vite). Not the PMS UI.
│
└── Pharmacy/       ← THIS MODULE. PostgreSQL :5432/pharmacy, port 8081.
   ├── context/     ← the six context files + FDD/MDD + specs
   └── frontend/    ← the PMS React app (built in this module)
```

Pharmacy is a **separate Maven project** with its own `pom.xml` — not a module of the `practi` aggregator. Different group id (`com.greedit` vs `com.potla`), different database, different port. It shares no code with the HMS; the only coupling is the future HTTP call described in `MDD.md` §4.3.

`../SCMS` is a sibling project that solves a related problem. What is borrowed from it and what is rejected is recorded in `MDD.md` §9 — read that before assuming any convention came from nowhere.

## 8. Related documents

| Document | What it answers |
| :--- | :--- |
| `FDD.md` | What the system does, for whom, and which features exist in the full product |
| `MDD.md` | How it is built — stack, schema, algorithms, security, v1 scope, SCMS relationship |
| `architecture-context.md` | Boundaries, storage model, invariants |
| `code-standards.md` | How to write code here |
| `ui-context.md` | How it looks |
| `ai-workflow-rules.md` | How to work here |
| `progress-tracker.md` | What is built, what is next, what is undecided |
| `layer-tracker.md` | Per-component and per-endpoint status |
| `specs/00-build-plan.md` | The unit sequence and why it is ordered that way |
