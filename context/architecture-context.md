# Architecture Context — Pharmacy Management System (PMS)

**Status:** Active
**Last updated:** September 2026

Read `project-overview.md` first. This file covers structure, boundaries, storage, and the invariants that must never be broken.

---

## 1. System boundary

The PMS is **one deployable Spring Boot service plus one SPA**. It owns its own database and its own identity store. It talks to exactly one other system, and only in a way that is stubbed out in v1.

```text
┌──────────────────────────────────────────────────────────────────────┐
│  Pharmacy/frontend            React SPA, port 5163 (dev)            │
│  Zustand + ApiClient + AG Grid + shadcn/ui                          │
└───────────────────────────────┬──────────────────────────────────────┘
                                │ HTTP/JSON, JWT Bearer
                                │ Vite dev proxy: /api → :8081
┌───────────────────────────────▼──────────────────────────────────────┐
│  Pharmacy (Spring Boot 4.0.8, Java 21)          port 8081           │
│                                                                      │
│   controller/ ──► service/ ──► repository/ ──► entity/                │
│        │                                                           │
│        └──► security/  (JWT, CurrentUserContext)                     │
│        └──► client/    (HmsClient — Feign, stubbed in v1)           │
└───────┬──────────────────────────────┬───────────────────────────────┘
        │                              │
┌───────▼──────────────┐    ┌──────────▼───────────────┐
│  PostgreSQL 16      │    │  Local filesystem        │
│  :5432/pharmacy     │    │  uploads/ (invoice imgs) │
│  single schema      │    └──────────────────────────┘
└──────────────────────┘

        ┌──────────────────────────────────────────────┐
        │  practi (HMS)  port 8080                     │
        │  NOT CALLED in v1 — MockHmsClient is @Primary│
        └──────────────────────────────────────────────┘
```

### What is outside the boundary

- **The HMS owns patients, doctors, appointments, and (eventually) prescriptions.** The PMS stores a UHID string for billing linkage and nothing clinical. No diagnosis, no prescription content is persisted here.
- **Redis is optional.** Caching is wired but the app must boot and serve correctly with Redis absent. Never make a request path fail because a cache is cold or missing.
- **No message broker.** SCMS uses RabbitMQ between internal modules; the PMS has no internal module boundaries, so there is nothing to decouple. Do not introduce one.
- **No file storage service.** Invoice images go to the local filesystem, path configurable. MinIO is a future swap, not a v1 dependency.

## 2. Package structure

Deliberately **flat and layered**, not SCMS's `domain/application/interfaces/infrastructure` split. The PMS has no internal module boundaries to defend, and the ceremony would obscure a codebase that fits in one deployable.

```text
com.greedit.pharmacy
├── PharmacyApplication.java   @SpringBootApplication, @EnableScheduling, @EnableAsync
│
├── config/                    Spring configuration beans only
│   ├── SecurityConfig             filter chain, CORS, method security
│   ├── OpenApiConfig              springdoc metadata
│   ├── WebConfig                  CORS source, static resource handlers
│   ├── SchedulingConfig           @EnableScheduling
│   └── properties/               @ConfigurationProperties records (AppProperties…)
│
├── entity/                    JPA entities. No behaviour beyond invariants
│   ├── User, Supplier, Manufacturer, Medicine, NonMedicalItem
│   ├── InventoryBatch, StockTransaction
│   └── PurchaseOrder, PurchaseOrderItem
│
├── repository/                Spring Data interfaces. One per aggregate root
│
├── service/                   Business logic. The only place transactions begin
│   ├── FefoAllocator              ← PURE FUNCTION. No Spring, no writes. See §5
│   ├── InventoryService
│   ├── MedicineService, SupplierService, ManufacturerService
│   ├── PurchaseOrderService
│   ├── AlertService
│   ├── UserService
│   └── ocr/                       ← OcrService, InvoiceTextParser (PURE), MedicineMatcher
│
├── controller/                REST endpoints. Thin: validate, delegate, map
│
├── dto/                       Request + response DTOs, grouped by feature
│   ├── request/
│   ├── response/
│   └── common/                ApiError, PageResponse
│
├── exception/                 Custom exceptions + GlobalExceptionHandler
│
├── security/                  JwtService, JwtAuthenticationFilter, CurrentUserContext
│
├── event/                     Domain events (POJOs)
│
└── client/                    HmsClient (Feign), MockHmsClient, dto/
```

### Layer rules

| Rule | Enforcement |
| :--- | :--- |
| Controllers never touch repositories | Convention. Only `service/` imports `repository/`. |
| Services never import from `controller/` | Convention. |
| Entities never import from `service/`, `controller/`, or `dto/` | Convention. Entities are data + invariants only. |
| `@Transactional` appears only in `service/` | Convention. A transaction in a controller is always a bug. |
| Repository queries never leave the transaction that opened them | JPA rule. Lazy relations must be initialised inside the service. |

