import { useEffect, useState, useSyncExternalStore } from 'react'
import { ble, type BluetoothAdapterState, type BluetoothPermission, type DiagnosticEvent } from '../api/ble'
import { createExternalStore } from './externalStore'
import { latestEvents } from './latestEvents'

const bluetoothState = createExternalStore<BluetoothAdapterState, 'unknown'>((onChange) => ble.onStateChange(onChange), 'unknown')
const bluetoothPermission = createExternalStore<BluetoothPermission, undefined>((onChange) => ble.onPermissionChange(onChange), undefined)

/**
 * The phone's Bluetooth radio state, re-rendering on every change. `unknown` until the first report. Never shows
 * the iOS permission prompt.
 */
export function useBluetoothState(): BluetoothAdapterState {
  return useSyncExternalStore(bluetoothState.subscribe, bluetoothState.getSnapshot)
}

/**
 * Whether the app may use Bluetooth, re-rendering on every change, including changes made in the system
 * settings. `undefined` until the first report.
 */
export function useBluetoothPermission(): BluetoothPermission | undefined {
  return useSyncExternalStore(bluetoothPermission.subscribe, bluetoothPermission.getSnapshot)
}

/**
 * The newest recorded diagnostic events, oldest first: those stored so far, then each new one as it is recorded.
 * Keeps at most `limit` (100 by default). Empty while diagnostics are off.
 */
export function useDiagnosticEvents({ limit = 100 }: { limit?: number } = {}): DiagnosticEvent[] {
  const [events, setEvents] = useState<DiagnosticEvent[]>([])

  useEffect(() => {
    let isActive = true
    let stored: DiagnosticEvent[] | undefined
    let live: DiagnosticEvent[] = []
    const update = () => {
      if (isActive && stored != null) setEvents(latestEvents(stored, live, limit))
    }
    const subscription = ble.onDiagnosticEvent((event) => {
      live = latestEvents([], [...live, event], limit)
      update()
    })
    ble
      .getDiagnostics()
      .then((events) => {
        stored = events
        update()
      })
      .catch(() => {
        stored = []
        update()
      })
    return () => {
      isActive = false
      subscription.remove()
    }
  }, [limit])

  return events
}

export type { BluetoothAdapterState, BluetoothPermission, DiagnosticEvent }
