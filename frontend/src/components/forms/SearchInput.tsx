import { useEffect, useState } from 'react'
import { Search, X } from 'lucide-react'
import { Input } from '@/components/ui/input'

/**
 * A search box that reports changes on a delay.
 *
 * Every catalogue list is server-paged and server-filtered, so each keystroke
 * would otherwise be a request. The delay is the whole reason this is a
 * component and not a bare input: three letters into "amoxicillin" is four
 * round trips that return nothing useful, and on a cold connection each one
 * visibly stalls the table.
 *
 * The value is echoed immediately so typing never feels laggy - only the request
 * is delayed. The pending prop lets a caller show that a search is still settling
 * rather than looking idle.
 */
interface SearchInputProps {
  value: string
  onChange: (value: string) => void
  placeholder?: string
  delayMs?: number
  className?: string
  label?: string
}

export function SearchInput({
  value,
  onChange,
  placeholder = 'Search…',
  delayMs = 300,
  className,
  label = 'Search',
}: SearchInputProps) {
  const [draft, setDraft] = useState(value)

  // Keep the draft in step when the caller resets the box from outside (a "clear
  // filters" button), otherwise the input keeps showing text the query dropped.
  useEffect(() => {
    setDraft(value)
  }, [value])

  useEffect(() => {
    if (draft === value) return
    const timer = setTimeout(() => onChange(draft), delayMs)
    return () => clearTimeout(timer)
  }, [draft, value, onChange, delayMs])

  return (
    <div className={className}>
      <label htmlFor="resource-search" className="sr-only">
        {label}
      </label>
      <div className="relative">
        <Search
          className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground"
          aria-hidden
        />
        <Input
          id="resource-search"
          type="search"
          value={draft}
          placeholder={placeholder}
          className="pl-9 pr-9"
          onChange={(event) => setDraft(event.target.value)}
          onKeyDown={(event) => {
            // Enter should search now rather than after the debounce, not add a
            // newline - a search field has no multiline state.
            if (event.key === 'Enter') {
              event.preventDefault()
              onChange(draft)
            }
          }}
        />
        {draft && (
          <button
            type="button"
            onClick={() => {
              setDraft('')
              onChange('')
            }}
            className="absolute right-2 top-1/2 -translate-y-1/2 rounded p-1 text-muted-foreground hover:bg-muted"
            aria-label="Clear search"
          >
            <X className="h-3.5 w-3.5" aria-hidden />
          </button>
        )}
      </div>
    </div>
  )
}
