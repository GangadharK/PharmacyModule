import { cn } from '@/lib/utils'

/**
 * A loading placeholder block.
 *
 * Catalogue lists need a pending state that occupies the same space the content
 * will, so the layout does not jump when data lands. A centred spinner cannot do
 * that - it collapses to zero height and the page reflows under the user's eyes
 * on every filter keystroke.
 */
function Skeleton({ className, ...props }: React.HTMLAttributes<HTMLDivElement>) {
  return <div className={cn('animate-pulse rounded-md bg-muted', className)} {...props} />
}

export { Skeleton }
