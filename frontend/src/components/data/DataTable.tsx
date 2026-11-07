import type { ReactNode } from 'react'
import { AlertTriangle, Inbox, Loader2 } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { cn } from '@/lib/utils'

/**
 * The list-screen chassis: a table with explicit loading, empty and error states.
 *
 * AGENTS.md requires every data component to handle all three as early returns.
 * Centralising that here is what makes it true of five resources instead of one,
 * and it keeps the states visually identical - a pharmacist switching from
 * Medicines to Suppliers should not have to relearn what "no rows" looks like.
 *
 * The three states are deliberately distinguishable at a glance:
 *   loading - grey blocks in the row positions the data will occupy
 *   empty   - an illustration and the reason, with the action that fixes it
 *   error   - amber, because a failed load is not an empty result
 */

export interface Column<T> {
  key: string
  header: string
  /** Rendered when the row is a real record. */
  cell: (row: T) => ReactNode
  /** Right-aligned, for actions and numbers. */
  align?: 'left' | 'right'
  className?: string
}

interface DataTableProps<T> {
  columns: Column<T>[]
  rows: T[]
  isLoading: boolean
  error: string | null
  emptyTitle: string
  emptyDescription?: string
  emptyAction?: ReactNode
  onRetry?: () => void
  /** Stable row key. Never the array index - rows reorder under a filter. */
  rowKey: (row: T) => string
  /** Highlights the row whose id matches, for a detail page. */
  highlightId?: string
  onRowClick?: (row: T) => void
}

export function DataTable<T>({
  columns,
  rows,
  isLoading,
  error,
  emptyTitle,
  emptyDescription,
  emptyAction,
  onRetry,
  rowKey,
  highlightId,
  onRowClick,
}: DataTableProps<T>) {
  // Error wins over loading: a request that has already failed must not leave the
  // skeleton up, or the screen looks like it is still thinking about a dead list.
  if (error) {
    return (
      <StatePanel tone="error" icon={AlertTriangle} title="Could not load" description={error}>
        {onRetry && (
          <Button variant="outline" size="sm" onClick={onRetry}>
            Try again
          </Button>
        )}
      </StatePanel>
    )
  }

  if (isLoading && rows.length === 0) {
    return (
      <div className="space-y-2">
        {Array.from({ length: 5 }, (_, i) => (
          <Skeleton key={i} className="h-11 w-full" />
        ))}
        <p className="flex items-center gap-2 pt-1 text-sm text-muted-foreground">
          <Loader2 className="h-3.5 w-3.5 animate-spin motion-reduce:animate-none" aria-hidden />
          Loading…
        </p>
      </div>
    )
  }

  if (rows.length === 0) {
    return (
      <StatePanel
        tone="empty"
        icon={Inbox}
        title={emptyTitle}
        description={emptyDescription ?? 'Nothing matches the current filters.'}
      >
        {emptyAction}
      </StatePanel>
    )
  }

  return (
    <>
      <Table>
        <TableHeader>
          <TableRow>
            {columns.map((column) => (
              <TableHead
                key={column.key}
                className={cn(column.align === 'right' && 'text-right', column.className)}
              >
                {column.header}
              </TableHead>
            ))}
          </TableRow>
        </TableHeader>
        <TableBody>
          {rows.map((row) => {
            const id = rowKey(row)
            const isHighlighted = highlightId !== undefined && id === highlightId
            return (
              <TableRow
                key={id}
                data-state={isHighlighted ? 'selected' : undefined}
                data-highlighted={isHighlighted ? 'true' : undefined}
                className={cn(onRowClick && 'cursor-pointer', isHighlighted && 'bg-muted')}
                tabIndex={onRowClick ? 0 : undefined}
                onClick={
                  onRowClick
                    ? (event) => {
                        // A row is a link, but the cells also contain real
                        // controls. Without this, clicking "Edit" both opens the
                        // form and navigates to the detail page, and the form
                        // unmounts before it can be submitted.
                        if (
                          event.target instanceof Element &&
                          event.target.closest('button, a, input, select, textarea, [role="button"]')
                        ) {
                          return
                        }
                        onRowClick(row)
                      }
                    : undefined
                }
                onKeyDown={
                  onRowClick
                    ? (event) => {
                        if (event.target !== event.currentTarget) return
                        if (event.key !== 'Enter' && event.key !== ' ') return
                        event.preventDefault()
                        onRowClick(row)
                      }
                    : undefined
                }
              >
                {columns.map((column) => (
                  <TableCell
                    key={column.key}
                    className={cn(column.align === 'right' && 'text-right', column.className)}
                  >
                    {column.cell(row)}
                  </TableCell>
                ))}
              </TableRow>
            )
          })}
        </TableBody>
      </Table>
      {/* A refresh over existing rows dims them rather than replacing the table,
          so the layout holds still and it is obvious the data is being re-read. */}
      {isLoading && (
        <p className="mt-2 flex items-center gap-2 text-xs text-muted-foreground">
          <Loader2 className="h-3 w-3 animate-spin motion-reduce:animate-none" aria-hidden />
          Refreshing…
        </p>
      )}
    </>
  )
}

function StatePanel({
  tone,
  icon: Icon,
  title,
  description,
  children,
}: {
  tone: 'empty' | 'error'
  icon: typeof Inbox
  title: string
  description: string
  children?: ReactNode
}) {
  return (
    <div
      className={cn(
        'flex flex-col items-center gap-3 rounded-xl border border-dashed px-6 py-12 text-center',
        tone === 'error' ? 'border-destructive/40 bg-destructive/5' : 'bg-muted/20',
      )}
      role={tone === 'error' ? 'alert' : 'status'}
    >
      <Icon
        className={cn('h-7 w-7', tone === 'error' ? 'text-destructive' : 'text-muted-foreground')}
        aria-hidden
      />
      <div className="space-y-1">
        <p className="font-medium">{title}</p>
        <p className="max-w-md text-sm text-muted-foreground">{description}</p>
      </div>
      {children}
    </div>
  )
}
