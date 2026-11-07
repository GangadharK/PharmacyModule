# 01c — Frontend Shell

**Parent:** `01-infrastructure.md`
**Persona:** `@pharmacy-frontend`

## Goal

Stand up `Pharmacy/frontend/` as a Vite + React 19 + TypeScript-strict app with the Pharmacy theme applied, the shadcn/ui primitives in place, an `AppShell` with a collapsible sidebar, a mounted `ToastContainer`, and the `ApiClient` and Zustand stores that Unit 02 will attach to.

## Design

### Stack

React 19, Vite 6, TypeScript 5.x `strict` + `noUnusedLocals` + `noUnusedParameters`, Tailwind 3.4, shadcn/ui, Zustand 5, react-router-dom 7, AG Grid **Community** 31.

`npm run build` runs `tsc && vite build` — the type check is a build gate, not a lint suggestion.

### Theme

Exactly `ui-context.md` §3. Teal `--primary`, warm neutral surface, `dark` declared in `index.html` but never toggled. Theme **dormant, not wired** — SCMS ships a full dark-mode block that nothing activates, and `ui-context.md` §9 records dark mode as deferred. Declaring the class costs nothing; building a toggle nobody asked for is scope creep.

### Layout

```text
┌──────────────────────────────────────────────────────┐
│  Sidebar │  Header                                   │
│  64/256  ├───────────────────────────────────────────┤
│         │  PageHeader (title · description · actions)│
│         │  ─────────────────────────────────────────  │
│         │  content                                    │
└──────────────────────────────────────────────────────┘
```

Only `main` scrolls. The sidebar and header are fixed. This is what makes a data grid usable — a page-level scrollbar that also scrolls the nav is the most common SCMS-style layout failure.

### Navigation

| Item | Route | Role | Available from |
| --- | --- | --- | --- |
| Dashboard | `/dashboard` | all | placeholder page, Unit 01 |
| Medicines | `/medicines` | all | Unit 04 |
| Suppliers | `/suppliers` | all | Unit 04 |
| Manufacturers | `/manufacturers` | all | Unit 04 |
| Non-Medical Items | `/non-medical-items` | all | Unit 04 |
| Inventory Batches | `/inventory/batches` | all | Unit 07 |
| FEFO Preview | `/inventory/fefo` | all | Unit 07 |
| Cycle Counts | `/inventory/cycle-counts` | all | Unit 09 |
| Low Stock | `/alerts/low-stock` | MANAGER, ADMIN | Unit 09 |
| Near Expiry | `/alerts/near-expiry` | all | Unit 09 |
| Purchase Orders | `/purchase-orders` | all | Unit 11 |
| OCR Upload | `/purchase-orders/ocr` | PHARMACIST+ | Unit 11 |
| Users | `/settings/users` | SUPER_ADMIN | Unit 02 |

Routes whose feature has not shipped render a "not yet available in this build" placeholder. Grouped under **Catalogue**, **Inventory**, **Purchasing**, **Administration**, matching `ui-context.md` §4. Disabled items are dimmed and not navigable — a visible-but-broken link is worse than an absent one.

## Implementation

### Scaffold

```bash
cd /Users/rockerss/ws/practi/Pharmacy
npm create vite@latest frontend -- --template react-ts
```

Then: Tailwind 3.4 + `tailwind.config.js` with the `ui-context.md` §3 tokens, `postcss.config.js`, `cn()` in `lib/utils.ts`, path alias `@/` → `src/` in both `tsconfig.json` and `vite.config.ts`.

`vite.config.ts` proxies `/api` and `/actuator` to `http://localhost:8081` so the dev server needs no CORS pre-flight and cookies work.

### shadcn/ui primitives

Initialise shadcn and add exactly these:

`badge` `button` `card` `dialog` `dropdown-menu` `input` `label` `select` `separator` `avatar` `scroll-area` `textarea` `toast`

Add others just-in-time. `command` and `combobox` are tempting for the FEFO medicine picker in Unit 07 — add them there, not now.

**Never edit a `components/ui/*` primitive to suit one screen.** `ui-context.md` §2. If a primitive cannot do what a screen needs, wrap it.

### `lib/api.ts`

Per `code-standards.md` §3.2. One `ApiClient` class. `baseUrl` defaults to `/api/v1` — the Vite proxy makes this work with no env var in dev and no CORS.

