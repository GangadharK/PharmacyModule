# AGENTS — Pharmacy Management System (PMS)

## Application Building Context

**Read the following files in order before implementing or making any architectural decision:**

1. `context/project-overview.md` — Product definition, goals, features, and scope
2. `context/architecture-context.md` — System structure, boundaries, storage model, and invariants
3. `context/ui-context.md` — Theme, colors, typography, and component conventions
4. `context/code-standards.md` — Implementation rules and conventions
5. `context/ai-workflow-rules.md` — Development workflow, scoping rules, and delivery approach
6. `context/progress-tracker.md` — Current phase, completed work, open questions, and next steps

**Then read** `context/layer-tracker.md` for granular per-component and per-endpoint status, and the current unit's spec under `context/specs/`.

**Update `context/progress-tracker.md` after each meaningful implementation change.**
**Update `context/layer-tracker.md` as each component, endpoint, and migration lands.**
**If implementation changes the architecture, scope, or standards documented in the context files, update the relevant file before continuing.**

`context/MDD.md` is the source of truth for the database schema. If code and `MDD.md` disagree, one of them is a bug — find which before moving on.

---

## What This Is

A standalone, client-facing pharmacy management system. Spring Boot 4 + Java 21 backend on **PostgreSQL 16**, with React 19 + Vite + TypeScript frontend. `Pharmacy/frontend/` is the only UI. `practi/frontend` belongs to the HMS and is not this module.

v1 delivers three features: **medicine and supplier catalogue**, **batch inventory with FEFO**, and **purchase entry with OCR**. Billing, returns, IPD requisitions, the full report suite and notifications are out of scope — see `project-overview.md` §5.

---

## Global Rules for All Agents

1. **Money is `BigDecimal`.** `DECIMAL(10,2)`. Never `Double`, never `float`. SCMS uses `Double` throughout; that is precisely the bug being avoided.
2. **Stock quantities are never deleted.** Every change to `inventory_batch.quantity_available` writes a `stock_transaction` row **in the same transaction**. The ledger is append-only and must always reconcile.
3. **FEFO reads are locked.** `SELECT … FOR UPDATE` via `@Lock(PESSIMISTIC_WRITE)`. SCMS's unlocked version has a real concurrent-dispense race. Never remove the lock to "simplify".
4. **Non-negative stock is enforced in the database.** `CHECK (quantity_available >= 0)`, which PostgreSQL always enforces. (Under the discarded MySQL plan this needed a 8.0.16 floor; it is now unconditional.)
5. **Entities never leave the service layer.** A JPA entity returned from a controller leaks `password_hash` and `cost_price` by default.
6. **`@Valid` on every `@RequestBody`.** SCMS declares `spring-boot-starter-validation` and then never uses `@Valid`, so its one `@NotBlank` does nothing. Not copied.
7. **The frontend never holds a cost price it is not entitled to.** The `CASHIER` role must receive a response body with no `cost_price` field at all. This is a server-side guarantee, not a UI convention.
8. **`@PreAuthorize` values are UPPER_CASE only.** SCMS mixes case and works around it in the JWT converter by emitting case variants, which silently grants extra authorities.
9. **No `any` in TypeScript.** `strict`, `noUnusedLocals`, `noUnusedParameters` are on and `tsc` runs before the build.
10. **No hardcoded colours in components.** Tokens only, per `ui-context.md` §3.
11. **No secrets in code or config.** Env-var placeholders with dev defaults only. SCMS hardcodes database credentials; not copied.
12. **Migrations are append-only.** Never edit an applied migration. Write a new one.
13. **Documentation lives in `context/`.** No `.md` files under `src/`.
14. **The spec is the contract.** Read `context/specs/00-build-plan.md` for the unit sequence, then the current unit's spec and its sub-specs. Implement as written. If the spec is ambiguous, record an open question in `context/progress-tracker.md` and ask. Do not guess at behaviour a pharmacist would act on.
15. **Verify every migration by starting the app.** `ddl-auto: validate` compares the entity mapping against the live schema, so a wrong type or a missing column stops the application from booting rather than surfacing later as bad data. Recreate the database and boot it: `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev`. `MigrationGuardTest` covers what a boot cannot — contiguous versions, documented headers, and no credentials in a migration. `MDD.md` §3.10.
16. **Never put a credential in a migration.** Flyway runs in production as well as in development. Reference and demo data is seeded by code gated on a flag (`app.seed.enabled`), not by an `INSERT`.

---

## Deliberate Departures from SCMS

`../SCMS` is **reference material for entities, fields, and frontend idiom — not a template.** Pharmacy is a client-facing standalone service, not a multi-tenant modular monolith. The full list with rationale is in `code-standards.md` §9 and `MDD.md` §9.3. The ones most likely to be copied by mistake:

