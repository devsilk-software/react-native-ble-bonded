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
import { Connection } from '../specs/Connection.nitro'
import {
  connectionStore,
  defaultConnectTimeout,
  Device,
  toNativeAndroid,
  type DeviceConnection,
  type DeviceIdentity,
  type AndroidConnectionOptions,
  type Characteristic,
  type CharacteristicProperty,
  type ConnectionChange,
  type ConnectionState,
  type ConnectOptions,
  type DisconnectReason,
  type LastDisconnect,
  type Service,
  type ServiceCacheRefresh,
} from './connections'
import { prepare } from './prepare'
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
export type {
  AndroidConnectionOptions,
  Characteristic,
  CharacteristicProperty,
  ConnectionChange,
  ConnectionState,
  ConnectOptions,
  DisconnectReason,
  LastDisconnect,
  Service,
  ServiceCacheRefresh,
}
export { BleError, Device }
export type { DeviceIdentity }

/** Bonded's settings. Every field is optional; omitted fields keep their current value. */
export interface BleConfig {
  /** Off by default. Turn on in debug builds to record what happens, for bug reports. */
  diagnostics?: DiagnosticsConfig
  /** Android's connection workarounds for every connection, all on by default. iOS ignores them. */
  android?: AndroidConnectionOptions
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

/** Which device `ble.connect()` connects to: one by its ID, or the first that matches a filter. */
export type ConnectTarget = { id: string } | { name?: ScanNameFilter; services?: string[] }

/** How long Bonded keeps listening after the first match, to tell one device from several. */
const findAgainSettle = 1_500

function rememberedConnections(): DeviceConnection[] {
  return connectionStore
    .all()
    .filter((connection) => connection.isRemembered)
    .sort((a, b) => (b.lastConnectedAt ?? 0) - (a.lastConnectedAt ?? 0))
}

function deviceHandle(id: string): Device
function deviceHandle(): Device | undefined
function deviceHandle(id?: string): Device | undefined {
  if (id != null) return new Device(id)
  const remembered = rememberedConnections()
  if (remembered.length > 1) throw new Error('Several devices are remembered; pass an id')
  return remembered.length === 1 ? new Device(remembered[0]!.id) : undefined
}

/** What a scan that ended without a result means for a connection. */
function scanEnded(stop: ScanStop | undefined, message: string): BleError {
  if (stop?.error != null) return stop.error
  if (stop?.reason === 'replaced')
    return new BleError('cancelled', 'Another scan replaced the one looking for the device', { operation: 'connect' })
  return new BleError('deviceNotFound', message, { operation: 'connect' })
}

/** Scans for the first device that matches, within `timeoutMs` (0 for no limit). */
async function findFirst(
  target: { name?: ScanNameFilter; services?: string[] },
  timeoutMs: number,
  options: ConnectOptions
): Promise<ScannedDevice> {
  let onFound: (device: ScannedDevice) => void = () => {}
  const found = new Promise<ScannedDevice>((resolve) => (onFound = resolve))
  const scan = await ble.startScan(
    { name: target.name, services: target.services, timeout: timeoutMs, ...prepareOptionsOf(options) },
    (device) => onFound(device)
  )
  const result = await Promise.race([found, scan.stopped])
  scan.stop()
  if ('id' in result) return result
  throw scanEnded(result, 'No device matched before the timeout')
}

/** Scans for a remembered device's identity, within `timeoutMs`; resolves with the one device that matches it. */
async function findAgain(identity: DeviceIdentity, timeoutMs: number): Promise<ScannedDevice> {
  const matches = new Map<string, ScannedDevice>()
  let onFirst: () => void = () => {}
  const first = new Promise<void>((resolve) => (onFirst = resolve))
  const scan = await ble.startScan(
    {
      services: identity.services,
      name: identity.name,
      filter: (device) => identity.companyId == null || device.manufacturerData?.companyId === identity.companyId,
      allowDuplicates: true,
      timeout: timeoutMs,
      autoRequestPermission: false,
      autoEnableBluetooth: false,
    },
    (device) => {
      matches.set(device.id, device)
      onFirst()
    }
  )
  const settled = first.then(() => new Promise<'settled'>((resolve) => setTimeout(() => resolve('settled'), findAgainSettle)))
  const result = await Promise.race([settled, scan.stopped])
  scan.stop()
  // A scan that ended early, replaced or failed, never had the time to tell one device from several.
  if (result !== 'settled' && result.reason !== 'timeout') throw scanEnded(result, 'The remembered device was not found')
  if (matches.size > 1) {
    throw new BleError('deviceNotFound', 'Several devices match the remembered device; connect by ID', { operation: 'connect' })
  }
  const [match] = matches.values()
  if (match == null) throw new BleError('deviceNotFound', 'The remembered device was not found', { operation: 'connect' })
  return match
}

/**
 * Connects a device by its ID. When the platform says the ID is unknown (iOS after a network reset; a device never
 * seen), finds it again by how it was found, connects to the one match and, unless `remember` is off, forgets the
 * old ID, which can never connect again. A timeout is not a reason: the device is usually just off or away.
 */
async function connectRemembered(device: Device, options: ConnectOptions): Promise<Device> {
  const cancellations = connectionStore.cancelCount(device.id)
  try {
    await device.connect(options)
    return device
  } catch (error) {
    const identity = connectionStore.get(device.id)?.identity
    const isUnknown = error instanceof BleError && error.code === 'deviceNotFound'
    const isFindable = identity != null && (identity.name != null || identity.services.length > 0 || identity.companyId != null)
    if (!isUnknown || !isFindable) throw error
    const found = await findAgain(identity, options.timeout ?? defaultConnectTimeout)
    if (connectionStore.cancelCount(device.id) !== cancellations) {
      throw new BleError('cancelled', 'The connection attempt was cancelled', { deviceId: device.id, operation: 'connect' })
    }
    const moved = new Device(found.id)
    await moved.connect(options)
    if (moved.id !== device.id && options.remember !== false && device.isRemembered) await device.forget()
    return moved
  }
}

function prepareOptionsOf(options: ConnectOptions) {
  return { autoRequestPermission: options.autoRequestPermission, autoEnableBluetooth: options.autoEnableBluetooth }
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
    const accepted = new Set<string>()
    // Native code reports each device once; with a JavaScript filter that would hide every later report of a
    // device whose first report the filter rejected. So the filter sees every report, and the devices it accepts
    // are deduplicated here instead.
    const isDeduplicatedHere = filter != null && options.allowDuplicates !== true
    const native = toNativeOptions(isDeduplicatedHere ? { ...options, allowDuplicates: true } : options)
    await prepare(options)
    let onStopped: (stop: ScanStop) => void = () => {}
    const stopped = new Promise<ScanStop>((resolve) => (onStopped = resolve))
    let id: number
    try {
      id = await Scanner.start(
        native,
        (device) => {
          const scanned = fromNativeDevice(device)
          connectionStore.rememberScan(scanned.id, scanned)
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
    const android = toNativeAndroid(config.android)
    if (android != null) Connection.configureAndroid(android)
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

  /**
   * A handle for the device with this ID. Cheap: create one wherever it's needed. Without an ID, the one
   * remembered device, or `undefined` when none is; throws when several are, since it can't know which. Read
   * without an ID once remembered devices have loaded, for example after `await ble.getRememberedDevices()`.
   */
  device: deviceHandle,

  /**
   * Connects, and resolves with the device once it's ready.
   *
   * - `ble.connect()`: the remembered device. Rejects with `deviceNotFound` when none is remembered, and throws when
   *   several are.
   * - `ble.connect({ id })`: that device.
   * - `ble.connect({ name, services })`: scans, and connects to the first device that matches.
   *
   * When the platform says a remembered device's ID is unknown (iOS changes it after a network reset), Bonded scans
   * for how it was found (name, services, company ID), connects to the one device that matches and remembers it
   * under its new ID. Several matches: it doesn't guess, and rejects. A timeout never starts this search: the device
   * is usually just off or out of range. The scans it starts replace a running scan, `useScan()` included.
   */
  async connect(target?: ConnectTarget, options: ConnectOptions = {}): Promise<Device> {
    await connectionStore.start()
    if (target == null) {
      const device = deviceHandle()
      if (device == null) {
        throw new BleError('deviceNotFound', 'No device is remembered; connect by ID or by filter first', { operation: 'connect' })
      }
      return connectRemembered(device, options)
    }
    if ('id' in target) return connectRemembered(new Device(target.id), options)
    if (target.name == null && (target.services == null || target.services.length === 0)) {
      throw new RangeError('connect needs an id, a name or services; an empty filter would connect to any device')
    }
    // One timeout for finding and connecting together.
    const timeout = options.timeout ?? defaultConnectTimeout
    const started = Date.now()
    const found = await findFirst(target, timeout, options)
    const remaining = timeout === 0 ? 0 : Math.max(1, timeout - (Date.now() - started))
    const device = new Device(found.id)
    await device.connect({ ...options, timeout: remaining })
    return device
  },

  /** The remembered devices, most recently connected first. In components, prefer `useRememberedDevices()`. */
  async getRememberedDevices(): Promise<Device[]> {
    await connectionStore.start()
    return rememberedConnections().map((connection) => new Device(connection.id))
  },

  /** The devices that are connected, connecting or disconnecting. */
  async getConnectedDevices(): Promise<Device[]> {
    await connectionStore.start()
    return connectionStore
      .all()
      .filter((connection) => connection.state !== 'disconnected')
      .map((connection) => new Device(connection.id))
  },

  /**
   * Calls `onChange` with every known device's current connection, then every change, tagged with the device's
   * ID. For one device, `ble.device(id).onConnectionStateChange()`; in components, `useConnectedDevices()`.
   */
  onConnectionStateChange(onChange: (id: string, change: ConnectionChange) => void): Subscription {
    const listener = connectionStore.subscribe((connection) =>
      onChange(connection.id, { state: connection.state, lastDisconnect: connection.lastDisconnect })
    )
    return subscription(() => listener.remove())
  },

  /** Opens the app's system settings, where a `blocked` permission can be granted. */
  openSettings(): Promise<void> {
    return Linking.openSettings()
  },
}
