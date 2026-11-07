# Code Standards — Pharmacy Management System (PMS)

**Status:** Active
**Last updated:** September 2026

Conventions follow the `../SCMS/apps/core-service` and `../SCMS/apps/web-internal` reference implementation, except where §9 records a deliberate departure. Read that section before assuming a convention is arbitrary.

---

## 1. General principles

1. **Type safety.** No `any` in TypeScript. No raw types in Java generics. No `Object` in a signature.
2. **Immutability by default.** `final` in Java, `readonly` in TypeScript, `const` everywhere.
3. **Fail fast.** Validate at the boundary. Throw early, catch narrowly.
4. **Single responsibility.** One class does one thing.
5. **No hardcoded values.** Configuration via `application.properties` with `${ENV:default}` placeholders. Colours via `ui-context.md` tokens.
6. **No speculative generality.** Do not build for a requirement that is not in the current unit's spec.

## 2. Java standards

### 2.1 Entity conventions

Every entity follows the same shape. This is the SCMS pattern and it is kept exactly.

```java
package com.greedit.pharmacy.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Inventory batch - the atomic unit of stock in the pharmacy.
 * This is the row FEFO walks in ascending expiry order.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "inventory_batch", indexes = {
        @Index(name = "idx_fefo_search",
               columnList = "medicine_id, status, expiry_date, location")
})
public class InventoryBatch {

    @Id
    // No columnDefinition and no @Convert. Hibernate maps UUID natively to
    // PostgreSQL 'uuid' and round-trips it correctly unaided. A columnDefinition
    // would hardcode DDL into the entity, which ddl-auto: validate checks
    // against the live schema - so it fails at boot the first time it disagrees.
    private UUID id;

    @Column(name = "batch_number", nullable = false, length = 100)
    private String batchNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private BatchStatus status = BatchStatus.ACTIVE;

    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public enum BatchStatus { ACTIVE, QUARANTINED, EXPIRED, DEPLETED }
}
```

Rules that hold across all entities:

| Rule | Detail |
| --- | --- |
| Lombok order | `@Data @Builder @NoArgsConstructor @AllArgsConstructor`, always in that order |
| JPA imports | `import jakarta.persistence.*` — never `javax.*` |
| Table | `@Table(name = "snake_case")`. **No `schema` attribute** — single schema. No `columnDefinition`: `ddl-auto: validate` checks the mapping against the real schema, so hardcoded DDL is a boot failure the first time it disagrees. |
| ID | `UUID`, mapped by Hibernate natively to PostgreSQL `uuid`. **No `@Convert` and no `columnDefinition`** — neither is needed, and both were removed with the MySQL support that motivated them. Do **not** use `GenerationType.UUID` — the app generates IDs so it can write a ledger row referencing the batch in the same transaction. |
| FK columns | Plain `UUID` fields (`medicineId`, `supplierId`). **No `@ManyToOne`** — it makes batch reads N+1 and buys nothing here. |
| Enums | Nested inside the entity, `@Enumerated(EnumType.STRING)`, default via `@Builder.Default` |
| Money | `BigDecimal` with `@Column(precision = 10, scale = 2)`. **Never `Double`.** |
| Quantities | `Integer` |
| Dates | `LocalDate` for expiry and manufacturing. `LocalDateTime` for audit timestamps. |
| Timestamps | `createdAt` via `@Builder.Default ... = LocalDateTime.now()`; `updatedAt` via `@PreUpdate`. No `@EntityListeners`, no `@EnableJpaAuditing` — matching SCMS. |
| Behaviour | Only invariant guards (`getAvailableQuantity()`, `hasSufficientQuantity()`, `deductQuantity()`). **No business orchestration in an entity.** |
| Javadoc | On the class, and on any method whose contract is not obvious. |
| Section dividers | `// ==================== Section Name ====================` in classes over ~150 lines. |

### 2.2 Repository conventions

