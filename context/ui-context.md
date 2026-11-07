# UI Context — Pharmacy Management System (PMS)

**Status:** Active
**Last updated:** September 2026

Derived from the `../SCMS/apps/web-internal` frontend, fine-tuned for a pharmacy counter. Where this file and SCMS disagree, this file wins.

---

## 1. Design intent

A pharmacist dispenses under time pressure and reads batch numbers and expiry dates for a living. A cashier rings up prescriptions quickly and repeatedly. Neither is an analyst.

So the interface optimises for three things, in priority order:

1. **Legibility of critical values.** Expiry date, batch number and available quantity must be readable at a glance from two feet away, colour-coded by urgency. Nothing else on screen may compete with them.
2. **Fast repeated action.** Search, add, confirm. The dispensing and purchase flows are done hundreds of times a shift — every extra click is felt.
3. **Data density.** Pharmacists want to see a lot at once, not a card per item.

It is deliberately **not** a consumer app. No large hero imagery, no generous whitespace, no marketing motion.

## 2. Design tokens

### 2.1 Colour

shadcn/ui on Radix, with the HSL channel-triplet convention from SCMS: raw channels in `index.css`, wrapped as `hsl(var(--x))` in `tailwind.config.js`.

```css
:root {
  --background: 0 0% 100%;
  --foreground: 222.2 84% 4.9%;

  /* Pharmacy teal — replaces SCMS's near-black --primary.
     A hospital pharmacy reads as clinical; pure black primary actions
     feel like an admin console. Teal is the one departure from SCMS. */
  --primary: 187 72% 33%;
  --primary-foreground: 0 0% 100%;

  --secondary: 210 40% 96.1%;
  --secondary-foreground: 222.2 47.4% 11.2%;
  --muted: 210 40% 96.1%;
  --muted-foreground: 215.4 16.3% 46.9%;
  --accent: 210 40% 96.1%;
  --accent-foreground: 222.2 47.4% 11.2%;
  --destructive: 0 72% 45%;
  --destructive-foreground: 0 0% 100%;
  --success: 142 71% 35%;
  --success-foreground: 0 0% 100%;
  --warning: 32 95% 38%;
  --warning-foreground: 0 0% 100%;

  --card: 0 0% 100%;
  --card-foreground: 222.2 84% 4.9%;
  --popover: 0 0% 100%;
  --popover-foreground: 222.2 84% 4.9%;
  --border: 214.3 31.8% 91.4%;
  --input: 214.3 31.8% 91.4%;
  --ring: 187 72% 33%;
  --radius: 0.5rem;
}
```

`--success` and `--warning` are additions. shadcn's default palette has no success or warning, which forces semantic colours into ad-hoc `bg-green-100 text-green-700` strings scattered across components. Promoting them to tokens is the main fine-tuning change over SCMS.

**No dark mode in v1.** SCMS defines a full `.dark` block that nothing ever toggles — dead configuration that implies a feature that does not exist. Omitted rather than copied.

### 2.2 Status colour mapping

Semantic meaning is never left to a component author's judgement. These are the fixed mappings, and they are the same mappings the backend `status` enum uses.

**Batch / `inventory_batch.status`:**

| Status | Badge classes | Icon |
| --- | --- | --- |
| `ACTIVE` | `bg-success/10 text-success border-success/20` | — |
| `QUARANTINED` | `bg-warning/10 text-warning border-warning/20` | `ShieldAlert` |
| `EXPIRED` | `bg-destructive/10 text-destructive border-destructive/20` | `CalendarX` |
| `DEPLETED` | `bg-muted text-muted-foreground` | — |

**Expiry urgency** — derived from days remaining, computed **client-side for display only**. The backend is still the authority; this is a rendering rule, never a data source.

| Days remaining | Badge classes | Label |
| --- | --- | --- |
| `< 0` | `bg-destructive text-destructive-foreground` | `EXPIRED` |
| `≤ 30` | `bg-destructive/10 text-destructive` | `N days` |
| `≤ 60` | `bg-warning/10 text-warning` | `N days` |
| `≤ 90` | `bg-amber-100 text-amber-800` | `N days` |
| `> 90` | `bg-success/10 text-success` | `N days` |

**Purchase order / `purchase_order.status`:**

| Status | Badge classes |
| --- | --- |
| `DRAFT`, `DRAFT_OCR` | `bg-muted text-muted-foreground` |
| `PENDING_APPROVAL` | `bg-warning/10 text-warning border-warning/20` |
| `APPROVED` | `bg-success/10 text-success border-success/20` |
| `CANCELLED` | `bg-destructive/10 text-destructive border-destructive/20` |