**Exception to the flat rule:** `FefoAllocator` and `InvoiceTextParser` are deliberately Spring-free pure functions. They are the two highest-risk pieces of logic in the system and are unit-tested exhaustively with no mocks and no context. Do not inject anything into them.

## 3. Storage model

### 3.1 Database

| Setting | Value |
| :--- | --- |
| Engine | PostgreSQL 16. The only engine — decision 22 removed MySQL. |
| Database | `pharmacy` |
| Schema | **One schema.** No `catalog.*` / `inventory.*` split. |
| Migrations | Flyway, `src/main/resources/db/migration/V__*.sql`, applied on startup |
| `ddl-auto` | `validate` — Hibernate must **fail** on schema mismatch, never silently mutate |
| Isolation | PostgreSQL default `READ COMMITTED`. Pessimistic FEFO reads (`SELECT … FOR UPDATE`) carry the concurrency guarantee, not the isolation level. |

**Flyway naming:** `V1__create_users.sql`, `V2__create_catalogue.sql`, … Integer version, double underscore, lowercase snake description. Migrations are append-only. Never edit a migration that has been applied anywhere — write a new one.

`ddl-auto: validate` is a deliberate choice over `update`. `update` silently mutates a developer's database and hides migration bugs until they reach a fresh environment.

### 3.2 Identifiers

`BINARY(16)` holding `UUID_TO_BIN(UUID(), 1)`. The `1` flag swaps the UUID's time fields to the front, so primary keys are time-ordered and InnoDB clustered-index inserts stay append-mostly instead of scattering random page writes across the B-tree.

At the API boundary IDs are **UUID strings**. Conversion happens in exactly one place per direction — a `UuidConverter` (AttributeConverter) for persistence, and Jackson serialisation for the wire. Never let a `BINARY(16)` leak into a JSON response.

### 3.3 Money

`BigDecimal` mapped to `DECIMAL(10,2)`. **Never `float`, `double`, or `Double`** — for any monetary value, anywhere, including frontend types.

Rules that matter:

- Construct with `new BigDecimal("12.50")`, never `new BigDecimal(12.5)` — the latter inherits the double's binary error.
- Compare with `compareTo()`, not `equals()` — `equals` is scale-sensitive, so `2.50` is not equal to `2.5`.
- Set `spring.jpa.properties.hibernate.jdbc.time_zone` and be explicit about `BigDecimal` scale on write, so `2.5` does not silently become `2.50` in one place and `2.5` in another.

### 3.4 Files

Invoice images are written under a configured root (`app.upload.dir`, default `./uploads`). The path is stored on `purchase_order.invoice_image_path`. Uploads are validated on extension **and** content type **and** byte size before anything touches disk. A random component is appended to the stored filename so a user-supplied name can never influence the write path.

## 4. Identity and authorisation

### 4.1 Authentication

Self-issued JWT, per FDD §5. Not Keycloak — the PMS must run standalone, and a login must not require six containers.

| Property | Value |
| :--- | --- |
| Access token | JWT, **15-minute** expiry, HS256 |
| Refresh token | Opaque random string, **7-day** expiry, stored server-side, delivered in an `HttpOnly` + `Secure` + `SameSite=Strict` cookie |
| Storage of refresh tokens | `refresh_token` table, hashed at rest |
| Passwords | BCrypt. Never logged, never returned in any response. |
| Session | Stateless. `SessionCreationPolicy.STATELESS` |

Access tokens are held in memory by the SPA (Zustand, not persisted). Refresh tokens live in the HttpOnly cookie, which JavaScript cannot read — this is the reason for the cookie, and the reason no token goes into `localStorage`.

### 4.2 Roles

Exactly the four from FDD §2. UPPER_CASE, everywhere, with no exceptions.

| Role | May |
| :--- | :--- |
| `SUPER_ADMIN` | Everything. Master-data and user administration, all financial reports. |
| `PHARMACY_MANAGER` | Approve purchase orders, authorise write-offs and quarantine, transfer stock between locations, view margin and cost reports. |
| `PHARMACIST` | Dispense, verify prescriptions, process returns, run cycle counts, quarantine batches. |
| `CASHIER` | Create invoices, take payment, print receipts. **Cannot** see `cost_price`, adjust stock, or approve anything. |

Authorisation is enforced at two levels, both required:

1. **URL level** (`SecurityConfig`) — only auth and docs endpoints are public; everything else is `authenticated()`.
2. **Method level** (`@PreAuthorize`) — the real model. Every mutating endpoint carries an explicit role expression. There is no "authenticated is enough" endpoint that changes data.

### 4.3 Field-level confidentiality — the Cashier rule

