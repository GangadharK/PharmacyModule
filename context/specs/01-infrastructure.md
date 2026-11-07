# 01 — Infrastructure & App Foundation

**Status:** Not started
**Spec:** `context/specs/00-build-plan.md` → Unit 01
**Persona:** `@pharmacy-infra` + `@pharmacy-backend` + `@pharmacy-frontend`

---

## Goal

Turn the bare Spring Initializr scaffold into a running Pharmacy application: a PostgreSQL-backed Spring Boot 4 service with Flyway, typed error handling, OpenAPI, and a Vite + React 19 shell that boots to a styled login screen, with the API client and state plumbing in place for Unit 02 to attach authentication to.

Nothing in this unit is a Pharmacy feature. It is the floor everything else stands on.

## Design

### Backend

- Port `8081`. Single `pharmacy` schema. All config via `${ENV:default}` placeholders in `application.yml`.
- `ddl-auto: validate` + Flyway. A migration that Hibernate disagrees with fails at boot — that is the point.
- Every uncaught exception becomes an `ApiError` JSON envelope with a stable machine-readable `code`. The frontend branches on `code`, never on message text.
- UUIDs stored as `BINARY(16)` via a JPA `AttributeConverter`. This lands in Unit 01 because the first entity in Unit 02 depends on it.
- Actuator health at `/actuator/health`. Swagger UI at `/swagger-ui.html`.

### Frontend

- `Pharmacy/frontend/` — Vite + React 19 + TypeScript strict, Tailwind, shadcn/ui, Zustand.
- Theme tokens exactly as `ui-context.md` §3: teal `--primary`, warm neutral surface, `dark` class support declared in `index.html` but not toggled.
- `AppShell` with a 64px↔256px collapsible `Sidebar`, a `Header` with avatar and role badges, and `PageHeader` per page.
- `LoginPage` renders fully styled and validates locally, but submits nowhere — auth is Unit 02. A visible "authentication not yet wired" state makes the boundary unambiguous rather than looking broken.
- `ApiClient` in `lib/api.ts` with 401 → logout already implemented, so Unit 02 only has to supply the token.
- `ToastContainer` **mounted** and verified. SCMS ships one that is never mounted, so its toasts silently do nothing.

### Structural

- `src/main/resources/application.properties` is replaced by `application.yml`.
- Empty scaffold leftovers `templates/` and `static/` are deleted. This is a SPA + API.
- `pom.xml` drops `spring-boot-starter-batch` and `spring-boot-starter-amqp` — no batch jobs, no broker. `spring-boot-starter-validation` is **added explicitly**; the generated POM omits it, which is the root cause of SCMS's dead `@NotBlank`.
- The empty `PharmacyApplicationTests` context test is replaced with a real one.

## Implementation

### 01a — Backend foundation
`pom.xml`, `application.yml`, `main` class, package layout, `UuidConverter`, CORS config, actuator, OpenAPI config, deletion of scaffold leftovers, `PharmacyApplicationTests`.

### 01b — Error handling
`ApiError` record, the five exception types, `GlobalExceptionHandler`, `PageResponse<T>` envelope.

### 01c — Frontend shell
Vite scaffold, Tailwind tokens, shadcn primitives, `cn()` util, `AppShell`/`Sidebar`/`Header`/`PageHeader`, `ToastContainer`, `auth.store`, `ui.store`, router, `ProtectedRoute`.

### 01d — Auth UI
`LoginPage` — layout, validation, role hints, disabled submit with an explanatory note.

## Dependencies