**`DRAFT_OCR` lines carrying `ocr_confidence < 0.6`** get `border-warning/40` plus a `TriangleAlert` icon and the literal text `Needs review`. The pharmacist must be able to see, without clicking, which OCR lines they still have to check.

### 2.3 Typography

Tailwind defaults plus two deliberate additions. No custom font family is loaded — a pharmacy counter terminal should not wait on a webfont.

| Element | Classes | Note |
| --- | --- | --- |
| Page title | `text-2xl font-bold tracking-tight` | From `PageHeader` |
| Section header | `text-lg font-semibold` | |
| Card title | `text-base font-semibold` | Overrides shadcn's `text-2xl` default |
| Body | `text-sm` | Base size. Pharmacy UI is dense. |
| Helper / caption | `text-xs text-muted-foreground` | |
| **Batch number, PO number, invoice number** | `font-mono text-xs` | The repo-wide "this is an identifier" signal |
| **Money** | `font-mono tabular-nums` | `tabular-nums` so columns of figures align |

`tabular-nums` on money is an addition over SCMS. Price columns that jitter as digits change are genuinely hard to scan.

### 2.4 Spacing and radius

Tailwind's default scale. Radius: `--radius: 0.5rem`, `lg` = 0.5rem, `md` = 0.375rem, `sm` = 0.25rem.

Layout rhythm unit is `h-16` (64px) — the header and the sidebar logo row both use it.

## 3. Layout

```text
┌───────────────────────────────────────────────────────────────┐
│  Header  h-16   [PMS]  🔍 global search    🔔   (avatar ▾)    │
├──────────┬────────────────────────────────────────────────────┤
│ Sidebar  │ PageHeader  (title, description, actions)         │
│  w-64    ├────────────────────────────────────────────────────┤
│  / w-16  │                                                     │
│          │  main  flex-1 overflow-auto p-6                    │
│  Dash    │                                                     │
│  Catalog │   ┌───────────────────────────────────────────┐    │
│  Inven.  │   │  content: grids, forms, tables, cards    │    │
│  Purch.  │   └───────────────────────────────────────────┘    │
│  Alerts  │                                                     │
│  Settings│                                                     │
├──────────┴────────────────────────────────────────────────────┤
```

Structure, taken from SCMS:

- Root is `flex h-screen overflow-hidden`. **The body never scrolls — only `<main className="flex-1 overflow-auto p-6">` does.** Feature pages therefore must not set their own page-level height.
- Sidebar collapses `w-64` ↔ `w-16` with `transition-all duration-300`, chevron `rotate-180`.
- Every protected route is nested under `/app` and rendered through `MainLayout`, which is the auth gate. No separate `<RequireAuth>` wrapper.
- Unauthenticated access redirects to `/login?next=<encoded path>`, and `LoginPage` reads `next` to return the user where they were going.

### 3.1 Sidebar navigation

`NavItem` is `{ label, path, icon, end?, children? }` where `icon` is **pre-rendered JSX with its own size class** — `h-5 w-5` at top level, `h-4 w-4` for children, `h-3 w-3` for grandchildren. Children are declared as module-level `const` arrays. `NavItemComponent` is self-recursive and takes a `level` for indentation. Groups default to expanded.

```text
Dashboard          LayoutDashboard
Catalogue          BookOpen
  ├ Medicines      Pill
  ├ Non-Medical    Syringe
  ├ Manufacturers  Factory
  └ Suppliers      Truck
Inventory          Package
  ├ Batches        Layers
  ├ FEFO Preview   ListOrdered
  └ Cycle Counts   ClipboardCheck
Purchasing         FileText
  ├ Purchase Orders  Receipt
  └ OCR Upload       ScanLine
Alerts             BellRing
  ├ Low Stock        PackageX
  └ Near Expiry      CalendarClock
Settings           Settings
```

The active-item treatment is a single literal, repeated verbatim — `bg-primary text-primary-foreground` when active, `text-muted-foreground hover:bg-accent hover:text-accent-foreground` when not. Do not vary it per item.

### 3.2 Route map

```text
/login                      LoginPage                       (public)

/app                        → redirect /app/dashboard
/app/dashboard              DashboardPage
/app/catalog
  ├ (index)                 CatalogueOverview
  ├ medicines               MedicinesPage
  ├ non-medical             NonMedicalItemsPage
  ├ manufacturers           ManufacturersPage
  └ suppliers               SuppliersPage
/app/inventory
  ├ (index)                 BatchesPage
  ├ fefo                    FefoPreviewPage
  └ cycle-counts            CycleCountsPage
/app/purchasing
  ├ (index)                 PurchaseOrdersPage
  └ ocr                     OcrUploadPage
/app/alerts
  ├ (index)                 LowStockPage
  └ near-expiry             NearExpiryPage
/app/settings                SettingsPage
/app/*                       NotFoundPage
```