```java
package com.greedit.pharmacy.repository;

@Repository
public interface InventoryBatchRepository extends JpaRepository<InventoryBatch, UUID> {

    /**
     * FEFO candidate query. Ordering is load-bearing - see architecture-context.md 5.2.
     * FOR UPDATE serialises concurrent dispensations at the read.
     */
    @Query(value = """
            SELECT b FROM InventoryBatch b
            WHERE b.medicine.id = :medicineId
              AND b.location = :location
              AND b.status = com.greedit.pharmacy.entity.InventoryBatch.BatchStatus.ACTIVE
              AND b.quantityAvailable > 0
              AND b.expiryDate >= :today
            ORDER BY b.expiryDate ASC, b.batchNumber ASC
            """, nativeQuery = false)
    List<InventoryBatch> findFefoCandidates(
            @Param("medicineId") UUID medicineId,
            @Param("location") PharmacyLocation location,
            @Param("today") LocalDate today,
            @Lock(LockModeType.PESSIMISTIC_WRITE);
}
```

| Rule | Detail |
| --- | --- |
| Annotation | `@Repository` on the interface |
| Base | `extends JpaRepository<T, UUID>` |
| Custom queries | JPQL **text blocks** (`"""`) for anything multi-line. Derived method names for simple cases. |
| Native SQL | **Avoid.** SCMS has zero `nativeQuery = true` and so does Pharmacy. |
| Enums in JPQL | Fully-qualified: `com.greedit.pharmacy.entity.InventoryBatch.BatchStatus.ACTIVE`. |
| Locking | `@Lock(LockModeType.PESSIMISTIC_WRITE)` on FEFO reads. |
| Pagination | Return `Page<T>`; let Spring Data build the count query. |
| Naming | `findBy<Field>And<Field>`, `existsBy<Field>`, `countBy<Field>` |
| Comment | Javadoc on every custom `@Query`, explaining **why** it is shaped that way. |

### 2.3 Service conventions

```java
package com.greedit.pharmacy.service;

@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryService {

    private final InventoryBatchRepository batchRepository;
    private final StockTransactionRepository transactionRepository;
    private final FefoAllocator fefoAllocator;
    private final CurrentUserContext currentUser;

    @Transactional(readOnly = true)
    public List<InventoryBatchResponse> findBatches(BatchSearchCriteria criteria, Pageable pageable) {
        // ...
    }

    @Transactional
    public List<StockTransaction> dispense(DispenseRequest request) {
        // ...
    }
}
```

| Rule | Detail |
| --- | --- |
| Annotations | `@Slf4j @Service @RequiredArgsConstructor` |
| Injection | Constructor only, via `private final`. **No `@Autowired` on fields.** |
| Transactions | `@Transactional(readOnly = true)` on reads, plain `@Transactional` on writes. **Only in `service/`.** |
| Not found | `orElseThrow(() -> new ResourceNotFoundException("InventoryBatch", id))` — a real exception, not `IllegalArgumentException`. See §2.6. |
| Logging | `log.info` for state changes (what changed, which batch, how much, who). `log.debug` for reads. Never log a password, token, or cost price at INFO. |
| Long classes | Over ~300 lines, split by concern and use section dividers. |
| Pure logic | No Spring beans, no repository, no writes, no injected clock. See `FefoAllocator`, `InvoiceTextParser`. |

### 2.4 Controller conventions

```java
package com.greedit.pharmacy.controller;

@RestController
@RequestMapping("/api/v1/medicines")
@RequiredArgsConstructor
@Tag(name = "Medicines", description = "Medicine catalogue management")
public class MedicineController {

    private final MedicineService medicineService;

    @GetMapping
    @PreAuthorize("hasAnyRole('PHARMACIST','CASHIER','PHARMACY_MANAGER','SUPER_ADMIN')")
    @Operation(summary = "List medicines", description = "Paginated, searchable medicine catalogue")
    public ResponseEntity<PageResponse<MedicineResponse>> list(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(medicineService.search(q, page, size));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('PHARMACY_MANAGER','SUPER_ADMIN')")
    @Operation(summary = "Create medicine")
    public ResponseEntity<MedicineResponse> create(@Valid @RequestBody MedicineRequest request) {
        MedicineResponse created = medicineService.create(request);
        return ResponseEntity.created(
                URI.create("/api/v1/medicines/" + created.id())).body(created);
    }
}
```

