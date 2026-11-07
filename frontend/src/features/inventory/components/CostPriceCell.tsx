import { formatMoney } from '@/lib/utils'

/**
 * Renders a cost price, or an em dash when the server did not send one.
 *
 * This branches on the **field being absent**, never on the viewer's role.
 * AGENTS.md rule 7 makes cost redaction a server-side guarantee: a CASHIER's
 * response body contains no `cost_price` key at all. So the correct test is
 * whether the value arrived, and a role check would be a second, weaker source
 * of truth that could disagree with the server.
 *
 * The failure that motivates this: an earlier draft used
 * `showCost && formatMoney(costPrice)`, so the value sat in the JavaScript heap
 * for a CASHIER regardless of what was rendered, and any future devtools
 * inspection or a mis-set role object exposed it. Branching on the payload means
 * a price the server withheld is a price the client never has.
 *
 * `number | null | undefined` because the backend omits the key entirely
 * (`@JsonInclude(NON_NULL)`), which arrives here as `undefined`.
 */
export interface CostPriceCellProps {
  costPrice?: number | null
}

export function CostPriceCell({ costPrice }: CostPriceCellProps) {
  if (costPrice === null || costPrice === undefined) {
    return <span className="text-muted-foreground">—</span>
  }
  return <span className="tabular-nums">{formatMoney(costPrice)}</span>
}
