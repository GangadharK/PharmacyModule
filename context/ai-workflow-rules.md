# AI Workflow Rules — Pharmacy Management System (PMS)

**Status:** Active
**Last updated:** September 2026

How work gets done in this module. Read alongside `code-standards.md` (how to write it) and `architecture-context.md` (what must stay true).

---

## 1. Working mode

Single-machine. One agent plans, writes the specs, implements, and verifies each unit. There is no separate Developer machine and no delegation handoff.

Each unit runs the same loop, in this order, without exception:

```text
1. Read AGENTS.md and the six context files
2. Read the unit's spec and its sub-specs
3. Read progress-tracker.md for current state
4. DATABASE      — migrations, entities, repositories. Seed if the feature needs data to verify
5. API LAYER     — service + controller + DTO. Verify each endpoint before the next one
6. UI            — components against the now-working API
7. INTEGRATION   — wire the flow end to end
8. VERIFY        — work the checklist in the spec
9. UPDATE        — progress-tracker.md and layer-tracker.md
```

Database before API before UI. Never the other way round. Building a screen against an imagined endpoint guarantees rework.

## 2. Per-unit verification gates

Each gate must pass before moving on. A gate that cannot be run is marked **awaiting verification** in the tracker and work continues — never silently skipped.

### After every database change

- [ ] Migration applies cleanly against an empty PostgreSQL 16 database, and the app boots with `ddl-auto: validate`
- [ ] Migration is idempotent where it claims to be
- [ ] `ddl-auto: validate` passes — Hibernate agrees with the migration
- [ ] Seed data present and sufficient to exercise the feature
- [ ] Every FK has an index; every `WHERE`/`ORDER BY` column is indexed

### After every API

- [ ] Correct status code: `200` read, `201` create with `Location`, `400` validation, `401` no token, `403` wrong role, `404` missing, `409` bad state transition
- [ ] Bean Validation actually fires (send a deliberately bad body and confirm `400`)
- [ ] Auth verified **three ways**: no token → 401, wrong-role token → 403, correct token → 200
- [ ] `cost_price` absent from the response body when the caller is a `CASHIER`
- [ ] Error body matches the `ApiError` envelope and has a non-null `code`
- [ ] Listed in the API inventory in `progress-tracker.md`

### After every UI component

- [ ] Renders with real backend data, not placeholder values
- [ ] Loading, empty, and error states all render
- [ ] Every interaction works
- [ ] Validation fires with field-level messages
- [ ] Tokens only — no hardcoded hex
- [ ] Cashier rule honoured if it shows price data
- [ ] `npm run build` passes (`tsc` under `strict`)

### After each unit

- [ ] Every sub-spec checklist individually ticked
- [ ] Combined flow works — not just components in isolation
- [ ] No invariant in `architecture-context.md` §6 is broken
- [ ] Ledger reconciliation returns zero rows if stock was touched
- [ ] Both trackers updated

## 3. Scope discipline

**The spec is the contract.** Implement it as written.

| Situation | Action |
| --- | --- |
| Spec is clear | Implement it. |
| Spec is ambiguous | **Stop.** Add an open question to `progress-tracker.md` and ask. Do not guess. |
| Implementation needs a field the spec lacks | Update the spec **and** `MDD.md` **first**, then write the code. |
| A tempting improvement appears | Note it in the tracker under "Deferred". Do not build it. |
| A spec item turns out to be wrong | Say so. Do not silently reinterpret it. |
| Two specs conflict | Stop. This is a planning bug, not an implementation detail. |

Guessing at ambiguous behaviour is the single most expensive mistake available here. A pharmacist acting on an invented rule is worse off than one waiting for an answer.

**Out of v1 scope** (from `project-overview.md` §5): billing, IPD requisitions, returns, the full report suite, invoice PDFs, notifications. If a unit seems to need one of these, that is a signal the unit is mis-specified. Raise it.

## 4. Context file maintenance

| Trigger | Action |
| --- | --- |
| A unit completes | Update `progress-tracker.md` and `layer-tracker.md` |
| A new endpoint ships | Add it to the API inventory |
| A new component ships | Add it to `layer-tracker.md` |
| Architecture changes | Update `architecture-context.md` **before** the code |
| A new entity or field | Update `MDD.md` schema **before** the code |
| A visual decision is made | Update `ui-context.md` |
| A convention is set | Update `code-standards.md`, including §9 if it departs from SCMS |
| A question is answered | Close the open question in the tracker |
| A lesson is learned | Add it here |