| Rule | Detail |
| --- | --- |
| Annotations | `@RestController` + `@RequestMapping("/api/v1/<module>")` + `@RequiredArgsConstructor` |
| Base path | Always `/api/v1/...` |
| Docs | Class-level `@Tag`, `@Operation(summary, description)` on **every** handler |
| Auth | `@PreAuthorize` on **every** method. No mutating endpoint may rely on the URL rules alone. |
| Validation | `@Valid @RequestBody` on every request body. **This is not optional** — SCMS omits it everywhere, which is why its one validation constraint does nothing. |
| Create | `201` + `Location` via `URI.create("/api/v1/.../" + id)` |
| Delete | `204` via `ResponseEntity.noContent().build()` |
| Lists | `ResponseEntity<PageResponse<T>>`, not a raw Spring `Page` — its JSON shape leaks into the frontend contract. |
| Body | Validate, delegate, map. **No business logic, no repository access.** |
| Section dividers | `// ==================== Section ====================` |

### 2.5 DTO conventions

Plain Lombok **classes**, not records. Manual mapping, no MapStruct.

```java
package com.greedit.pharmacy.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MedicineResponse {

    private UUID id;
    private String genericName;
    private String brandName;
    private String strength;
    private String dosageForm;
    private String scheduleClass;
    private Integer reorderLevel;
    private Boolean isActive;

    /**
     * Cost price is deliberately absent. See architecture-context.md 4.3 -
     * a Cashier must never receive it. Mappers omit it based on caller role.
     */
    public static MedicineResponse from(Medicine medicine) {
        return MedicineResponse.builder()
                .id(medicine.getId())
                .genericName(medicine.getGenericName())
                // ...
                .build();
    }
}
```

| Rule | Detail |
| --- | --- |
| Type | Lombok `@Data @Builder @NoArgsConstructor @AllArgsConstructor` in that order |
| Mapping | `static from(Entity)` factory on the response DTO, or a private method in the service when extra lookups are needed |
| Never | Return a JPA entity from a controller. It leaks `cost_price` and `password_hash` by default. |
| Money | `BigDecimal`, serialised as a JSON number, never a `String` |
| IDs | `UUID` → JSON string |
| Package | `dto/request/` and `dto/response/` |
| Validation | Bean Validation annotations on request DTOs: `@NotBlank`, `@NotNull`, `@Positive`, `@Size`, `@Pattern` |

**N+1 warning.** SCMS's `mapBatchesToDTOs` calls `findById` per row to resolve names. Do not copy that. Collect the distinct foreign keys from the page into a `Set`, issue **one** `findAllById`, build a `Map`, then map. On a 100-row page that is the difference between 101 queries and 2.

### 2.6 Exception conventions

SCMS has **no custom exception classes** and throws `IllegalArgumentException` for everything, so a missing batch and a genuine bad argument are indistinguishable to the client. Pharmacy defines real exceptions.

```java
package com.greedit.pharmacy.exception;

/** Thrown when a requested resource does not exist. Maps to HTTP 404. */
public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String resource, Object id) {
        super("%s not found: %s".formatted(resource, id));
    }
}
```

| Exception | HTTP | `code` |
| --- | --- | --- |
| `ResourceNotFoundException` | 404 | `NOT_FOUND` |
| `InsufficientStockException` | 400 | `INSUFFICIENT_STOCK` |
| `BusinessRuleException` | 400 | `BUSINESS_RULE_VIOLATION` |
| `InvalidStateTransitionException` | 409 | `INVALID_STATE_TRANSITION` |
| `AccessForbiddenException` | 403 | `FORBIDDEN` |

`GlobalExceptionHandler` (`@RestControllerAdvice`) produces the `ApiError` envelope in `architecture-context.md` §7. The 500 handler logs the real exception and returns a **masked** message — never leak a stack trace or a SQL error to a client.

### 2.7 Transaction rules

```java
// CORRECT - one aggregate, one transaction
@Transactional
public List<StockTransaction> dispense(DispenseRequest request) {
    List<Allocation> allocations = fefoAllocator.allocate(candidates, request.quantity(), today);
    allocations.forEach(this::applyDeduction);
    return transactionRepository.saveAll(...);
}
```

