import { Linking } from 'react-native'
import { BluetoothState, type BluetoothAdapterState } from '../specs/BluetoothState.nitro'
import { Permissions, type BluetoothPermission } from '../specs/Permissions.nitro'
import {
  Diagnostics,
  type DiagnosticAppState,
  type DiagnosticEvent,
  type DiagnosticEventType,
  type DiagnosticsConfig,
} from '../specs/Diagnostics.nitro'

export type { BluetoothAdapterState, BluetoothPermission, DiagnosticAppState, DiagnosticEvent, DiagnosticEventType, DiagnosticsConfig }

/** Bonded's settings. Every field is optional; omitted fields keep their current value. */
export interface BleConfig {
  /** Off by default. Turn on in debug builds to record what happens, for bug reports. */
  diagnostics?: DiagnosticsConfig
}

/** A listener registration. Call `remove()` to stop receiving updates. */
export interface Subscription {
  remove(): void
}

function subscription(remove: () => void): Subscription {
  let removed = false
  return {
    remove() {
      if (removed) return
      removed = true
      remove()
    },
  }
}

/**
 * Bonded's public API: one manager for every Bluetooth feature (decision 4). It combines the Nitro
 * HybridObjects and adds nothing but validation, error mapping and convenience (decision A5).
 */
export const ble = {
  /** The phone's Bluetooth radio state. Never shows the iOS permission prompt. */
  getState(): Promise<BluetoothAdapterState> {
    return BluetoothState.getState()
  },

  /** Calls `onChange` with the current state, then with every change. In components, prefer `useBluetoothState()`. */
  onStateChange(onChange: (state: BluetoothAdapterState) => void): Subscription {
    const id = BluetoothState.addStateListener(onChange)
    return subscription(() => BluetoothState.removeStateListener(id))
  },

  /** Whether the app may use Bluetooth, without asking the user. */
  getPermissionStatus(): Promise<BluetoothPermission> {
    return Permissions.getStatus()
  },

  /**
   * Asks for Bluetooth permission where the platform allows it, and resolves with the resulting status. On iOS
   * this is when the system prompt appears; before it, Bonded never shows it.
   */
  requestPermissions(): Promise<BluetoothPermission> {
    return Permissions.request()
  },

  /**
   * Calls `onChange` with the current permission status, then with every change, including changes made in the
   * system settings. In components, prefer `useBluetoothPermission()`.
   */
  onPermissionChange(onChange: (status: BluetoothPermission) => void): Subscription {
    const id = Permissions.addListener(onChange)
    return subscription(() => Permissions.removeListener(id))
  },

  /** Changes Bonded's settings. Turning diagnostics off also deletes the recorded events. */
  async configure(config: BleConfig): Promise<void> {
    const capacity = config.diagnostics?.capacity
    if (capacity != null && !(Number.isInteger(capacity) && capacity > 0)) {
      throw new RangeError(`diagnostics.capacity must be a positive whole number, got ${capacity}`)
    }
    if (config.diagnostics != null) await Diagnostics.configure(config.diagnostics)
  },

  /** The recorded events, oldest first; only those at or after `since` (milliseconds since the epoch) when given. */
  getDiagnostics(options: { since?: number } = {}): Promise<DiagnosticEvent[]> {
    return Diagnostics.getEvents(options.since)
  },

  /** Deletes the recorded events, in memory and in storage. */
  clearDiagnostics(): Promise<void> {
    return Diagnostics.clear()
  },

  /**
   * Calls `onEvent` with every event recorded from now on. Unlike the other listeners it sends no current value:
   * `getDiagnostics()` has the events so far. In components, prefer `useDiagnosticEvents()`.
   */
  onDiagnosticEvent(onEvent: (event: DiagnosticEvent) => void): Subscription {
    const id = Diagnostics.addListener(onEvent)
    return subscription(() => Diagnostics.removeListener(id))
  },

  /** Opens the app's system settings, where a `blocked` permission can be granted. */
  openSettings(): Promise<void> {
    return Linking.openSettings()
  },
}