| Dependency | Why |
| --- | --- |
| `spring-boot-starter-validation` | Bean Validation. **Missing from the generated POM.** Without it `@Valid` is a no-op |
| `spring-boot-flyway`, `flyway-core`, `flyway-database-postgresql` | Schema migrations. All three required — see `01a`-runs SQL and uses `ddl-auto: update` |
| `springdoc-openapi-starter-webmvc-ui` `3.1.0` | Already present. The contract the frontend is built against |
| `io.jsonwebtoken:jjwt-api` + `jjwt-impl` + `jjwt-jackson` | **Unit 02.** Added in Unit 01 but unused until then so the build is warm — flag this if the reviewer objects |
| `react` `19.x`, `react-dom` `19.x` | Up from SCMS's 18 |
| `react-router-dom` `7.x` | Routing |
| `tailwindcss` `3.4.x` + `postcss` + `autoprefixer` | SCMS's stack. Tailwind 4 changes the config shape; stay on 3.4 for the proven path |
| `ag-grid-community` `31.x` + `ag-grid-react` | **Community, not Enterprise.** SCMS's AGENTS.md mandates Enterprise; this project does not license it |
| `zustand` `5.x` | Auth and UI state |
| `react-hook-form` `7.x` + `@hookform/resolvers` + `zod` `4.x` | Forms |
| `lucide-react` | Icons |
| `radix-ui` primitives | shadcn/ui foundation |
| `class-variance-authority`, `clsx`, `tailwind-merge` | shadcn/ui variants and `cn()` |

**Removed:** `spring-boot-starter-batch`, `spring-boot-starter-amqp`, `asciidoctor-maven-plugin`, `spring-boot-starter-restdocs` and the RestDocs/asciidoctor tooling. There are no batch jobs and no broker in v1 — `MDD.md` §2.

## API Inventory

No business endpoints. The health and docs endpoints below are the entire surface of this unit.

| Method | Path | Auth | Description | Spec Ref |
| --- | --- | --- | --- | --- |
| GET | `/actuator/health` | None | Liveness + DB connectivity | `01a-backend-foundation.md` |
| GET | `/swagger-ui.html` | None | OpenAPI UI | `01a-backend-foundation.md` |
| GET | `/v3/api-docs` | None | OpenAPI JSON | `01a-backend-foundation.md` |
| GET | `/api/v1/system/ping` | None | **Deliberately unauthenticated** until Unit 02 | `01b-error-handling.md` |

`/api/v1/system/ping` exists so the frontend's `ApiClient` has something real to call during Unit 01, and so the error envelope can be proven end to end. It returns `ApiError` when `?fail=true` is passed, which is how `01b` is verified. Unit 02 replaces it with real auth.

## Verify when done

### Backend
- [ ] `mvn -q clean verify` passes
- [ ] `mvn spring-boot:run` starts on `8081` with no stack trace
- [ ] Flyway reports migrations applied; `ddl-auto: validate` does not throw
- [ ] `GET /actuator/health` returns `{"status":"UP"}`
- [ ] `GET /v3/api-docs` returns valid OpenAPI JSON
- [ ] `GET /swagger-ui.html` loads
- [ ] `GET /api/v1/system/ping` returns 200 with a plain body
- [ ] `GET /api/v1/system/ping?fail=true` returns the `ApiError` envelope with `code` populated
- [ ] A POST to an unmapped path returns the same envelope, 404, not a container error page
- [ ] *(removed — `UuidConverter` deleted in Unit 02, decision 22)*
- [ ] `GlobalExceptionHandlerTest` covers every exception type → status and `code`
- [ ] No credential is hardcoded in any committed file

### Frontend
- [ ] `npm run build` passes with `tsc` under `strict`, no `any`
- [ ] `npm run dev` serves on `5163` and the login screen renders
- [ ] Sidebar collapses 64↔256px and the transition is smooth
- [ ] `LoginPage` validates a bad email and shows a field error
- [ ] `ToastContainer` is mounted — triggering a toast actually shows one
- [ ] No hardcoded hex in any `.tsx`
- [ ] `ApiClient` reaches `/api/v1/system/ping` through the Vite proxy

### Integration
- [ ] With the backend running, the login screen's connectivity check renders the ping response
- [ ] `progress-tracker.md` and `layer-tracker.md` updated

## Notes

- **PostgreSQL must be up for the backend gates.** See `progress-tracker.md` open question 1. If it cannot be provisioned, mark this unit ⚠️ Awaiting verification and name the gates that did not run — per `ai-workflow-rules.md` §6.
- The unit produces no Pharmacy data, so there is nothing to seed here. Seeding starts in Unit 02.
- `V6__seed_reference_data.sql` grows across units rather than being split. Decided here, recorded in the build plan.