- A transaction is scoped to **one aggregate**. Never wrap a PO approval and its stock writes in a nested `REQUIRES_NEW` — partial approval is not a state this system has.
- The FEFO read and every deduction it drives happen in **one** transaction, or the ledger does not reconcile.
- Read-only queries get `readOnly = true`.
- Self-invocation does not go through the proxy. If a `@Transactional` method must call another, inject the bean or lift the transaction to the caller.

## 3. TypeScript standards

### 3.1 File and import conventions

```text
src/
├── components/
│   ├── layout/      AppShell, Sidebar, Header, PageHeader
│   └── ui/          shadcn primitives - do not edit to change one screen
├── features/
│   └── <feature>/
│       ├── index.ts        barrel: pages first, then components
│       ├── pages/
│       └── components/
├── lib/             api.ts, utils.ts
├── pages/           app-level pages (Login, Dashboard, Settings)
├── store/           Zustand stores
└── types/           shared API types
```

| Rule | Detail |
| --- | --- |
| Imports | `@/` alias. `import { Button } from '@/components/ui/button'` |
| Feature pages | Imported **only** through the feature barrel |
| Barrel | One `export { X } from './path'` per line. `pages/` block first. No `export *`, no default export. |
| Types | API response types in `types/`. Component-local props declared as a local `interface` in the same file. |
| `any` | **Banned.** `unknown` plus narrowing when genuinely dynamic. |
| Compiler | `strict`, `noUnusedLocals`, `noUnusedParameters` are on and `npm run build` runs `tsc` first. An unused import **fails the build**. |
| Components | `function Foo() {}` with named exports. Only `App` and `main` are default exports. |
| Props | Always typed via an `interface FooProps`. Never inline object literals. |

### 3.2 Data fetching

One `ApiClient` in `lib/api.ts`. **Every** network call goes through it.

```tsx
// lib/api.ts
class ApiClient {
  private async request<T>(endpoint: string, options: RequestOptions = {}): Promise<T> {
    const response = await fetch(`${this.baseUrl}${endpoint}`, { ...options, headers });
    if (response.status === 401) { useAuthStore.getState().logout(); throw new UnauthorizedError(); }
    if (!response.ok) {
      const error = (await response.json().catch(() => null)) as ApiError | null;
      throw new ApiRequestError(error ?? { message: `HTTP ${response.status}` });
    }
    return response.json();
  }
  get<T>(endpoint: string) { return this.request<T>(endpoint, { method: 'GET' }); }
  post<T>(endpoint: string, data?: unknown) { /* ... */ }
  // ...
}
```

SCMS has two competing fetch styles — some components use raw `fetch` with a token scraped from `sessionStorage`, bypassing the client. **Not copied.** One path, one error handler, one place where a 401 triggers logout.

Branching on errors uses `error.code`, never on message text.

### 3.3 State

| Concern | Tool |
| --- | --- |
| Auth | Zustand, in-memory, not persisted |
| UI chrome | Zustand |
| Toasts | Zustand |
| Form state | React Hook Form |
| Server data | `useEffect` + `ApiClient` |

No React Query — see `ui-context.md` §8 for the reasoning.

### 3.4 Components

```tsx
interface FefoAllocationTableProps {
  allocations: Allocation[];
  requestedQuantity: number;
  shortfall: number;
  showCost: boolean;
}

export function FefoAllocationTable({
  allocations, requestedQuantity, shortfall, showCost,
}: FefoAllocationTableProps) {
  // ...
}
```

- Props are data-in, event-out. `onXxx?: (value: T) => void`.
- Every data component handles **loading, empty, and error** as explicit early returns.
- Colours come from tokens (`bg-primary`, `text-destructive`). **No hardcoded hex** in a `.tsx` file.
- Conditional classnames always via `cn(...)`, never string concatenation.
- A component that displays money accepts a `showCost` prop and honours it (`ui-context.md` §5).

### 3.5 Forms

