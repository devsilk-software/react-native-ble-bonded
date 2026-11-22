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
import { maxCapacity, notifyDiagnosticsReset, subscribeToDiagnostics } from './diagnostics'

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
    const diagnostics = config.diagnostics
    if (diagnostics == null) return
    const capacity = diagnostics.capacity
    if (capacity != null && !(Number.isInteger(capacity) && capacity > 0 && capacity <= maxCapacity)) {
      throw new RangeError(`diagnostics.capacity must be a whole number from 1 to ${maxCapacity}, got ${capacity}`)
    }
    await Diagnostics.configure(diagnostics)
    if (diagnostics.enabled != null) notifyDiagnosticsReset()
  },

  /**
   * The recorded events, oldest first; only those at or after `since` (milliseconds since the epoch, rounded up
   * to a whole millisecond) when given.
   */
  async getDiagnostics(options: { since?: number } = {}): Promise<DiagnosticEvent[]> {
    const since = options.since == null ? undefined : Math.ceil(options.since)
    if (since != null && !Number.isSafeInteger(since)) {
      throw new RangeError(`since must be a time in milliseconds, got ${options.since}`)
    }
    return Diagnostics.getEvents(since)
  },

  /** Deletes the recorded events, in memory and in storage. */
  async clearDiagnostics(): Promise<void> {
    await Diagnostics.clear()
    notifyDiagnosticsReset()
  },

  /**
   * Calls `onEvent` with every event recorded from now on. Unlike the other listeners it sends no current value:
   * `getDiagnostics()` has the events so far. In components, prefer `useDiagnosticEvents()`.
   */
  onDiagnosticEvent(onEvent: (event: DiagnosticEvent) => void): Subscription {
    return subscribeToDiagnostics(onEvent).subscription
  },

  /** Opens the app's system settings, where a `blocked` permission can be granted. */
  openSettings(): Promise<void> {
    return Linking.openSettings()
  },
}
