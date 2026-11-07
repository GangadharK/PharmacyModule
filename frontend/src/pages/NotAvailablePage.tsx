import { PageHeader } from '@/components/layout/PageHeader'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { useLocation } from 'react-router-dom'

/**
 * Placeholder for a route whose feature has not shipped yet.
 *
 * Used for every nav item with a higher `shippedIn` than CURRENT_BUILD_UNIT.
 * It exists so the route table is complete and deep links resolve, rather than
 * 404ing on a URL the user can see in the sidebar.
 */
export function NotAvailablePage() {
  const location = useLocation()

  return (
    <div>
      <PageHeader
        title="Not available in this build"
        description={location.pathname}
      />
      <Card>
        <CardHeader>
          <CardTitle>This screen has not been built yet</CardTitle>
          <CardDescription>
            The route exists so navigation stays stable, but the feature behind it
            ships in a later build unit. See{' '}
            <code className="rounded bg-muted px-1 py-0.5 text-xs">
              context/specs/00-build-plan.md
            </code>
            .
          </CardDescription>
        </CardHeader>
        <CardContent className="text-sm text-muted-foreground">
          Navigation entries for unbuilt features are dimmed and are not links.
        </CardContent>
      </Card>
    </div>
  )
}