Feature pages are imported **only through the feature barrel** (`@/features/catalogue`), never deep-imported. Unbuilt screens get `PlaceholderPage` with `title` + `description`.

No `React.lazy` / `Suspense` code splitting in v1.

## 4. Component library

Stock shadcn/ui on Radix, matching SCMS's exact component set so the two codebases stay recognisably siblings:

`avatar` · `badge` · `button` · `card` · `dialog` · `dropdown-menu` · `input` · `label` · `scroll-area` · `select` · `separator` · `textarea` · `toast`

Two SCMS defects **not** carried over:

- SCMS never mounts a `ToastContainer`, so every `toast.success()` silently does nothing. Here the container **is** mounted in `main.tsx`, and firing a toast is part of the frontend shell unit's verification.
- SCMS defines `select.tsx` but then hand-rolls native `<select>` elements with inlined classes throughout. Here: use the shadcn `Select`. If a native control is genuinely needed, style it once in `components/ui/` rather than ad hoc at each call site.

### 4.1 Pharmacy-specific components

These are the components that make this a pharmacy UI rather than a generic admin UI. Each lives in its feature folder and is individually tracked in `layer-tracker.md`.

| Component | Purpose |
| --- | --- |
| `ExpiryBadge` | Colour-coded days-until-expiry. Used in grids, tables and detail panels. |
| `ScheduleBadge` | `H` / `H1` / `OTC` indicator on a medicine. H and H1 get `bg-warning/10 text-warning` — they need a prescription. |
| `FefoAllocationTable` | Shows the FEFO split: which batch, how many, at what price. Renders green when fulfillable, red with the shortfall when not. |
| `StockLevelBar` | Available vs `reorder_level`, with a red zone below the threshold. |
| `OcrConfidenceBar` | Per-line OCR confidence with the 0.6 review threshold marked. |
| `CostPriceCell` / `SellingPriceCell` | Separate AG Grid cell renderers. The cost one **renders nothing at all** for a Cashier — see §5. |
| `RoleGate` | `{canAccessStock ? <StockAdjuster/> : null}`. Hides controls the caller's role cannot use. |

## 5. Money and the Cashier rule

The backend omits `cost_price` entirely from Cashier-facing responses (`architecture-context.md` §4.3). The frontend must not reintroduce it:

- `CostPriceCell` returns `null` when the current user is a `CASHIER`. It does not render `•••` — nothing, so the value is not in the DOM.
- Cost columns are **omitted from `columnDefs` entirely** for a Cashier, not rendered blank. A blank column wastes horizontal space on the screen a cashier stares at all shift.
- Margin, valuation and stock-value widgets are not routed to Cashier at all.

## 6. Data grid conventions

AG Grid Community (free). The SCMS `AGENTS.md` calls for AG Grid **Enterprise** with the Server-Side Row Model, but its own `package.json` only has `ag-grid-community` — the Enterprise plan is not actually available, so that instruction is aspirational. Pharmacy uses Community with client-side pagination.

**Setup, matching SCMS so the two are recognisably the same codebase:**

```tsx
import 'ag-grid-community/styles/ag-grid.css';
import 'ag-grid-community/styles/ag-theme-alpine.css';

// <div className="ag-theme-alpine" style={{ height: '600px', width: '100%' }}>
```

Theme is applied by the wrapping div, not a `theme` prop. `index.css` remaps AG Grid's CSS variables onto the shadcn tokens — **copy SCMS's `.ag-theme-alpine` block**, including `--ag-font-family: inherit`, `--ag-font-size: 14px`, and the `.ag-cell { display:flex; align-items:center }` rule that makes single-element cell renderers align correctly.

Fixed grid config:

```tsx
<AgGridReact
  getRowId={(params) => params.data.id}
  animateRows rowSelection="single" suppressRowClickSelection
  suppressCellFocus rowHeight={40}
  pagination paginationPageSize={20}
  enableCellTextSelection
  defaultColDef={{ sortable: true, resizable: true }}
/>
```

Every column sets an explicit `width` and `headerName`. Long text gets:

```tsx
cellStyle: { overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' },
tooltipField: '<field>',
```

Identifiers additionally get `cellClass: 'font-mono'`.