| SCMS | Pharmacy |
| --- | --- |
| 7 Postgres schemas, modular monolith | One `pharmacy` schema, flat layered packages |
| `tenant_id` everywhere + RLS + `TenantContext` | No tenancy. Slim `CurrentUserContext` for `performed_by` only |
| `Double` money | `BigDecimal` |
| Unlocked FEFO | `FOR UPDATE` |
| Keycloak + realm JSON, 6 containers | Self-issued JWT (jjwt) |
| Zero tests | Real tests on every critical path |
| `ddl-auto: update` + hand-run SQL | Flyway + `ddl-auto: validate` |
| `AG Grid Enterprise` required in its own AGENTS.md | `AG Grid Community` — not licensed here |
| Raw `fetch` in some components | `ApiClient` only |
| `ToastContainer` never mounted | Mounted and verified |

---

## Agent Personas

Single-machine mode: one agent plans, implements, and verifies. Adopt the persona matching the directory you are working in.

### `@pharmacy-backend` — `src/main/java/**`

**Mindset:** rigorous, ACID-focused, audit-conscious. This system manages drugs whose expiry dates matter.

- Layered: `controller → service → repository`. No upward dependencies. No skipping a layer.
- `@Transactional` lives in `service/` only, and only in `service/`.
- Pure logic — `FefoAllocator`, `InvoiceTextParser`, `MedicineMatcher` — is a plain class with no Spring annotations, no repository, no injected clock. Constructed with `new` in its test.
- Long classes get `// ==================== Section ====================` dividers.
- **Forbidden:** `@ManyToOne`. Use plain `UUID` FK columns.
- **Forbidden:** `GenerationType.UUID`. The app must generate the ID so a ledger row can reference the batch in the same transaction.
- **Forbidden:** returning a JPA entity from a controller.
- **Forbidden:** `orElseThrow(() -> new IllegalArgumentException(...))`. Use the typed exceptions in `code-standards.md` §2.6.

### `@pharmacy-frontend` — `frontend/src/**`

**Mindset:** component-reuse-first, type-strict, accessible. A pharmacist reads this on a counter under bad lighting.

- shadcn/ui + Tailwind. Reuse `components/ui/*` before writing anything new; never edit a `components/ui/*` primitive to suit one screen.
- One `ApiClient` in `lib/api.ts`. All network calls go through it. A 401 triggers logout centrally.
- Every data component handles loading, empty, and error as explicit early returns.
- React Hook Form + Zod on every form.
- AG Grid **Community** for large tables. Never `AG Grid Enterprise`.
- **Forbidden:** `any`. **Forbidden:** hardcoded hex in a `.tsx`. **Forbidden:** branch on an error's message text — branch on `error.code`.

### `@pharmacy-infra` — `pom.xml`, `application.properties`, `db/migration/`, build config

**Mindset:** reproducible, least-privilege, verifiable.

- Flyway migrations with a header comment explaining what the migration does and what it depends on.
- `ddl-auto: validate`, never `update`.
- Secrets via `${ENV:default}` placeholders. `.env` is gitignored.
- **Forbidden:** credentials in `application.properties`, Docker Compose, or any committed file.

---

## Context-Switching Protocol

| Working in | Act as | Load first |
| --- | --- | --- |
| `src/main/java/**/controller\|service` | `@pharmacy-backend` | This file, `code-standards.md`, `architecture-context.md` §6 invariants |
| `src/main/java/**/entity\|repository` | `@pharmacy-backend` | `MDD.md` schema, `code-standards.md` §2.1–2.2 |
| `src/main/resources/db/migration/` | `@pharmacy-infra` | `MDD.md` §5 schema, `architecture-context.md` §5 |
| `frontend/src/components/ui\|lib\|store` | `@pharmacy-frontend` | `ui-context.md` |
| `frontend/src/features/**` | `@pharmacy-frontend` | `ui-context.md`, the unit's sub-specs |
| `frontend/` build config | `@pharmacy-infra` | `ui-context.md` §2 |

If a task crosses a boundary, the backend contract is settled first — the OpenAPI schema at `/swagger-ui.html` is the agreement. Implement the endpoint, verify it, then build the screen against it. Never the reverse.

---

## Working Agreement

- Read the six context files and the unit spec before writing code. Every time, not just the first.
- Follow the per-unit order in `ai-workflow-rules.md` §1: **database → API → UI → integration → verify → update trackers.**
- Verify each endpoint before the next one. Verify each component before the next one.
- Never mark a unit ✅ that was not actually verified. If verification could not run, mark it ⚠️ Awaiting verification and name the gates that did not run.
- Do not commit unless asked. Stage only `Pharmacy/` paths — the parent workspace is a shared monorepo with unrelated changes.
