/**
 * Timestamp formatting, shared by every list and detail screen.
 *
 * Backend timestamps arrive as `2026-09-26T07:26:41.345` - a `LocalDateTime`
 * serialised without a zone. The server runs in one timezone and the browser in
 * another, so a bare `new Date(value)` parses the string as *local* time and
 * displays a wall-clock time that is not the time the server recorded. For a
 * record's "last sign-in" or a batch's expiry that discrepancy is not
 * cosmetic.
 *
 * The engine's guess at the missing offset is the real hazard: it is
 * undocumented, differs between Safari and Chrome historically, and a library
 * that "fixes" it later would silently change displayed times. So the assumption
 * is stated here once rather than re-derived per screen, and the server's
 * timezone is what gets shown. A viewer in another zone sees the server's
 * clock, which for an auditable record is the correct choice.
 */
export function formatTimestamp(value: string): string {
  // Appending the zone designator that `LocalDateTime.toString()` omits.
  const parsed = new Date(`${value}Z`)
  if (Number.isNaN(parsed.getTime())) {
    // Unparseable input is shown verbatim rather than as "Invalid Date", so a
    // bad value is visible instead of silently hidden behind a placeholder.
    return value
  }
  return parsed.toLocaleString(undefined, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  })
}

/** Date only, for expiry and batch-style fields. */
export function formatDate(value: string): string {
  const parsed = new Date(`${value}Z`)
  if (Number.isNaN(parsed.getTime())) return value
  return parsed.toLocaleDateString(undefined, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
  })
}
