import { formatMoney } from '@/lib/utils'
import { formatDate } from '@/features/shared/format'
import type { FefoAllocation } from '@/types/api'

/**
 * The split FEFO produced: which batches, how many from each, at what price.
 *
 * The order of these rows *is* the answer. FEFO consumes the soonest expiry
 * first, so this table is not an arbitrary grouping of a total - it is the
 * sequence stock left the shelf in, and a pharmacist checking a discrepancy needs
 * to read it in that order. Nothing sorts it.
 */
export interface FefoAllocationTableProps {
  allocations: FefoAllocation[]
  /** Highlights the column and appends the shortfall line. */
  shortfall?: number
  requested?: number
}

export function FefoAllocationTable({ allocations, shortfall = 0, requested }: FefoAllocationTableProps) {
  if (allocations.length === 0) {
    return (
      <p className="py-6 text-center text-sm text-muted-foreground">
        {requested === 0
          ? 'Enter a quantity to preview the split.'
          : 'Nothing available to allocate from this location.'}
      </p>
    )
  }

  const lineTotal = allocations.reduce((sum, a) => sum + a.quantity * a.unitPrice, 0)
  const allocated = allocations.reduce((sum, a) => sum + a.quantity, 0)

  return (
    <div className="space-y-2">
      <table className="w-full text-sm">
        <thead className="text-left text-xs text-muted-foreground">
          <tr>
            <th className="pb-2 font-medium">Batch</th>
            <th className="pb-2 font-medium">Expiry</th>
            <th className="pb-2 text-right font-medium">Quantity</th>
            <th className="pb-2 text-right font-medium">Unit price</th>
            <th className="pb-2 text-right font-medium">Line total</th>
          </tr>
        </thead>
        <tbody>
          {allocations.map((allocation) => (
            <tr key={allocation.batchId} className="border-t">
              <td className="py-2 font-medium">{allocation.batchNumber}</td>
              <td className="py-2 text-muted-foreground">{formatDate(allocation.expiryDate)}</td>
              <td className="py-2 text-right tabular-nums">{allocation.quantity}</td>
              <td className="py-2 text-right tabular-nums">{formatMoney(allocation.unitPrice)}</td>
              <td className="py-2 text-right tabular-nums">
                {formatMoney(allocation.quantity * allocation.unitPrice)}
              </td>
            </tr>
          ))}
        </tbody>
        <tfoot>
          <tr className="border-t-2">
            <td className="py-2 font-medium" colSpan={2}>
              Total
            </td>
            <td className="py-2 text-right font-medium tabular-nums">{allocated}</td>
            <td />
            <td className="py-2 text-right font-medium tabular-nums">{formatMoney(lineTotal)}</td>
          </tr>
          {/*
            A shortfall is rendered here, in the table, in red - not as an error
            toast. The preview *succeeded*; its answer is "not enough". Showing a
            successful answer as a failure is what teaches a user to distrust the
            preview, and then they stop reading it before dispensing.
          */}
          {shortfall > 0 ? (
            <tr>
              <td className="py-1 text-destructive" colSpan={5}>
                {requested !== undefined
                  ? `Short by ${shortfall} — requested ${requested}, only ${allocated} available.`
                  : `Short by ${shortfall}.`}
              </td>
            </tr>
          ) : null}
        </tfoot>
      </table>
    </div>
  )
}
