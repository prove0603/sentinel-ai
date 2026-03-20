/**
 * Formats an ISO/LocalDateTime string (e.g. "2026-03-20T15:46:28.946113") to "YYYY-MM-DD HH:mm:ss".
 */
export function formatTime(value: string | null | undefined): string {
  if (!value) return ''
  return value.replace('T', ' ').replace(/\.\d+$/, '').substring(0, 19)
}
