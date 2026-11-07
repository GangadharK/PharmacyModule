import { Badge } from '@/components/ui/badge'

/**
 * Status and schedule badges.
 *
 * Schedule is the one place colour carries clinical meaning: a pharmacist
 * scanning for something they may not dispense without a prescription must be
 * able to tell H from H1 from OTC without reading the text. The badge variants
 * already exist in `components/ui/badge` for exactly this - `scheduled` and
 * `otc` are separate from the ordinary variants so a scheduled drug is never
 * rendered in the same style as an OTC one. They are used here and nowhere else,
 * so the mapping cannot diverge between screens.
 *
 * The label is always present as text as well as colour. Colour alone fails for
 * a colour-blind user and in a printed or low-brightness screen; this is a
 * counter at a pharmacy counter.
 */

const SCHEDULE_VARIANT = {
  OTC: 'otc',
  H: 'scheduled',
  H1: 'scheduled',
} as const

const SCHEDULE_MEANING = {
  OTC: 'Over the counter',
  H: 'Schedule H — pharmacist record required',
  H1: 'Schedule H1 — narcotic, stricter record',
} as const

export function ScheduleBadge({ scheduleClass }: { scheduleClass: 'OTC' | 'H' | 'H1' }) {
  return (
    <Badge variant={SCHEDULE_VARIANT[scheduleClass]} title={SCHEDULE_MEANING[scheduleClass]}>
      {scheduleClass}
    </Badge>
  )
}

/** Active / deactivated, for the soft-deletable resources. */
export function ActiveBadge({ active }: { active: boolean }) {
  return <Badge variant={active ? 'success' : 'secondary'}>{active ? 'Active' : 'Deactivated'}</Badge>
}

/**
 * Prescription-only marker.
 *
 * Shown for medicines that cannot be sold without one. Note this reflects the
 * record, not a legal judgement - `requiresPrescription` defaults to false and
 * the patient-safety question about that default is still open, so the badge
 * says what the data says and nothing more.
 */
export function PrescriptionBadge({ required }: { required: boolean }) {
  if (!required) return <span className="text-xs text-muted-foreground">OTC</span>
  return <Badge variant="warning">Rx only</Badge>
}