**All data fetching goes through `ApiClient` in `lib/api.ts`.** SCMS has two competing styles — some grids use raw `fetch` with a token scraped from `sessionStorage`, others use `api.get`. The raw-`fetch` path bypasses shared error handling; it is not copied. `ApiClient` is the only way data enters the app.

Loading and error states are early returns before the grid, each a centred `h-[600px]` block with the spinner idiom `h-8 w-8 animate-spin rounded-full border-4 border-primary border-t-transparent`.

## 7. Forms

React Hook Form + Zod via `@hookform/resolvers/zod`, as in SCMS's `InitiateRecallDialog` — the cleanest of its three examples.

```tsx
const schema = z.object({
  batchNumber: z.string().min(1, 'Batch number is required'),
  expiryDate: z.coerce.date({ required_error: 'Expiry date is required' }),
  quantity: z.coerce.number().int().positive('Quantity must be positive'),
  costPrice: z.coerce.number().positive('Cost price must be positive'),
});

type FormInput = z.infer<typeof schema>;

const { register, handleSubmit, reset, formState: { errors } } = useForm<FormInput>({
  resolver: zodResolver(schema),
});
```

Layout is `space-y-4` on the form, `space-y-2` per field, with `<Label htmlFor>` then the control then the error. Errors render `text-sm text-destructive`.

**SCMS's inconsistency is not copied:** most of its forms use bare `useState` with a hand-rolled `handleChange` and no validation. Only three use RHF + Zod. Here every form uses RHF + Zod, so validation behaves the same everywhere and matches the server's Bean Validation rules.

Numeric fields use `z.coerce.number()` so form input arrives as a number, not a string.

## 8. State management

| State | Tool | Rule |
| --- | --- | --- |
| Auth (token, user, role) | Zustand | In-memory. Token **not** persisted. `initializeAuth()` on app load attempts a silent refresh. |
| UI chrome (sidebar collapsed, active filters) | Zustand | |
| Toasts / notifications | Zustand | |
| Server data | `useEffect` + `ApiClient` in the component | **No React Query.** See below. |
| Form state | React Hook Form | |

**React Query is not used**, despite appearing in the original MDD draft. Rationale: the PMS has a handful of list and form screens with no cross-screen cache sharing, and the reference project proves plain `useEffect` + a typed `ApiClient` is sufficient. Adding a query-cache layer would be speculative infrastructure. Revisit if a screen needs background refetch or cross-route cache reuse — recorded as an open question in `progress-tracker.md`.

Tokens live in memory only. The refresh token is an `HttpOnly` cookie that JavaScript cannot read, which is exactly why it is not in `localStorage`. SCMS stores its access token in `sessionStorage`; that is acceptable for an internal tool but is not the pattern here, because the PMS is client-facing on a shared counter machine.

## 9. Accessibility

Non-negotiable for a clinical system.

- Every input has a `<label htmlFor>`. Placeholders are hints, never labels.
- Status is never conveyed by colour alone — always icon or text alongside. A pharmacist must be able to read a red expiry badge to a colour-blind colleague.
- Focus rings are visible: `focus-visible:ring-2 ring-ring ring-offset-2`. Never `outline-none` without a replacement.
- Dialogs trap focus and close on `Escape` (Radix default — do not disable).
- Tables use real `<th scope="col">`.
- Target size ≥ 36px for anything a cashier hits repeatedly.

## 10. Responsive behaviour

| Breakpoint | Behaviour |
| --- | --- |
| ≥ 1280px | Full sidebar, full data grid, multi-column forms |
| 768–1279px | Sidebar collapsed by default, stacked form fields, grid drops lower-priority columns |
| < 768px | **Not a supported target.** The PMS runs on fixed counter hardware. Below 768px it must remain usable and legible, but no layout work is spent on it. |

## 11. Money formatting

Two formatters, used consistently by domain — a fix for SCMS, which has both coexisting inconsistently.

```ts
// Grid cells and tables
new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR' })

// Inline totals in summary panels
₹{amount.toFixed(2)}
```

Pick by context as above and do not mix within a component. Quantities use `toLocaleString()` without a currency symbol.

## 12. Definition of done for a UI component

A component is not finished until all of these are true:

- Renders with real backend data, not placeholder values
- Renders its loading state
- Renders its empty state
- Renders its error state, and the error is readable rather than a raw stack
- Every interaction works: click, input, submit, keyboard navigation
- Validation fires and displays field-level messages
- Uses tokens from this file — **no hardcoded hex values**
- Honours the Cashier rule (§5) if it displays price data
- `npm run build` passes, which means `tsc` passes under `strict`, `noUnusedLocals`, and `noUnusedParameters`
