# Implementation Reference

Generated **26 Sep 2026**. Every endpoint table below is extracted from the
running application's OpenAPI document (`GET /v3/api-docs`), not hand-maintained,
so it cannot drift from the code.

**Scope covered:** Unit 02 (auth / RBAC) and Unit 03 (master catalogue), plus the
PostgreSQL-only migration completed beforehand. Inventory, purchase orders and
invoices are **not yet implemented** — see §9.

---

## 1. How to run and test

| | |
|---|---|
| Backend | http://localhost:8081 — `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev` |
| Frontend | http://localhost:5163 — `cd frontend && npm run dev` (proxies `/api` → 8081) |
| Swagger UI | http://localhost:8081/swagger-ui.html — **requires a `SUPER_ADMIN` token** |
| OpenAPI JSON | http://localhost:8081/v3/api-docs |
| Database | PostgreSQL 16, db/user/pass all `pharmacy` on `localhost:5432` |

The `dev` profile seeds four accounts, all with password `Pharmacy@Dev123`:

| Username | Role |
|---|---|
| `superadmin` | `SUPER_ADMIN` |
| `manager` | `PHARMACY_MANAGER` |
| `pharmacist` | `PHARMACIST` |
| `cashier` | `CASHIER` |

Seeding is gated on `app.seed.enabled` (`false` by default, `true` only in
`dev`). No credential is ever written by a migration — `AGENTS.md` rule 16.

> The dev database also contains a manually created `newpharm` account promoted to
> `SUPER_ADMIN` during testing, and the original `superadmin` was deactivated
> while verifying the last-admin guard. Recreate the database for a pristine run.

---

## 2. Layers delivered

71 main classes, 9 test classes, 4 migrations. Full inventory in §4–§8.

| Layer | Count | Notes |
|---|---|---|
| Entity | 6 | `User`, `RefreshToken`, `Manufacturer`, `Medicine`, `NonMedicalItem`, `Supplier` |
| Repository | 6 | all `JpaRepository` + `JpaSpecificationExecutor` |
| Service | 9 | 4 catalogue, auth, user, 2 pure-logic |
| Controller | 6 | auth, users, manufacturers, medicines, items, suppliers |
| DTO (record) | 19 | request/response pairs, no entity escapes the service |
| Config | 4 | `SecurityConfig`, `JwtProperties`, `CookieProperties`, seeder |

---

## 3. Endpoint index

**Role legend** — `SA` `SUPER_ADMIN` · `PM` `PHARMACY_MANAGER` · `PH` `PHARMACIST` · `CA` `CASHIER`.
A role inherits every role below it, so `SA` satisfies any annotation listing `PM`.

### 3.1 Authentication — `/api/v1/auth`

| Method | Endpoint | Access | Notes |
|---|---|---|---|
| `POST` | `/auth/login` | public | Returns access token + sets HttpOnly `refresh_token` cookie |
| `POST` | `/auth/refresh` | public (cookie) | **Rotates** the refresh token |
| `POST` | `/auth/logout` | public (cookie) | Idempotent; never errors on a missing cookie |
| `GET` | `/auth/me` | any role | Current user from the bearer token |

### 3.2 Users — `/api/v1/users` · **`SUPER_ADMIN` only**

| Method | Endpoint | Notes |
|---|---|---|
| `GET` | `/users` | `search`, `role`, `active`, `page`, `size` |
| `GET` | `/users/{id}` | |
| `POST` | `/users` | `CreateUserRequest` |
| `PATCH` | `/users/{id}` | Password / role / deactivation change revokes all refresh sessions |
| `DELETE` | `/users/{id}` | **Deactivate**, not delete — audit rows must still resolve |
| `POST` | `/users/{id}/reactivate` | |

### 3.3 Manufacturers — `/api/v1/manufacturers`

| Method | Endpoint | Read | Write |
|---|---|---|---|
| `GET` | `/manufacturers` | `CA`+ | |
| `GET` | `/manufacturers/{id}` | `CA`+ | |
| `POST` | `/manufacturers` | | `PM`+ |
| `PATCH` | `/manufacturers/{id}` | | `PM`+ |
| `DELETE` | `/manufacturers/{id}` | | `PM`+ — **hard delete, refused while any medicine references it** |

> † **`activeOnly` is tri-state.** `true` returns active rows only, `false` returns
> deactivated rows only, and **omitting the parameter applies no filter** and returns
> both. Every other optional filter on these endpoints already behaves that way; this
> one used to carry `@RequestParam(defaultValue = "true")`, which made the catalogue
> list screens' **All** state silently behave like **Active**, because the controller
> substituted `true` for the absent value. Clients must therefore send an explicit
> `activeOnly=true` when they want active rows — do not rely on a server default here.

