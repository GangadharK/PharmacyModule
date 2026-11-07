import { useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { ListOrdered } from 'lucide-react'

import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { NativeSelect } from '@/components/ui/native-select'
import { PageHeader } from '@/components/layout/PageHeader'
import { ErrorState } from '@/features/shared/ResourceStates'
import { useResourceList } from '@/hooks/useResource'
import { apiClient } from '@/lib/api'
import { describeError } from '@/hooks/useResource'
import { locationOptions } from '@/features/shared/labels'
import { FefoAllocationTable } from '@/features/inventory/fefo/FefoAllocationTable'
import type {
  BatchLocation,
  FefoRequest,
  FefoResponse,
  MedicineResponse,
} from '@/types/api'

/**
 * Preview what FEFO would hand over, then dispense it.
 *
 * **Preview is an explicit button, not a keystroke effect.** Live preview means
 * a request per digit, and a pharmacist typing `30` would fire requests for `3`
 * and `30` - two round trips, two table rewrites, and a request for a quantity
 * they never intended to ask about.
 *
 * **A shortfall is an answer, not an error.** See `FefoAllocationTable`: the
 * preview succeeded. The only thing that makes this screen show an error state
 * is a failed request.
 *
 * **No reference type is offered.** A sale has no truthful value in
 * `stock_transaction.reference_type` (06-fefo-engine.md section 7.4), so the
 * field stays server-side and the user is not asked to pick a fiction.
 */
export function FefoPreviewPage() {
  const navigate = useNavigate()

  const [medicineId, setMedicineId] = useState('')
  const [quantityText, setQuantityText] = useState('0')
  const [location, setLocation] = useState<BatchLocation>('MAIN_PHARMACY')

  const [preview, setPreview] = useState<FefoResponse | null>(null)
  const [isPreviewing, setIsPreviewing] = useState(false)
  const [isDispensing, setIsDispensing] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [fieldError, setFieldError] = useState<string | null>(null)
  const [dispensed, setDispensed] = useState<FefoResponse | null>(null)

  // The medicine picker queries the API by name rather than loading the whole
  // catalogue into a dropdown.
  //
  // The first version fetched `size: 200` once and filtered client-side, on the
  // reasoning that a pharmacy's catalogue is small. It is not: the dev database
  // passed 200 medicines during verification, and because the list is ordered by
  // name, anything sorting after the 200th was simply unselectable. A cap that
  // silently drops the tail is worse than no cap, because the user sees a
  // complete-looking list that is quietly incomplete.
  //
  // So the search is server-side and the page size is small. Which also means
  // FEFO no longer depends on the catalogue being small enough to fetch.
  const [medicineSearch, setMedicineSearch] = useState('')
  const [debouncedSearch, setDebouncedSearch] = useState('')

  useEffect(() => {
    const timer = setTimeout(() => setDebouncedSearch(medicineSearch.trim()), 250)
    return () => clearTimeout(timer)
  }, [medicineSearch])

  const medicines = useResourceList<MedicineResponse>('/medicines', {
    page: 0,
    size: 50,
    filters: { activeOnly: true, search: debouncedSearch || undefined },
  })

  const parsedQuantity = Number(quantityText)
  const quantityValid = Number.isInteger(parsedQuantity) && parsedQuantity >= 0

  const availableMedicines = useMemo(() => medicines.rows, [medicines.rows])

  // Strength is appended because "Paracetamol" and "Paracetamol 500mg" are
  // different things to dispense, and a list of bare generic names is ambiguous
  // at exactly the moment accuracy matters.
  const medicineOptions = useMemo(
    () =>
      availableMedicines.map((medicine) => ({
        value: medicine.id,
        label: medicine.strength
          ? `${medicine.genericName} — ${medicine.strength}`
          : medicine.genericName,
      })),
    [availableMedicines],
  )

  async function runPreview() {
    setError(null)
    setFieldError(null)
    setDispensed(null)

    if (!medicineId) {
      setFieldError('Choose a medicine.')
      return
    }
    if (!quantityValid) {
      setFieldError('Quantity must be a whole number of zero or more.')
      return
    }

    const body: FefoRequest = {
      medicineId,
      requestedQuantity: parsedQuantity,
      location,
    }

    setIsPreviewing(true)
    try {
      setPreview(await apiClient.post<FefoResponse>('/inventory/fefo-preview', body))
    } catch (caught: unknown) {
      setPreview(null)
      setError(describeError(caught, 'Could not preview this request.'))
    } finally {
      setIsPreviewing(false)
    }
  }

  async function runDispense() {
    if (!preview) return
    setError(null)
    setIsDispensing(true)
    try {
      const result = await apiClient.post<FefoResponse>('/inventory/dispense', {
        medicineId,
        requestedQuantity: parsedQuantity,
        location,
      })
      setDispensed(result)
      // The balances on screen are now wrong by construction, so the preview is
      // cleared rather than left showing a split that has already been consumed.
      setPreview(null)
    } catch (caught: unknown) {
      // Rendered inline rather than as a toast: a pharmacist who asked for 30 and
      // can only get 8 needs to see the two numbers while deciding what to do,
      // not a message that has already faded.
      setError(describeError(caught, 'Could not dispense this request.'))
    } finally {
      setIsDispensing(false)
    }
  }

  return (
    <>
      <PageHeader
        title="FEFO preview"
        description="See which batches a quantity would come from, then dispense it."
      />

      <div className="grid gap-4 lg:grid-cols-[minmax(0,22rem)_minmax(0,1fr)]">
        <Card>
          <CardHeader>
            <CardTitle className="text-base">Request</CardTitle>
          </CardHeader>
          <CardContent className="space-y-4">
            <div className="space-y-1.5">
              <Label htmlFor="fefo-medicine-search">Find medicine</Label>
              <Input
                id="fefo-medicine-search"
                type="search"
                value={medicineSearch}
                onChange={(event) => setMedicineSearch(event.target.value)}
                placeholder="Type a name or brand…"
                autoComplete="off"
              />
            </div>

            <div className="space-y-1.5">
              <Label htmlFor="fefo-medicine">Medicine</Label>
              <NativeSelect
                id="fefo-medicine"
                value={medicineId}
                onValueChange={setMedicineId}
                placeholder={medicines.isLoading ? 'Loading…' : 'Select a medicine…'}
                options={medicineOptions}
                disabled={medicines.isLoading}
                size="md"
              />
              {/*
                An empty result after a search is stated rather than left as a bare
                disabled dropdown, so "no match" is distinguishable from "still
                loading" and from "the list failed to arrive".
              */}
              {!medicines.isLoading && medicineOptions.length === 0 && debouncedSearch ? (
                <p className="text-xs text-muted-foreground">
                  No active medicine matches “{debouncedSearch}”.
                </p>
              ) : null}
            </div>

            <div className="space-y-1.5">
              <Label htmlFor="fefo-quantity">Quantity</Label>
              <Input
                id="fefo-quantity"
                type="number"
                min={0}
                step={1}
                value={quantityText}
                onChange={(event) => setQuantityText(event.target.value)}
                aria-describedby="fefo-quantity-hint"
              />
              <p id="fefo-quantity-hint" className="text-xs text-muted-foreground">
                Zero is a valid request and returns nothing — it is not an error.
              </p>
            </div>

            <div className="space-y-1.5">
              <Label htmlFor="fefo-location">Location</Label>
              <NativeSelect
                id="fefo-location"
                value={location}
                onValueChange={(value) => setLocation(value as BatchLocation)}
                options={locationOptions}
              />
            </div>

            {fieldError ? <p className="text-sm text-destructive">{fieldError}</p> : null}

            <Button
              onClick={() => void runPreview()}
              disabled={isPreviewing}
              className="w-full"
            >
              <ListOrdered className="h-4 w-4" aria-hidden />
              {isPreviewing ? 'Previewing…' : 'Preview allocation'}
            </Button>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle className="text-base">Allocation</CardTitle>
          </CardHeader>
          <CardContent className="space-y-4">
            {error ? (
              <ErrorState message={error} onRetry={() => void runPreview()} />
            ) : dispensed ? (
              <div className="space-y-3">
                <p className="text-sm text-success">
                  Dispensed {dispensed.totalAllocated} from {dispensed.allocations.length}{' '}
                  {dispensed.allocations.length === 1 ? 'batch' : 'batches'}.
                </p>
                <FefoAllocationTable
                  allocations={dispensed.allocations}
                  shortfall={dispensed.shortfall}
                  requested={parsedQuantity}
                />
                <Button variant="outline" onClick={() => navigate('/inventory/batches')}>
                  View batches
                </Button>
              </div>
            ) : preview ? (
              <div className="space-y-4">
                <FefoAllocationTable
                  allocations={preview.allocations}
                  shortfall={preview.shortfall}
                  requested={parsedQuantity}
                />
                {/*
                  Disabled on an incomplete allocation rather than on an empty
                  one, so dispensing zero stays available - it is a no-op the user
                  may legitimately mean, and the backend agrees.
                */}
                <Button
                  onClick={() => void runDispense()}
                  disabled={isDispensing || !preview.complete}
                >
                  {isDispensing
                    ? 'Dispensing…'
                    : preview.complete
                      ? `Dispense ${preview.totalAllocated}`
                      : 'Cannot dispense — not enough stock'}
                </Button>
              </div>
            ) : (
              <p className="py-6 text-center text-sm text-muted-foreground">
                Choose a medicine and a quantity, then preview.
              </p>
            )}
          </CardContent>
        </Card>
      </div>
    </>
  )
}
