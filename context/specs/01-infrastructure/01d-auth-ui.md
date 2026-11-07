# 01d — Auth UI

**Parent:** `01-infrastructure.md`
**Persona:** `@pharmacy-frontend`

## Goal

Render a complete, styled, locally-validating login screen that makes the Unit 01/02 boundary explicit. It does not authenticate. It must look finished, not broken, and it must not pretend to work.

## Design

### Layout

Full-viewport, centred, teal accent — per `ui-context.md` §6.

```text
┌──────────────────────────────────────────────┐
│                                              │
│           ┌────────────────────┐             │
│           │   PharmacyPMS      │             │
│           │   Sign in          │             │
│           │                    │             │
│           │  Username          │             │
│           │  Password      👁   │             │
│           │                    │             │
│           │  [   Sign in   ]   │             │
│           │                    │             │
│           │  ⚠ Auth wired in   │             │
│           │    unit 02         │             │
│           └────────────────────┘             │
│                                              │
└──────────────────────────────────────────────┘
```

Card `max-w-md`, `shadow-lg`, `rounded-xl`. Background `bg-muted/40`. Heading uses `text-primary`.

### The disabled submit

The button is rendered `disabled` with a small `text-muted-foreground` note beneath it: *"Authentication is wired in unit 02."*

**This is deliberate.** A disabled button plus a stated reason is honest. A button that accepts input and silently discards it, or one left enabled and throwing a network error, both read as bugs. The alternative — a login form that genuinely works in Unit 01 — would mean building auth early, which breaks unit boundaries and puts unaudited security code in the foundation.

The form still validates locally, so all the real work is done: `useForm` with a Zod resolver, inline field errors, password visibility toggle.

**Validation triggers on blur, not on submit.** `mode: 'onTouched'`, `reValidateMode: 'onChange'`.

This is not a style preference. A `disabled` submit button also blocks *implicit* submission: pressing Enter in a text field does not fire `handleSubmit`. This was confirmed in Chrome — Enter produced zero field errors and no request — and is now asserted permanently in `frontend/scripts/verify-ui.mjs` ("Enter fires no auth request while submit is disabled"). So with `mode: 'onSubmit'` the Zod rules are unreachable code that ships looking tested and never executes. Blur reaches the same rules through a path a user can take, which is also the better UX: errors appear as you move between fields rather than only after a submit you are not allowed to make.

Unit 02 enables the button and adds a pending state; the blur validation stays as-is.

### Fields

| Field | Validation |
| --- | --- |
| Username | `z.string().min(1, 'Username is required')` — **not** an email rule. FDD §1 says staff log in with a username; a pharmacist's username is frequently not their email address |
| Password | `z.string().min(1, 'Password is required')` |

No complexity requirements client-side. The server is the authority on password policy; duplicating it invites drift.

## Implementation

`features/auth/pages/LoginPage.tsx`

```tsx
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';

const loginSchema = z.object({
  username: z.string().min(1, 'Username is required'),
  password: z.string().min(1, 'Password is required'),
});

type LoginForm = z.infer<typeof loginSchema>;

export function LoginPage() {
  const { register, handleSubmit, formState: { errors } } = useForm<LoginForm>({
    resolver: zodResolver(loginSchema),
    mode: 'onTouched',
    reValidateMode: 'onChange',
  });

  const onSubmit = (values: LoginForm) => {
    // Unit 02 replaces this with apiClient.post('/auth/login', values)
    void values;
  };
  // ...
}
```

Sub-specs of the component:

- **Layout** — centred card, pharmacy mark, heading, `text-muted-foreground` subtitle
- **Fields** — `Input` + `Label` + error text; password field with a `Button variant="ghost" size="icon"` eye toggle using `Eye`/`EyeOff`
- **Submit** — `Button type="submit" className="w-full" disabled`, plus the unit-02 note
- **Connectivity check** — a small footer that calls `apiClient.get('/system/ping')` on mount and renders `Backend: PONG` or `Backend: unreachable`. This is how the frontend/backend wiring is proven in the integration gate

`error` text: `text-sm text-destructive`, `role="alert"`, `id` linked via `aria-describedby` so screen readers announce it.

The password toggle needs `aria-label` and `aria-pressed`. An icon-only button with no label is unusable with a screen reader.

## Dependencies

None beyond the parent. `react-hook-form`, `@hookform/resolvers`, `zod`, `lucide-react` are already installed by `01c`.

## Verify when done

Automated: `frontend/scripts/verify-ui.mjs` runs these in headless Chrome. Run it, do not eyeball them.

- [ ] `/login` renders the card, centred, at `max-w-md`
- [ ] Blurring an empty username shows the username error, and no network request fires
- [ ] Blurring an empty password shows the password error
- [ ] The password field toggles visibility; the button has `aria-label` and `aria-pressed`
- [ ] The submit button is disabled and the unit-02 note is visible beneath it
- [ ] Enter in either field fires no request and changes no route — implicit submission is blocked by the disabled button
- [ ] Field errors carry `role="alert"` and `aria-describedby`
- [ ] The footer shows `Backend: PONG` with the backend running, `unreachable` without
- [ ] No hardcoded hex in the file
- [ ] The note renders in `text-muted-foreground`, not `text-destructive` — it is informational, not an error