### 3.4 Medicines — `/api/v1/medicines`

| Method | Endpoint | Read | Write |
|---|---|---|---|
| `GET` | `/medicines` | `CA`+ | `search`, `activeOnly`†, `dosageForm`, `scheduleClass`, `manufacturerId`, `page`, `size` |
| `GET` | `/medicines/{id}` | `CA`+ | |
| `POST` | `/medicines` | | `PM`+ |
| `PATCH` | `/medicines/{id}` | | `PM`+ — `dosageForm` is **immutable** |
| `DELETE` | `/medicines/{id}` | | `PM`+ — deactivate; batches and history retained |
| `POST` | `/medicines/{id}/reactivate` | | `PM`+ |

### 3.5 Non-medical items — `/api/v1/items`

| Method | Endpoint | Read | Write |
|---|---|---|---|
| `GET` | `/items` | `CA`+ | `search`, `activeOnly`†, `category`, `page`, `size` |
| `GET` | `/items/{id}` | `CA`+ | |
| `POST` | `/items` | | `PM`+ |
| `PATCH` | `/items/{id}` | | `PM`+ — `category` and `unit` are **immutable** |
| `DELETE` | `/items/{id}` | | `PM`+ — deactivate |
| `POST` | `/items/{id}/reactivate` | | `PM`+ |

### 3.6 Suppliers — `/api/v1/suppliers`

| Method | Endpoint | Read | Write |
|---|---|---|---|
| `GET` | `/suppliers` | `PH`+ | `search`, `activeOnly`†, `page`, `size` |
| `GET` | `/suppliers/{id}` | `PH`+ | |
| `POST` | `/suppliers` | | `PM`+ |
| `PATCH` | `/suppliers/{id}` | | `PM`+ |
| `DELETE` | `/suppliers/{id}` | | `PM`+ — deactivate; **no hard delete exists** |
| `POST` | `/suppliers/{id}/reactivate` | | `PM`+ |

### 3.7 System

| Method | Endpoint | Access | Notes |
|---|---|---|---|
| `GET` | `/system/ping` | public | Placeholder liveness probe — remove once auth smoke tests are done |
| `GET` | `/actuator/health` | public | Returns `{"status":"UP"}` |

---

## 4. Entities

| Entity | Table | Notable design |
|---|---|---|
| `User` | `users` | `Role` enum carries the hierarchy in `atLeast()` — the single place it is interpreted. `passwordHash` excluded three ways (`@ToString.Exclude`, `@EqualsAndHashCode.Exclude`, `@JsonIgnore`) |
| `RefreshToken` | `refresh_token` | Stores only the SHA-256 of the token. `replacedBy` links a rotation chain |
| `Manufacturer` | `manufacturer` | Name unique **case-insensitively** via a `lower(name)` functional index. No `isActive` — it has no stock, so switching it off would hide live batches from FEFO |
| `Medicine` | `medicine_catalogue` | Carries the denormalised `searchBlob` (service-maintained, never client-supplied). `soundexCode` is a `GENERATED ALWAYS` column and is **deliberately unmapped** — Hibernate has no clean mapping for a computed value never read back |
| `NonMedicalItem` | `non_medical_item` | Separate table rather than a nullable `medicine_id`, so FEFO queries need no discriminator |
| `Supplier` | `supplier` | Deactivate-only; purchase orders and batches are financial records |

`DosageForm`, `ScheduleClass`, `Category`, `Unit` are nested enums, each backed by
a `CHECK` constraint so an invalid value fails in the database, not just in Java.

---

## 5. Repositories

All extend `JpaRepository` + `JpaSpecificationExecutor` for composable optional
filters.

| Repository | Key methods |
|---|---|
| `UserRepository` | `findByUsername` (explicit `lower()` on both sides so the `V3` index is used), `existsByUsername`, `countByRoleAndIsActiveTrue` |
| `RefreshTokenRepository` | `findByTokenHash`, revoke-all, purge-expired |
| `ManufacturerRepository` | `findByName`, `existsByName` |
| `MedicineRepository` | `existsByGenericNameFormAndStrength`, `existsByManufacturerId`, `findByManufacturerIdOrderByGenericNameAsc` |
| `NonMedicalItemRepository` | `existsByNameAndUnit` |
| `SupplierRepository` | `findByName`, `existsByName`, `existsByGstNumberOtherThan` |

> `findByNameIgnoreCase`-style derived queries are avoided throughout: Hibernate
> implements them with `upper()`, which cannot use a `lower(name)` functional
> index and silently degrades to a sequential scan.

