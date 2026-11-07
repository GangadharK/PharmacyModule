import { cn } from '@/lib/utils'

/**
 * A styled native `<select>`.
 *
 * ui-context.md §4 requires the shadcn `Select` for form fields and forbids
 * hand-rolled native selects with inlined classes at each call site. Both rules
 * are satisfied here rather than by breaking one of them.
 *
 * The shadcn `Select` is a popover with a listbox - correct for a form, wrong for
 * a 20px-tall control in a table toolbar, where it costs a portal, a trigger and
 * keyboard-trap handling for something the platform already does well. A native
 * select also gets the OS picker on touch, which is what a tablet on a pharmacy
 * counter actually wants.
 *
 * So: shadcn `Select` inside forms (as `Field` children), this primitive in
 * toolbars and pagination. Styled once, here, so the two do not drift.
 */
export interface NativeSelectOption<T extends string = string> {
  value: T
  label: string
}

interface NativeSelectProps<T extends string = string>
  extends Omit<React.SelectHTMLAttributes<HTMLSelectElement>, 'onChange' | 'value' | 'size'> {
  value: T
  options: NativeSelectOption<T>[]
  onValueChange: (value: T) => void
  /** Leading text for an "all" option, e.g. "All roles". Omit for a required choice. */
  placeholder?: string
  size?: 'sm' | 'md'
}

export function NativeSelect<T extends string = string>({
  value,
  options,
  onValueChange,
  placeholder,
  size = 'sm',
  className,
  ...props
}: NativeSelectProps<T>) {
  return (
    <select
      {...props}
      value={value}
      onChange={(event) => onValueChange(event.target.value as T)}
      className={cn(
        'rounded-md border border-input bg-background pl-2 pr-7 text-foreground shadow-sm',
        'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2',
        'disabled:cursor-not-allowed disabled:opacity-50',
        // A chevron, because `appearance: none` would otherwise remove the native
        // one and leave the control looking like a text input.
        "bg-[url('data:image/svg+xml;charset=utf-8,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 16 16' fill='%23888'%3E%3Cpath d='M4 6l4 4 4-4z'/%3E%3C/svg%3E')] bg-[length:16px] bg-[right_0.25rem_center] bg-no-repeat",
        size === 'sm' ? 'h-8 text-xs' : 'h-9 text-sm',
        className,
      )}
    >
      {placeholder !== undefined && <option value="">{placeholder}</option>}
      {options.map((option) => (
        <option key={option.value} value={option.value}>
          {option.label}
        </option>
      ))}
    </select>
  )
}
