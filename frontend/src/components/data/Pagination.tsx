import { ChevronLeft, ChevronRight } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { NativeSelect } from '@/components/ui/native-select'

/**
 * Page controls for a server-paged list.
 *
 * Rendered only when there is more than one page. A pager reading "Page 1 of 1"
 * tells the user nothing and costs a row of vertical space on every screen.
 *
 * `totalElements` is shown because a pharmacist needs to know whether a filtered
 * result is 3 rows or 300 before deciding whether to narrow further.
 */
interface PaginationProps {
  page: number
  totalPages: number
  totalElements: number
  size: number
  onPageChange: (page: number) => void
  onSizeChange?: (size: number) => void
}

const PAGE_SIZES = [10, 20, 50, 100] as const

export function Pagination({
  page,
  totalPages,
  totalElements,
  size,
  onPageChange,
  onSizeChange,
}: PaginationProps) {
  if (totalPages <= 1) {
    return (
      <p className="text-xs text-muted-foreground">
        {totalElements} {totalElements === 1 ? 'record' : 'records'}
      </p>
    )
  }

  const from = page * size + 1
  const to = Math.min((page + 1) * size, totalElements)

  return (
    <div className="flex flex-wrap items-center justify-between gap-3">
      <p className="text-xs text-muted-foreground">
        Showing <span className="font-medium text-foreground">{from}</span>–
        <span className="font-medium text-foreground">{to}</span> of{' '}
        <span className="font-medium text-foreground">{totalElements}</span>
      </p>

      <div className="flex items-center gap-2">
        {onSizeChange && (
          <>
            <label htmlFor="page-size" className="text-xs text-muted-foreground">
              Per page
            </label>
            <NativeSelect
              id="page-size"
              value={String(size)}
              onValueChange={(value) => {
                // Back to the first page: staying on page 4 of a list that just
                // shrank to 2 pages shows an empty table with no obvious cause.
                onSizeChange(Number(value))
                onPageChange(0)
              }}
              options={PAGE_SIZES.map((n) => ({ value: String(n), label: String(n) }))}
            />
          </>
        )}

        <Button
          variant="outline"
          size="sm"
          onClick={() => onPageChange(page - 1)}
          disabled={page === 0}
          aria-label="Previous page"
        >
          <ChevronLeft className="h-4 w-4" aria-hidden />
          Prev
        </Button>
        <span className="text-xs text-muted-foreground">
          Page {page + 1} of {totalPages}
        </span>
        <Button
          variant="outline"
          size="sm"
          onClick={() => onPageChange(page + 1)}
          disabled={page >= totalPages - 1}
          aria-label="Next page"
        >
          Next
          <ChevronRight className="h-4 w-4" aria-hidden />
        </Button>
      </div>
    </div>
  )
}
