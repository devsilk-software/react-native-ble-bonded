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
import { Scanner } from '../specs/Scanner.nitro'
import { BleError, toBleError, type BleErrorCode } from './BleError'
import { maxCapacity, notifyDiagnosticsReset, subscribeToDiagnostics } from './diagnostics'
import {
  fromNativeDevice,
  fromNativeStop,
  toNativeOptions,
  type ManufacturerData,
  type Scan,
  type ScanNameFilter,
  type ScannedDevice,
  type ScanOptions,
  type ScanStop,
  type ScanStopReason,
} from './scanning'

export type { BluetoothAdapterState, BluetoothPermission, DiagnosticAppState, DiagnosticEvent, DiagnosticEventType, DiagnosticsConfig }
export type { BleErrorCode, ManufacturerData, Scan, ScanNameFilter, ScannedDevice, ScanOptions, ScanStop, ScanStopReason }
export { BleError }

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

  /**
   * Asks the user to turn Bluetooth on and resolves with the resulting state. Android shows the system dialog;
   * iOS has no public way to, so there it resolves with the current state without showing anything.
   */
  requestBluetoothOn(): Promise<BluetoothAdapterState> {
    return BluetoothState.requestEnable()
  },

  /**
   * Starts scanning, replacing a running scan, and calls `onDevice` with each device found. Unless switched off,
   * it first asks for permission when it can still be asked, and asks to turn Bluetooth on. Rejects with a
   * `BleError` (`unauthorized`, `bluetoothOff`, `unsupported`) when it can't scan. Stops after 30 seconds unless
   * `timeout` says otherwise. In components, prefer `useScan()`.
   */
  async startScan(options: ScanOptions, onDevice: (device: ScannedDevice) => void): Promise<Scan> {
    const filter = options.filter
    // Native code reports each device once; with a JavaScript filter that would hide every later report of a
    // device whose first report the filter rejected. So the filter sees every report, and the devices it accepts
    // are deduplicated here instead.
    const isDeduplicatedHere = filter != null && options.allowDuplicates !== true
    const native = toNativeOptions(isDeduplicatedHere ? { ...options, allowDuplicates: true } : options)
    if (options.autoRequestPermission !== false && (await Permissions.getStatus()) === 'denied') {
      await Permissions.request()
    }
    if (options.autoEnableBluetooth !== false && (await BluetoothState.getState()) === 'poweredOff') {
      await BluetoothState.requestEnable()
    }
    const accepted = new Set<string>()
    let onStopped: (stop: ScanStop) => void = () => {}
    const stopped = new Promise<ScanStop>((resolve) => (onStopped = resolve))
    let id: number
    try {
      id = await Scanner.start(
        native,
        (device) => {
          const scanned = fromNativeDevice(device)
          if (filter != null && !filter(scanned)) return
          if (isDeduplicatedHere) {
            if (accepted.has(scanned.id)) return
            accepted.add(scanned.id)
          }
          onDevice(scanned)
        },
        (stop) => onStopped(fromNativeStop(stop))
      )
    } catch (error) {
      throw toBleError(error, 'scan')
    }
    return { stop: () => Scanner.stop(id), stopped }
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
