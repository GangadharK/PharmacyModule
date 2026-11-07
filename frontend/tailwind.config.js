/** @type {import('tailwindcss').Config} */
//
// Tokens are specified in HSL channel triplets without the hsl() wrapper, which
// is how Tailwind's opacity modifiers (bg-primary/10) can compose with them.
// Values come from context/ui-context.md section 3 and are the single source of
// truth - if a colour is needed that is not here, add it there first.
//
// The dark block is DELIBERATELY PRESENT BUT NOT TOGGLED. ui-context.md
// section 9 records dark mode as deferred: no requirement has asked for it. The
// reference project in ../SCMS ships a full dormant .dark block that nothing
// activates, which is dead configuration. Declaring the class costs nothing;
// building an unrequested toggle is scope creep. Remove or wire it, do not
// quietly half-implement it.

export default {
  darkMode: ['class'],
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  /*
   * The `dark` class must be safelisted, or Tailwind purges the .dark token
   * block out of the compiled CSS entirely.
   *
   * This is not hypothetical. `content` globs only .ts/.tsx, and the string
   * "dark" appears in none of them, so the purge treated the .dark selector as
   * an unused class and dropped it. The result was a stylesheet where .dark
   * simply did not exist: adding the class to <html> did nothing, the light
   * tokens kept applying, and a dark-mode screenshot came out byte-identical to
   * the light one (same md5).
   *
   * Custom class selectors written inside @layer base are subject to the same
   * purge as utility classes. Anything toggled at runtime needs a safelist entry.
   */
  safelist: ['dark'],
  theme: {
    extend: {
      colors: {
        border: 'hsl(var(--border))',
        input: 'hsl(var(--input))',
        ring: 'hsl(var(--ring))',
        background: 'hsl(var(--background))',
        foreground: 'hsl(var(--foreground))',
        primary: {
          DEFAULT: 'hsl(var(--primary))',
          foreground: 'hsl(var(--primary-foreground))',
        },
        secondary: {
          DEFAULT: 'hsl(var(--secondary))',
          foreground: 'hsl(var(--secondary-foreground))',
        },
        destructive: {
          DEFAULT: 'hsl(var(--destructive))',
          foreground: 'hsl(var(--destructive-foreground))',
        },
        success: {
          DEFAULT: 'hsl(var(--success))',
          foreground: 'hsl(var(--success-foreground))',
        },
        warning: {
          DEFAULT: 'hsl(var(--warning))',
          foreground: 'hsl(var(--warning-foreground))',
        },
        muted: {
          DEFAULT: 'hsl(var(--muted))',
          foreground: 'hsl(var(--muted-foreground))',
        },
        accent: {
          DEFAULT: 'hsl(var(--accent))',
          foreground: 'hsl(var(--accent-foreground))',
        },
        popover: {
          DEFAULT: 'hsl(var(--popover))',
          foreground: 'hsl(var(--popover-foreground))',
        },
        card: {
          DEFAULT: 'hsl(var(--card))',
          foreground: 'hsl(var(--card-foreground))',
        },
        sidebar: {
          DEFAULT: 'hsl(var(--sidebar))',
          foreground: 'hsl(var(--sidebar-foreground))',
          accent: 'hsl(var(--sidebar-accent))',
          border: 'hsl(var(--sidebar-border))',
        },
      },
      borderRadius: {
        lg: 'var(--radius)',
        md: 'calc(var(--radius) - 2px)',
        sm: 'calc(var(--radius) - 4px)',
      },
      fontFamily: {
        sans: ['Inter', 'system-ui', '-apple-system', 'Segoe UI', 'sans-serif'],
        mono: ['ui-monospace', 'SFMono-Regular', 'Menlo', 'monospace'],
      },
      keyframes: {
        'accordion-down': {
          from: { height: '0' },
          to: { height: 'var(--radix-accordion-content-height)' },
        },
        'accordion-up': {
          from: { height: 'var(--radix-accordion-content-height)' },
          to: { height: '0' },
        },
      },
      animation: {
        'accordion-down': 'accordion-down 0.2s ease-out',
        'accordion-up': 'accordion-up 0.2s ease-out',
      },
    },
  },
  plugins: [require('tailwindcss-animate')],
}
