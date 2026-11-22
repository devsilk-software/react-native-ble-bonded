import type { DiagnosticEvent } from '../api/ble'

function key(event: DiagnosticEvent): string {
  return JSON.stringify([event.at, event.type, event.appState, event.deviceId, event.code, event.platformCode, event.detail])
}

/**
 * The stored events followed by the live ones, without the live events the stored ones already hold (both can
 * report an event recorded while the stored ones were loading), keeping only the newest `limit`.
 */
export function latestEvents(stored: DiagnosticEvent[], live: DiagnosticEvent[], limit: number): DiagnosticEvent[] {
  const seen = new Set(stored.map(key))
  const merged = [...stored, ...live.filter((event) => !seen.has(key(event)))]
  return merged.slice(Math.max(0, merged.length - limit))
}