---

## 6. Services

| Service | Responsibility | Key invariants enforced |
|---|---|---|
| `AuthService` | login, refresh rotation, logout | Only token **hashes** persisted · unknown user costs a dummy BCrypt verify so response time does not leak existence · refresh replay revokes every session · identical message for unknown user and wrong password |
| `SessionRevoker` | revoke a user's sessions | `REQUIRES_NEW` — see §10, bug 2 |
| `UserService` | user CRUD | Last active `SUPER_ADMIN` cannot be deactivated or demoted · role/password/deactivation change revokes sessions · deactivate is idempotent and skips a pointless write |
| `ManufacturerService` | manufacturer CRUD | Trim before the uniqueness check, so `"Cipla "` cannot create a duplicate past the index · delete refused while a medicine references it |
| `MedicineService` | catalogue CRUD | `searchBlob` rebuilt on **every** write · duplicate tuple `(generic, form, strength)` refused · unknown `manufacturerId` → 404 not a flush-time constraint violation · manufacturer names resolved in one batched query |
| `NonMedicalItemService` | item CRUD | Duplicate scoped by `(name, unit)` · `searchBlob` rebuilt on rename |
| `SupplierService` | supplier CRUD | Case-insensitive name uniqueness · deactivate/reactivate idempotent |
| `CatalogueSearchText` | **pure logic** | Builds the lowercase haystack. `Locale.ROOT` lower-casing (a Turkish-locale server would otherwise store `ıbuprofen`, unfindable) · truncates on a token boundary, never mid-word |
| `JwtService` | token issue/verify | Rejects a secret under 32 bytes · 15-minute window |

---

## 7. DTOs

Entities never leave the service layer (`AGENTS.md` rule 5). All are Java records,
so adding a column forces an explicit decision about the API surface.

| DTO | Package | Notes |
|---|---|---|
| `LoginRequest` | `dto.auth` | |
| `TokenResponse` | `dto.auth` | Carries the session user; **no cost fields** |
| `AuthResult` | `dto.auth` | Access token + the `ResponseCookie` to set |
| `CreateUserRequest` | `dto.user` | Password capped at 72 chars — where BCrypt stops reading |
| `UpdateUserRequest` | `dto.user` | PATCH; null means unchanged |
| `UserResponse` | `dto.user` | No `passwordHash` field exists to leak |
| `Create/UpdateManufacturerRequest`, `ManufacturerResponse` | `dto.catalogue` | |
| `Create/UpdateSupplierRequest`, `SupplierResponse` | `dto.catalogue` | GSTIN validated as 15 upper-alphanumeric; stored as text so leading zeros survive |
| `Create/UpdateMedicineRequest`, `MedicineResponse` | `dto.catalogue` | No `searchBlob`/`soundexCode` on requests. `ScheduleClassRequest` is a nested enum so a new catalogue value cannot be introduced on the entity alone |
| `Create/UpdateNonMedicalItemRequest`, `NonMedicalItemResponse` | `dto.catalogue` | |
| `PageResponse<T>` | `common.dto` | Shared pagination envelope |
| `ApiError` | `common` | Uniform error body with a machine-readable `code` |

`AGENTS.md` rule 7 is satisfied structurally: no catalogue DTO has a
`costPrice` field, so no future column addition can leak a purchase price to a
`CASHIER`.

---

## 8. Controllers

| Controller | Base path | Authorisation |
|---|---|---|
| `AuthController` | `/api/v1/auth` | mixed — login/refresh/logout public, `/me` authenticated |
| `UserController` | `/api/v1/users` | class-level `SUPER_ADMIN` |
| `ManufacturerController` | `/api/v1/manufacturers` | class-level, `CASHIER`+ |
| `SupplierController` | `/api/v1/suppliers` | **method-level** — reads `PHARMACIST`+, writes `PHARMACY_MANAGER`+ |
| `MedicineController` | `/api/v1/medicines` | method-level — reads `CASHIER`+, writes `PHARMACY_MANAGER`+ |
| `NonMedicalItemController` | `/api/v1/items` | method-level — reads `CASHIER`+, writes `PHARMACY_MANAGER`+ |

Class-level `@PreAuthorize` is preferred so a newly added endpoint is denied by
default rather than silently exposed. All values UPPER_CASE (`AGENTS.md` rule 8).
`page`/`size` are clamped server-side — an unbounded `size` lets one request ask
for a million rows.

---

## 9. Migrations

