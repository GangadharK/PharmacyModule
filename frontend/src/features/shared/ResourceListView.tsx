import type { ReactNode } from 'react'
import { PageHeader } from '@/components/layout/PageHeader'
import { DataTable, type Column } from '@/components/data/DataTable'
import { Pagination } from '@/components/data/Pagination'
import { ActiveFilter } from '@/components/data/ActiveFilter'
import { SearchInput } from '@/components/forms/SearchInput'
import type { ResourceList } from '@/hooks/useResource'
import type { ResourceScreen } from '@/features/shared/useResourceScreen'

/**
 * The shared frame of every catalogue list screen.
 *
 * PageHeader, search, status filter, the table, and pagination - in that order,
 * identically, five times over. What differs between resources is the columns,
 * the extra filters, the dialogs and the row-click target, and those stay in the
 * feature.
 *
 * The value is not the saved lines, it is that the loading, empty, error and
 * paging behaviour cannot diverge between screens. A pharmacist who has learned
 * one catalogue list has learned all of them, and a fix to a shared behaviour
 * lands on every resource at once.
 */
interface ResourceListViewProps<T> {
  screen: ResourceScreen
  list: ResourceList<T>
  title: string
  description: string
  columns: Column<T>[]
  rowKey: (row: T) => string
  searchPlaceholder?: string
  /** Rendered next to the status filter, e.g. a dosage form dropdown. */
  extraFilters?: ReactNode
  /** Top-right header buttons. Omit on read-only roles. */
  actions?: ReactNode
  onRowClick?: (row: T) => void
  emptyTitle: string
  emptyDescription: string
  /** Shown in the empty state - typically "Clear filters" or "Add the first". */
  emptyAction?: ReactNode
}

export function ResourceListView<T>({
  screen,
  list,
  title,
  description,
  columns,
  rowKey,
  searchPlaceholder = 'Search…',
  extraFilters,
  actions,
  onRowClick,
  emptyTitle,
  emptyDescription,
  emptyAction,
}: ResourceListViewProps<T>) {
  return (
    <>
      <PageHeader title={title} description={description} actions={actions} />

      <div className="mb-4 flex flex-wrap items-center gap-3">
        <SearchInput
          value={screen.search}
          onChange={screen.onSearchChange}
          placeholder={searchPlaceholder}
          className="w-full max-w-xs"
        />
        {screen.statusFilter && (
          <ActiveFilter value={screen.status} onChange={screen.onStatusChange} />
        )}
        {extraFilters}
      </div>

      <DataTable
        columns={columns}
        rows={list.rows}
        isLoading={list.isLoading}
        error={list.error}
        rowKey={rowKey}
        onRetry={list.reload}
        onRowClick={onRowClick}
        emptyTitle={emptyTitle}
        emptyDescription={emptyDescription}
        emptyAction={
          emptyAction ??
          (screen.hasActiveFilters ? (
            <ResetFiltersButton onClick={screen.reset} />
          ) : undefined)
        }
      />

      <div className="mt-4">
        <Pagination
          page={list.page}
          totalPages={list.totalPages}
          totalElements={list.totalElements}
          size={list.size}
          onPageChange={screen.setPage}
          onSizeChange={screen.setSize}
        />
      </div>
    </>
  )
}

function ResetFiltersButton({ onClick }: { onClick: () => void }) {
  return (
    <button
      type="button"
      onClick={onClick}
      className="rounded-md border px-3 py-1.5 text-sm font-medium hover:bg-muted"
    >
      Clear filters
    </button>
  )
}