React Hook Form + Zod on every form, per `ui-context.md` §7. `z.coerce.number()` for numeric fields. Errors render as `text-sm text-destructive` beneath the field.

## 4. Configuration

`application.properties` with env-var placeholders. **SCMS hardcodes credentials — not copied.**

```properties
server.port=${SERVER_PORT:8081}

spring.datasource.url=${DB_URL:jdbc:postgresql://localhost:5432/pharmacy}
spring.datasource.username=${DB_USERNAME:pharmacy}
spring.datasource.password=${DB_PASSWORD:pharmacy}
spring.jpa.hibernate.ddl-auto=validate

app.jwt.secret=${JWT_SECRET:change-me-in-every-real-deployment}
app.jwt.access-token-minutes=15
app.jwt.refresh-token-days=7
app.upload.dir=${UPLOAD_DIR:./uploads}
app.upload.max-size-bytes=10485760
app.cors.allowed-origins=${CORS_ORIGINS:http://localhost:5163}
app.hms.mode=${HMS_MODE:mock}
app.hms.api-url=${HMS_API_URL:http://localhost:8080}
```

Profile-specific settings live in a separate `application-<profile>.properties`, activated with `-Dspring-boot.run.profiles=dev`. Properties has no multi-document equivalent of YAML's `---` separator, so the dev profile that used to be a second document in `application.yml` is now `application-dev.properties`. Only `dev` exists.

Bind custom configuration to a `@ConfigurationProperties` record rather than scattering `@Value` across classes.

## 5. SQL and migrations

- One Flyway tree: `src/main/resources/db/migration/V<n>__<description>.sql`. PostgreSQL is the only engine (decision 22).
- Header comment on every migration: what it does and what it depends on. `MigrationGuardTest` asserts both are present.
- **Append-only.** Never edit an applied migration — write a new one. Editing one changes its checksum and Flyway refuses to start.
- One index per foreign key and per column used in a `WHERE` or `ORDER BY`.
- `CHECK` constraints for cross-column invariants. PostgreSQL always enforces them. (`char(n)` is not a substitute for `varchar(n)` — it reports as `bpchar` and fails validation; see MDD §3.9.)
- `timestamp(3)`, set from Java, so audit ordering is deterministic. Not timezone-aware — `timestamp without time zone` — so the session runs UTC.
- Comment enum-ish columns inline: `status VARCHAR(20) NOT NULL, -- ACTIVE, QUARANTINED, EXPIRED, DEPLETED`
- **Never put credentials or seed users in a migration.** Flyway runs against the real database, so an `INSERT` of a demo account creates a known-password `SUPER_ADMIN` on every deploy. Seed from code behind a flag (`app.seed.enabled`) instead.
- **Boot the app after every migration.** `ddl-auto: validate` is the only check that catches a wrong type; recreating the database and starting it proves the whole chain. `MigrationGuardTest` covers versioning, headers and the no-credentials rule.

## 6. Git conventions

```text
feat/inventory-fefo-engine
fix/fefo-tie-break-order
chore/flyway-initial-schema
```

```text
feat(inventory): implement FEFO allocation engine

- Add FefoAllocator as a pure function over sorted candidates
- Guard deductions with a conditional UPDATE to prevent negative stock
- Write a stock_transaction row per allocation in the same transaction
- Cover 11 edge cases including boundary-exact and insufficient stock
```

Branch naming `feat|fix|chore|docs|refactor/<area>-<slug>`. Commit messages reference the unit number from `specs/00-build-plan.md` where one applies.

## 7. Testing standards

SCMS has **zero** tests. That is the single most important thing not being copied.

