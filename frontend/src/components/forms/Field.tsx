import { Children, cloneElement, isValidElement, type ReactElement, type ReactNode } from 'react'
import { Label } from '@/components/ui/label'
import { cn } from '@/lib/utils'

/**
 * A labelled form field wired for react-hook-form + Zod.
 *
 * Every form in the app renders through this, so the label/description/error
 * association is written once.
 *
 * The a11y wiring is **injected into the child** rather than exposed as a
 * `fieldA11y()` helper for callers to spread. An earlier version of this file
 * did it the other way and it was wrong: forgetting the spread produces a
 * validation message that is not programmatically associated with its input,
 * which is invisible in review and in a screenshot, and only shows up when a
 * screen-reader user hits the field. `cloneElement` makes the omission
 * impossible.
 */
interface FieldProps {
  id: string
  label: string
  error?: string
  description?: string
  required?: boolean
  className?: string
  children: ReactNode
}

export function Field({
  id,
  label,
  error,
  description,
  required = false,
  className,
  children,
}: FieldProps) {
  const describedBy =
    [description ? `${id}-description` : null, error ? `${id}-error` : null]
      .filter(Boolean)
      .join(' ') || undefined

  const child = Children.only(children)
  const enhanced = isValidElement(child)
    ? cloneElement(child as ReactElement<Record<string, unknown>>, {
        id,
        'aria-invalid': error ? true : undefined,
        'aria-describedby': describedBy,
        'aria-required': required || undefined,
      })
    : child

  return (
    <div className={cn('space-y-1.5', className)}>
      <Label htmlFor={id}>
        {label}
        {required && (
          <>
            <span className="ml-0.5 text-destructive" aria-hidden>
              *
            </span>
            <span className="sr-only"> (required)</span>
          </>
        )}
      </Label>
      {enhanced}
      {description && (
        <p id={`${id}-description`} className="text-xs text-muted-foreground">
          {description}
        </p>
      )}
      {error && (
        <p id={`${id}-error`} role="alert" className="text-xs font-medium text-destructive">
          {error}
        </p>
      )}
    </div>
  )
}