`MDD.md` is the schema's source of truth. If code and `MDD.md` disagree, one of them is a bug — find which before moving on.

## 5. Known traps in this codebase

Learned from `../SCMS` and from the decisions recorded in `MDD.md` §9.3. Each of these is a way the reference implementation is wrong.

1. **`Double` for money.** SCMS uses it throughout. Every price in Pharmacy is `BigDecimal`. No exceptions, no "just this one field".
2. **Unlocked FEFO.** SCMS reads batches then writes them with no lock. Pharmacy uses `SELECT … FOR UPDATE`. Do not remove the `@Lock` to "simplify".
3. **`@Valid` omitted.** SCMS declares `spring-boot-starter-validation` and then never uses `@Valid` on a controller, so its single `@NotBlank` does nothing. Pharmacy puts `@Valid` on every request body.
4. **N+1 in list mappers.** SCMS's `mapBatchesToDTOs` calls `findById` per row. Batch the lookups.
5. **`orElseThrow(IllegalArgumentException)` for everything.** Pharmacy has typed exceptions so the client can tell 404 from 400.
6. **Entities returned from controllers.** Leaks `password_hash` and `cost_price` by default.
7. **`@ManyToOne` everywhere.** Use plain `UUID` FK columns.
8. **`GenerationType.UUID`.** The app must generate the ID so a ledger row can reference the batch in the same transaction.
9. **`ddl-auto: update`.** Pharmacy uses `validate`. `update` silently mutates a developer's database.
10. **Two fetch styles in the frontend.** Raw `fetch` in some components, `ApiClient` in others. Pharmacy uses `ApiClient` only.
11. **`ToastContainer` never mounted.** Toasts silently did nothing in SCMS. Mount it and verify.
12. **Inconsistent `@PreAuthorize` casing** worked around by a converter that emits case variants — which silently grants extra authorities. UPPER_CASE only.
13. **Dead configuration.** SCMS ships a full dark-mode block nothing toggles and an `AGENTS.md` demanding AG Grid Enterprise the project does not license. Do not ship aspirational config.
14. **Docs in the source tree.** SCMS has a design `.md` inside `src/main/java`. Nothing but `.java` goes there.

## 6. When verification is impossible

The playbook's deferred-verification rule applies. If PostgreSQL, Maven, or Node is unavailable:

1. Write the code as specified.
2. Mark the unit **⚠️ Awaiting verification** in `progress-tracker.md`, naming exactly which gates did not run.
3. Carry on to the next unit. Do not block the whole project on one missing tool.
4. List every deferred gate in the tracker's "Open Items" so nothing is forgotten.

Never mark a unit ✅ that was not actually verified. An unverified unit marked done is worse than an absent unit, because it stops anyone from checking.

## 7. Commit discipline

- One logical unit per commit. Do not batch unrelated changes.
- Never commit without being asked.
- `git status` and `git diff` before every commit. Stage only intended files.
- No secrets, no `.env`, no real credentials — placeholders with env-var defaults only.
- Update `progress-tracker.md` in the same commit as the code it describes, so history never shows code without its tracker entry.

## 8. Asking for clarification

Ask when — and only when — one of these is true:

- The spec genuinely permits two behaviours with different consequences
- A change would touch an invariant in `architecture-context.md` §6
- A new dependency is needed that no spec mentions
- Verification found a contradiction between two context files
- The requested work is outside v1 scope

Do not ask about naming, formatting, or anything with an obvious conventional answer. Do not ask twice — record the answer in the context files so it does not need asking again.

## 9. End of project

When every unit is closed:

1. Read every file. Look for dead code, unused imports, stale `TODO`s, hardcoded values.
2. Audit types: no `any` in TypeScript, no raw types in Java.
3. Audit theme usage: no hardcoded colours in components.
4. Verify cross-boundary integrity — frontend types match actual backend responses.
5. Run the ledger reconciliation. It must return zero rows.
6. Close every open question, or carry it forward explicitly.
7. Update all six context files to describe the system as it actually is, with nothing left reading as "pending".