FDD §2 says a Cashier *"Cannot adjust stock, view cost prices, or approve returns."* Endpoint-level `@PreAuthorize` cannot express "may read this object but not this field of it." So:

> **`cost_price` is omitted from every response DTO the Cashier role can reach.** Not blanked — omitted, so the field is absent from the JSON entirely and cannot be read out of the browser devtools.

`InventoryBatchResponse` and `PurchaseOrderItemResponse` are built by a mapper that takes the caller's authorities and drops cost-price fields when the caller is a `CASHIER`. The same rule hides cost figures from the low-stock and near-expiry alert payloads.

This is the one place in the codebase where a response shape depends on who is asking. It gets its own tests.

### 4.4 `CurrentUserContext`

`@RequestScope` component holding `userId`, `username`, `role` for the current request. Populated by `JwtAuthenticationFilter` from the validated token, cleared in `afterCompletion`.

It exists for one reason: `stock_transaction.performed_by` is a non-null audit field and must never be taken from a request body. A caller must not be able to forge who performed a stock movement.

There is **no `TenantContext`.** SCMS has one because it is multi-tenant. The PMS is a single hospital (see `MDD.md` §9.3).

## 5. The FEFO engine — the core algorithm

This is the highest-risk code in the system. It decides which medicine a patient receives.

### 5.1 Shape

```text
FefoAllocator.allocate(candidates, requestedQuantity, today)
    │
    ├── pure function: no Spring, no repository, no writes, no clock
    ├── input:  an already-filtered, already-sorted list of candidate batches
    └── output: AllocationResult { allocations[], totalAllocated, shortfall }
```

Splitting it this way is what makes it testable. The service layer does the querying, filtering and locking; the allocator does the arithmetic. Every edge case in the table below is a pure unit test with no mocks and no database.

### 5.2 Candidate selection — done in SQL, not in Java

The service queries:

```sql
SELECT ... FROM inventory_batch
WHERE medicine_id = ? AND location = ?
  AND status = 'ACTIVE' AND quantity_available > 0
  AND expiry_date >= :today
ORDER BY expiry_date ASC, batch_number ASC
FOR UPDATE
```

Four things are load-bearing here:

- **`expiry_date >= :today`, not `> CURRENT_DATE`.** A medicine expiring *today* is still dispensable today. The sweep (§5.5) expires it tomorrow. Using `>` would make stock vanish a day early and would make the behaviour depend on server timezone.
- **`status = 'ACTIVE'`** filters out quarantined and expired batches. The hourly sweep sets `EXPIRED`; a pharmacist can set `QUARANTINED`.
- **`ORDER BY expiry_date ASC, batch_number ASC`** — the second key breaks ties deterministically. Without it, two batches sharing an expiry date could be dispensed in either order, which makes tests flaky and audit trails irreproducible.
- **`FOR UPDATE`** takes an exclusive next-key lock so two concurrent dispensations serialise at the read instead of interleaving their writes. SCMS omits this and has a real race; see `MDD.md` §9.3.

### 5.3 The allocation walk

```text
remaining ← requestedQuantity
for each candidate, in order:
    if remaining ≤ 0: stop
    take ← min(candidate.available, remaining)
    emit Allocation(candidate, take)
    remaining ← remaining − take
shortfall ← remaining
```

Properties that must hold, each with a test:

| Property | Assertion |
| --- | --- |
| Zero request | Empty allocation list, no error |
| Single sufficient batch | One allocation for the full quantity |
| Single insufficient batch | `InsufficientStockException` carrying the shortfall |
| Request exceeds all stock | Exception reporting both shortfall and total available |
| Spans two batches | Two allocations, split at the boundary |
| Lands exactly on a boundary | First batch emptied; **no** zero-quantity allocation emitted |
| Zero or negative request | Rejected at the boundary with `IllegalArgumentException` |
| Expired or quarantined candidate | Never present in the candidate list, therefore never allocated |
| Tie on expiry date | Broken by `batch_number` — same input, same output, always |
| Location filter | Only batches in the requested location are considered |
| Sum invariant | `sum(allocation.quantity) == requestedQuantity − shortfall` |

### 5.4 Writing the deduction

The allocator does not write. The caller, inside one `@Transactional` method, applies each allocation:

```text
UPDATE inventory_batch
   SET quantity_available = quantity_available − :take
 WHERE id = :batchId AND quantity_available >= :take
```

If any such update affects **zero rows**, the whole transaction rolls back. A partial dispense is not representable.

Each deduction writes a `stock_transaction` row in the same transaction. Never a batch update without its transaction row — that is the exact failure the reconciliation query (§5.6) exists to catch.

### 5.5 The expiry sweep

`@Scheduled`, hourly. Transitions batches past their expiry to `status = 'EXPIRED'` and writes one `EXPIRY_WRITE_OFF` transaction per batch with `performed_by = NULL` (system-initiated).