| Version | File | Purpose |
|---|---|---|
| V1 | `V1__create_identity.sql` | `users`, `refresh_token` |
| V2 | `V2__token_hash_varchar.sql` | token hash widened for BCrypt/hex |
| V3 | `V3__username_case_insensitive.sql` | `lower(username)` unique index, replacing `citext` |
| V4 | `V4__master_catalogue.sql` | `manufacturer`, `medicine_catalogue`, `non_medical_item`, `supplier` |

V4 installs `pg_trgm` and `fuzzystrmatch` (both TRUSTED, so the DB owner needs no
superuser). `MigrationGuardTest` enforces contiguous versions, documented
headers, no credentials, and no user `INSERT`s.

### Not yet implemented

`inventory_batch`, `stock_transaction` (V5) · `purchase_order`,
`purchase_order_item` (V6) · `invoice`, `invoice_item` (V7, schema-only per v1
scope). Entities, services and endpoints for these do not exist yet.

---

## 10. Defects found by testing, and fixed

Each was found by running the code, not by reading it. Each now has a regression
test that was **mutation-verified** — reverting the fix makes the test fail.

1. **User search ANDed instead of ORed.** `Specifications.likeLower` was applied
   to both `username` and `fullName`, so `search=newph` returned **zero** rows
   while the term plainly appeared in the username. Fixed with
   `Specifications.textOr`. *Reverting → 2 targeted test failures.*
2. **`JwtService.parse` ignored its injected `Clock`.** The clock was passed to
   the issuer but not the parser, so validation read the system clock. Every
   expiry test had been passing for the wrong reason — every token looked
   expired. Fixed by passing the clock to the parser too.
3. **`GET /medicines?dosageForm=SYRUP` returned 500.** The batch name map was
   `Map.of()`, whose `get(null)` throws `NullPointerException` **by
   specification**. Any medicine without a manufacturer crashed the entire
   listing — the normal state for a generics-only entry. Fixed with a null-safe
   lookup, plus removal of a dead `toResponses` method holding the same
   unguarded call. *Reverting → reproduces the NPE.*
4. **`requiresPrescription` defaulted inconsistently.** The DTO returned `true`
   when omitted; `MDD.md` and the column default said `FALSE`. Aligned to the
   schema, and the patient-safety question logged rather than silently decided.

Still open, lower priority: invalid credentials return **400** (`BusinessRuleException`)
where **401** would be more correct REST semantics.

---

## 11. Test coverage

**90 tests, 0 failures** (up from 34 at the start of this work).

| Suite | Tests | What it protects |
|---|---|---|
| `AuthServiceTest` | 13 | Token-hash-only storage, username-enumeration timing, refresh rotation, replay revocation, idempotent logout |
| `JwtServiceTest` | 11 | Claim contents, exact expiry boundary, tampered/foreign/garbage tokens, secret-length validation |
| `MedicineServiceTest` | 10 | Duplicate tuple, referential validation, the `Map.of()` NPE regression, soft-delete semantics |
| `SpecificationsTest` | 6 | OR-vs-AND filtering, blank-as-absent, untyped-null avoidance |
| `CatalogueSearchTextTest` | 16 | Haystack joining, `Locale.ROOT`, token-boundary truncation |
| `PageResponseTest` | 9 | Pagination envelope |
| `GlobalExceptionHandlerTest` | 20 | Error mapping |
| `MigrationGuardTest` | 3 | Migration safety rules |
| `PharmacyApplicationTests` | 2 | Context loads |

`SpecificationsTest` runs against **real PostgreSQL** with
`@AutoConfigureTestDatabase(replace = Replace.NONE)` — an embedded database would
make the `lower(bytea)` regression pass for the wrong reason. Its fixtures use a
per-run random token and baseline-delta counts, so it does not require an empty
database.

---

## 12. Open decisions

1. **`users` has no `email` or `phone`.** MediPluse's `employee` table has both.
   There is currently no way to contact staff about a shift or a password reset.
2. **`medicine_catalogue.brand_name` is free text**, not a `brand` lookup table as
   in MediPluse. "Crocin" / "CRO-500" / "Crocin DS" are three unrelated brands,
   so brand-level filtering and reporting are not possible.
3. **`non_medical_item.category` is a `CHECK`-constrained enum**, not a lookup
   table. Adding a consumable category requires a migration and redeploy;
   "PPE" / "Diagnostics" / "Stationery" are open-ended per pharmacy.
4. **`invoice.payment_mode` has no `CHECK` constraint** — the only enumerated
   column in `MDD.md` relying on a comment instead. `billing_type` beside it has
   `chk_invoice_type`. Cheap to fix in V7, which is unwritten.
5. Unresolved domain decisions: reservation semantics, GST treatment,
   PO/invoice numbering, seed volume, `SUPER_ADMIN` password reset.