- Access token read from `useAuthStore.getState().accessToken` at call time, never captured at construction.
- 401 → `useAuthStore.getState().logout()` then throw `UnauthorizedError`.
- Non-2xx → parse the `ApiError` envelope; fall back to `{ code: 'UNKNOWN', message: 'HTTP <status>' }` if the body is not JSON (a proxy or gateway may return HTML).
- Export a singleton `apiClient`.

### `store/auth.store.ts`

Zustand, **not persisted** — no `localStorage`, no `sessionStorage`. A JWT in web storage is readable by any XSS, and `ui-context.md` §8 is explicit about it. `accessToken`, `user`, `isAuthenticated`, `login()`, `logout()`, `setUser()`.

In-memory means a page refresh logs the user out. Acceptable for v1 and a deliberate trade. State it in the README rather than quietly discovering it.

### `store/ui.store.ts`

`sidebarCollapsed: boolean`, `toggleSidebar()`. Persisted to `localStorage` — a UI preference carries no security implication.

### `store/toast.store.ts`

Zustand-backed queue plus a `toast` helper object mirroring shadcn's imperative API: `toast.success()`, `toast.error()`, `toast.warning()`, `toast.info()`, `toast.dismiss(id)`.

### `components/layout/`

`AppShell.tsx` — grid, `Sidebar` fixed left, `Header` fixed top, `main` the only scroll container.
`Sidebar.tsx` — 64px collapsed / 256px expanded, `w-*` transition, nav groups with expand/collapse, active item highlighted by route match, role filter.
`Header.tsx` — page breadcrumb, avatar `DropdownMenu` with name, role badges, logout.
`PageHeader.tsx` — `title`, `description?`, `actions?: ReactNode` slot.

### `components/ui/ToastContainer` mounting

`ToastContainer` is rendered in `AppShell`, above `{children}`:

```tsx
<div className="min-h-screen bg-background">
  <Sidebar />
  <div className="flex flex-1 flex-col pl-16 lg:pl-0">
    <Header />
    <main className="flex-1 overflow-y-auto p-6">
      <Outlet />
    </main>
  </div>
  <ToastContainer />
</div>
```

Verify it with a temporary toast on mount. SCMS defines a toast system and never mounts the container, so `toast.success()` is a silent no-op there. The failure is invisible, which is why it survived.

### `ProtectedRoute.tsx`

Redirects to `/login?next=<path>` when unauthenticated. Honours `next` on success. Because `auth.store` is not persisted, this fires on every hard refresh until Unit 02 adds the silent-refresh call — expected at this stage.

### `pages/DashboardPage.tsx`

Placeholder. Card saying the module is up. Not a fake dashboard with invented numbers.

### Types

`types/api.ts` — `ApiError`, `PageResponse<T>`, `User`, `Role`, `Uuid = string`. `Uuid` as a branded alias so a bare `string` cannot be passed where a UUID belongs:

```ts
export type Uuid = string & { readonly __brand: 'Uuid' };
```

## Dependencies

See the parent spec table. No AG Grid yet — first used in Unit 07. Adding it now pulls ~1MB into a bundle that renders no grid.

## Verify when done

- [ ] `npm run build` passes; `tsc` reports no error and no warning
- [ ] Grep for `: any` and `as any` in `src/` — zero matches
- [ ] `npm run dev` serves on `5163`
- [ ] Sidebar collapses 64↔256px, transition is smooth, state survives a refresh
- [ ] Only `main` scrolls — the header does not move when the page scrolls
- [ ] Nav groups expand and collapse
- [ ] Unimplemented routes render the "not yet available" placeholder, dimmed and not navigable
- [ ] Role-filtered nav hides `/settings/users` without `SUPER_ADMIN`
- [ ] `ToastContainer` is mounted and a toast actually appears
- [ ] `apiClient.get('/system/ping')` reaches the backend through the Vite proxy and returns `PONG`
- [ ] A 401 from any call clears the auth store
- [ ] Grep for a hex literal in `*.tsx` — zero matches outside `index.css`
- [ ] Grep for `localStorage` in `auth.store` — zero matches
- [ ] `LoginPage` is reachable at `/login` and `/` redirects to it