`status` and `expiry_date` are deliberately redundant. `expiry_date` is the truth; `status` is the cached decision. The FEFO query filters on `status`, so if the sweep never ran, an expired batch would still be dispensable. The redundancy is the point.

### 5.6 The reconciliation invariant

> For every batch: `SUM(stock_transaction.quantity_changed) == inventory_batch.quantity_available`

`quantity_changed` is **signed** — positive in, negative out. That is what makes the sum meaningful and the check a single query. An unsigned magnitude column would force every consumer to re-derive direction from the type enum.

The check is exposed as an admin endpoint and is the **acceptance test for the audit invariant**. It must return zero rows. A non-empty result means some code path wrote to `inventory_batch` without a matching transaction.

## 6. Invariants

These hold everywhere, always. A change that breaks one is a bug, not a design decision.

| # | Invariant | Enforced by |
| --- | --- | --- |
| 1 | `quantity_available >= 0` | `CHECK` constraint + the guarded `UPDATE` in §5.4 |
| 2 | Every stock change has a `stock_transaction` row, in the same transaction | Code review + the reconciliation query |
| 3 | `SUM(quantity_changed) == quantity_available` per batch | Reconciliation query — must return zero rows |
| 4 | No `DELETE` on `inventory_batch` or `stock_transaction` | Code review. Both are permanent history. |
| 5 | Only `APPROVED` purchase orders write stock | Service-layer state machine check |
| 6 | Exactly one of `medicine_id` / `item_id` is set on a batch | `CHECK` constraint |
| 7 | A Cashier never receives a cost price | §4.3 mapper + its tests |
| 8 | No clinical data is stored in the PMS database | Code review. UHID and appointment ID only. |
| 9 | `performed_by` is never read from a request body | `CurrentUserContext` only |
| 10 | FEFO never allocates an expired or quarantined batch | §5.2 SQL filter + allocator tests |

## 7. Error handling contract

Every error returns the same envelope. `ApiError` is a real DTO, not a `Map.of(...)` — `Map.of` throws on a null value, so a null detail field turns a clean 400 into a 500.

```json
{
  "timestamp": "2026-09-26T10:15:30.123",
  "status": 400,
  "error": "BAD_REQUEST",
  "code": "INSUFFICIENT_STOCK",
  "message": "Only 12 units available for Paracetamol 500mg, 15 requested",
  "details": { "medicineId": "...", "available": 12, "requested": 15 },
  "path": "/api/v1/inventory/fefo-preview"
}
```

| Situation | Status | `code` |
| --- | --- | --- |
| Bean validation failure | 400 | `VALIDATION_FAILED` |
| Domain rule violated | 400 | domain-specific, e.g. `INSUFFICIENT_STOCK` |
| Not found | 404 | `NOT_FOUND` |
| Unauthenticated | 401 | `UNAUTHORIZED` |
| Authenticated but wrong role | 403 | `FORBIDDEN` |
| Unexpected | 500 | `INTERNAL_ERROR` — real message logged, **never** sent to the client |

`message` is for humans. `code` is for machines; the frontend branches on `code`, never on message text.

## 8. Testing strategy

| Layer | Tool | Rule |
| --- | --- | --- |
| Pure logic | JUnit 5, no Spring, no mocks | `FefoAllocator` and `InvoiceTextParser` — exhaustive table tests, every row of §5.3 and the OCR spec |
| Services | JUnit 5 + Mockito, no Spring context | Arrange / Act / Assert, `should<Outcome>_when<Scenario>` |
| Controllers | `@WebMvcTest` + MockMvc | Status codes, validation, auth behaviour per endpoint |
| Mappers | Plain unit tests | The §4.3 Cashier redaction especially |
| Integration | Manual, against a real PostgreSQL | Reconciliation query returns zero rows after a full purchase→dispense cycle |

Spring Boot 4 splits the test starters (`spring-boot-starter-webmvc-test`, `-data-jpa-test`, `-security-test`); the scaffold `pom.xml` already declares them.

SCMS has **zero** tests. That is one of the specific things not being copied — see `MDD.md` §9.3.

## 9. Dependency policy

Add a dependency only when the current unit needs it, never speculatively. Each addition needs a reason recorded in the unit's spec.

Already ruled out for v1, with reasons, so they are not re-litigated:

| Rejected | Why |
| --- | --- |
| RabbitMQ / Kafka | No internal module boundary to decouple. |
| Elasticsearch | `pg_trgm` + `tsvector` cover the search need in-engine (`MDD.md` §6). |
| MapStruct | Manual mapping is ~10 lines per DTO and needs no annotation processor. |
| Testcontainers | No container runtime is guaranteed in the dev loop. Integration checks are manual against local PostgreSQL. |
| Lombok alternatives | Lombok is already in the scaffold and is used per SCMS convention. |