```java
@ExtendWith(MockitoExtension.class)
class FefoAllocatorTest {

    private final FefoAllocator allocator = new FefoAllocator();

    @Test
    void shouldSplitAcrossTwoBatches_whenFirstBatchIsInsufficient() {
        // Arrange
        List<BatchCandidate> candidates = List.of(
                candidate("B1", expiryInDays(10), 10),
                candidate("B2", expiryInDays(40), 20));
        // Act
        AllocationResult result = allocator.allocate(candidates, 15, LocalDate.now());
        // Assert
        assertThat(result.allocations()).hasSize(2);
        assertThat(result.allocations().get(0).quantity()).isEqualTo(10);
        assertThat(result.allocations().get(1).quantity()).isEqualTo(5);
        assertThat(result.shortfall()).isZero();
    }

    @Test
    void shouldReportShortfall_whenTotalStockIsInsufficient() {
        // Arrange
        List<BatchCandidate> candidates = List.of(candidate("B1", expiryInDays(10), 4));
        // Act
        AllocationResult result = allocator.allocate(candidates, 15, LocalDate.now());
        // Assert
        assertThat(result.shortfall()).isEqualTo(11);
    }
}
```

| Rule | Detail |
| --- | --- |
| Naming | `should<ExpectedOutcome>_when<Scenario>` |
| Structure | `// Arrange` / `// Act` / `// Assert` comments |
| Assertions | AssertJ (`assertThat`). Prefer `BigDecimal` `isEqualByComparingTo` over `isEqualTo`. |
| Pure logic | No `@Mock`, no Spring. `FefoAllocator` and `InvoiceTextParser` are constructed with `new`. |
| Services | `@ExtendWith(MockitoExtension.class)`, `@Mock` repositories, `@InjectMocks` service. |
| Controllers | `@WebMvcTest` + MockMvc. Assert status, validation errors, and auth behaviour. |
| What to test | The FEFO allocation table exhaustively. The Cashier cost-price redaction. The OCR parser's regex edge cases. The PO state machine. |
| What not to test | Getters, setters, Lombok-generated code, framework wiring. |

## 8. Logging

| Level | Use |
| --- | --- |
| `ERROR` | An operation failed and a human must look. Stack trace attached. |
| `WARN` | A business rule was hit that is not exceptional — insufficient stock, expired batch skipped. |
| `INFO` | State changes: stock deducted, PO approved, user logged in. Include the actor and the magnitude. |
| `DEBUG` | Reads and query parameters. |

**Never log** a password, a JWT, a refresh token, or a cost price at INFO or above. In a regulated system the log is discoverable.

## 9. Deliberate departures from SCMS

Consolidated here so nobody re-litigates them. Full reasoning in `MDD.md` §9.3.

| # | SCMS does this | Pharmacy does this | Why |
| --- | --- | --- | --- |
| 1 | Modular monolith, 7 Postgres schemas | Single schema, flat layered packages | Client-facing standalone service; the boundaries are ceremony |
| 2 | `tenant_id` everywhere + `TenantContext` + RLS | No tenancy; slim `CurrentUserContext` for `performed_by` | FDD describes one hospital |
| 3 | `Double` for money | `BigDecimal` / `DECIMAL(10,2)` | Floating point cannot represent `0.1` |
| 4 | FEFO with no locking | `SELECT … FOR UPDATE` | SCMS has a real concurrent-dispense race |
| 5 | Mixed-case `@PreAuthorize`, worked around in the JWT converter | UPPER_CASE only, no workaround | The workaround silently grants extra authorities |
| 6 | Keycloak + realm JSON | Self-issued JWT (jjwt) | FDD §1 requires standalone operation |
| 7 | Zero tests | Real unit tests on all critical paths | The highest-risk code was entirely unexercised |
| 8 | Hand-run SQL + `ddl-auto: update` | Flyway + `ddl-auto: validate` | `update` hides migration drift |
| 9 | Raw `fetch` bypassing the API client | `ApiClient` only | One error path |
| 10 | `any` in handlers and grid events | No `any`; `strict` compiler flags | Untyped escape hatches defeat the compiler |
| 11 | Design `.md` inside `src/main/java` | Docs in `context/` only | Documentation does not belong in the source tree |
| 12 | `ToastContainer` never mounted | Mounted and verified | Toasts silently did nothing |
| 13 | `IllegalArgumentException` for everything | Real typed exceptions | Client cannot distinguish 404 from 400 |
| 14 | N+1 name resolution in list mappers | Batch `findAllById` + `Map` | 101 queries vs 2 on a 100-row page |
| 15 | `@Valid` omitted on controllers | `@Valid` on every request body | Validation must actually run |
