// The connection store and the device handle. One native listener feeds the store for the app's lifetime, so a
// handle's fields read synchronously and every handle for an ID agrees.
import {
  Connection,
  type CharacteristicProperty,
  type ConnectionState,
  type DisconnectReason,
  type NativeAndroidConnectionOptions,
  type NativeConnectionChange,
  type ServiceCacheRefresh,
} from '../specs/Connection.nitro'
import { Gatt } from '../specs/Gatt.nitro'
import { BleError, toBleError, type BleErrorCode } from './BleError'
import { prepare, type PrepareOptions } from './prepare'

export type { CharacteristicProperty, ConnectionState, DisconnectReason, ServiceCacheRefresh }

/** The last time a device disconnected, and why. `error` is set for every reason but `requested`. */
export interface LastDisconnect {
  reason: DisconnectReason
  error?: BleError
}

/** A device's connection, as listeners see it. */
export interface ConnectionChange {
  state: ConnectionState
  lastDisconnect?: LastDisconnect
}

/** Android's connection workarounds. All on by default; iOS ignores them. */
export interface AndroidConnectionOptions {
  /** Retry GATT status 133 and similar failures that pass on their own. */
  retryTransientErrors?: boolean
  /** Close the GATT client after a disconnection; a leaked client causes later failures. */
  closeGattOnDisconnect?: boolean
  /** Discover services only once pairing has finished. */
  waitForBonding?: boolean
  /** Milliseconds to wait before discovery on a bonded device; `0` for none. Default 600. */
  discoveryDelay?: number
  /** High connection priority until the device is ready, then balanced. */
  highPriorityDuringSetup?: boolean
  /** When to refresh Android's cache of the device's services. Default `onFailure`. */
  refreshServiceCache?: ServiceCacheRefresh
  /**
   * The MTU to ask for once connected, so writes without response carry more than 20 bytes. Default 517, the most
   * Bluetooth allows; `0` keeps 23. iOS negotiates by itself.
   */
  mtu?: number
}

export interface ConnectOptions extends PrepareOptions {
  /** Give up after this many milliseconds; `0` to wait until the device appears. Default 30,000. */
  timeout?: number
  /** Remember the device once connected, so `ble.connect()` finds it after a relaunch. Default `true`. */
  remember?: boolean
  /** This connection's Android workarounds, over those set with `ble.configure({ android })`. */
  android?: AndroidConnectionOptions
}

export interface Characteristic {
  /** Full lowercase UUID. */
  uuid: string
  properties: CharacteristicProperty[]
}

export interface Service {
  /** Full lowercase UUID. */
  uuid: string
  characteristics: Characteristic[]
}

export const defaultConnectTimeout = 30_000

/** How long a read or a write waits for the device by default. */
export const defaultOperationTimeout = 10_000

export interface OperationOptions {
  /** Give up after this many milliseconds. Default 10,000. */
  timeout?: number
}

export interface SubscribeOptions {
  /**
   * Called when switching notifications back on after a reconnect failed, such as when the device now wants
   * pairing. The subscription stays and Bonded tries again after the next reconnect. A disconnect itself isn't an
   * error: connection listeners report it.
   */
  onError?: (error: BleError) => void
  /** Called once when Bonded removed the subscription itself, because the app forgot the device. */
  onEnd?: () => void
}

export interface WriteOptions extends OperationOptions {
  /**
   * Write without asking the device to confirm. Faster; resolves once the phone has sent the value. A value longer
   * than the device's maximum write length rejects with `valueTooLong`; Bonded never splits it.
   */
  withoutResponse?: boolean
}

/** One device's connection, as the store keeps it. Replaced, never changed, so hooks can compare it. */
export interface DeviceConnection extends ConnectionChange {
  id: string
  name?: string
  isRemembered: boolean
  /** How the device was found; kept while it's remembered, to find it again when its ID changes. */
  identity?: DeviceIdentity
  /** Milliseconds since the Unix epoch; set while the device is remembered. */
  lastConnectedAt?: number
}

/** How a device was found: its advertised name, services and manufacturer company ID. */
export interface DeviceIdentity {
  name?: string
  services: string[]
  companyId?: number
}

/** Calls each listener with every known device's current connection, then every change. */
class ConnectionStore {
  private readonly devices = new Map<string, DeviceConnection>()
  private readonly names = new Map<string, string>()
  /** What scans saw of each device, passed to `connect()` so a remembered device can be found again. */
  private readonly identities = new Map<string, DeviceIdentity>()
  /** Devices known only by a name a scan saw, kept so reading one twice returns the same object. */
  private readonly named = new Map<string, DeviceConnection>()
  private readonly listeners = new Set<(connection: DeviceConnection) => void>()
  /** Counts each device's `disconnect()` and `forget()` calls, so work in JavaScript can see it was cancelled. */
  private readonly cancellations = new Map<string, number>()
  private registration: Promise<void> | undefined
  private list: DeviceConnection[] = []

  /**
   * Starts the native listener once; resolves when it's registered and has reported the current state. A failed
   * registration isn't kept, so the next call tries again.
   */
  start(): Promise<void> {
    this.registration ??= Connection.addListener((change) => this.apply(change)).then(
      // The listener's first reports can arrive after its registration resolves; the snapshot can't. A device the
      // listener already reported is newer than the snapshot, so only the others are taken from it.
      async () => {
        const current = await Connection.getConnections()
        current.filter((change) => !this.devices.has(change.id)).forEach((change) => this.apply(change))
      },
      (error: unknown) => {
        this.registration = undefined
        throw error
      }
    )
    return this.registration
  }

  /** Starts the listener for a synchronous read; a failure shows up on the next awaited call instead. */
  private startQuietly(): void {
    this.start().catch(() => {})
  }

  get(id: string): DeviceConnection | undefined {
    this.startQuietly()
    return this.devices.get(id) ?? this.named.get(id)
  }

  /** Every known device, in a list that's replaced on every change. */
  all(): DeviceConnection[] {
    this.startQuietly()
    return this.list
  }

  subscribe(listener: (connection: DeviceConnection) => void): { remove(): void } {
    this.startQuietly()
    this.listeners.add(listener)
    this.devices.forEach(listener)
    return { remove: () => this.listeners.delete(listener) }
  }

  /** A name a scan saw, so a handle shows it before the device has connected. */
  /** What a scan saw of a device: its name, so a handle shows it before connecting, and its identity. */
  rememberScan(id: string, scanned: { name?: string; serviceUuids: string[]; manufacturerData?: { companyId: number } }): void {
    this.identities.set(id, {
      name: scanned.name,
      services: scanned.serviceUuids,
      companyId: scanned.manufacturerData?.companyId,
    })
    if (scanned.name != null) this.rememberName(id, scanned.name)
  }

  cancel(id: string): void {
    this.cancellations.set(id, this.cancelCount(id) + 1)
  }

  cancelCount(id: string): number {
    return this.cancellations.get(id) ?? 0
  }

  /** The identity scans saw, if any. */
  scannedIdentity(id: string): DeviceIdentity | undefined {
    return this.identities.get(id)
  }

  rememberName(id: string, name: string): void {
    if (this.names.get(id) === name) return
    this.names.set(id, name)
    const known = this.devices.get(id)
    if (known == null) {
      this.named.set(id, { id, name, state: 'disconnected', isRemembered: false })
      this.listeners.forEach((listener) => listener(this.named.get(id)!))
    } else if (known.name == null) {
      this.set({ ...known, name })
    }
  }

  private apply(change: NativeConnectionChange): void {
    this.set({
      id: change.id,
      name: change.name ?? this.names.get(change.id),
      state: change.state,
      lastDisconnect: change.reason == null ? undefined : { reason: change.reason, error: errorOf(change) },
      isRemembered: change.isRemembered,
      identity: change.identity,
      lastConnectedAt: change.lastConnectedAt,
    })
  }

  private set(connection: DeviceConnection): void {
    this.devices.set(connection.id, connection)
    this.list = [...this.devices.values()]
    this.listeners.forEach((listener) => listener(connection))
  }
}

function errorOf(change: NativeConnectionChange): BleError | undefined {
  if (change.errorCode == null) return undefined
  return new BleError(change.errorCode as BleErrorCode, change.errorMessage ?? 'Disconnected', {
    platformCode: change.platformCode,
    deviceId: change.id,
  })
}

export const connectionStore = new ConnectionStore()

export function toNativeAndroid(options: AndroidConnectionOptions | undefined): NativeAndroidConnectionOptions | undefined {
  if (options == null) return undefined
  const delay = options.discoveryDelay
  if (delay != null && !(Number.isFinite(delay) && delay >= 0)) {
    throw new RangeError(`android.discoveryDelay must be 0 or more milliseconds, got ${delay}`)
  }
  const mtu = options.mtu
  if (mtu != null && !(Number.isInteger(mtu) && (mtu === 0 || (mtu >= 23 && mtu <= 517)))) {
    throw new RangeError(`android.mtu must be 0 or a whole number from 23 to 517, got ${mtu}`)
  }
  return {
    retryTransientErrors: options.retryTransientErrors,
    closeGattOnDisconnect: options.closeGattOnDisconnect,
    waitForBonding: options.waitForBonding,
    discoveryDelayMs: delay,
    highPriorityDuringSetup: options.highPriorityDuringSetup,
    refreshServiceCache: options.refreshServiceCache,
    mtu: options.mtu,
  }
}

/** Subscriptions being removed natively, per device and characteristic. */
const unsubscribing = new Map<string, Promise<void>>()

/**
 * A device, addressed by its ID. The handle holds only the ID and reads the live state, so it never goes stale
 * and every handle for an ID agrees. Create one anywhere with `ble.device(id)`.
 */
export class Device {
  constructor(readonly id: string) {}

  /** The name a scan or the platform last saw. */
  get name(): string | undefined {
    return connectionStore.get(this.id)?.name
  }

  get connectionState(): ConnectionState {
    return connectionStore.get(this.id)?.state ?? 'disconnected'
  }

  get lastDisconnect(): LastDisconnect | undefined {
    return connectionStore.get(this.id)?.lastDisconnect
  }

  /** Whether Bonded remembers the device, so `ble.connect()` finds it after a relaunch. */
  get isRemembered(): boolean {
    return connectionStore.get(this.id)?.isRemembered ?? false
  }

  /** Disconnects the device and forgets it. The system's pairing stays. */
  forget(): Promise<void> {
    connectionStore.cancel(this.id)
    return Connection.forget(this.id)
  }

  /**
   * Connects and discovers the device's services; resolves once it's ready. Waits for the device to advertise,
   * so no scan is needed for a device seen before. Resolves at once when already connected and shares a running
   * attempt. Unless switched off, first asks for permission and to turn Bluetooth on. Rejects with a `BleError`
   * (`timeout` after 30 s by default, `cancelled` when `disconnect()` is called meanwhile).
   */
  async connect(options: ConnectOptions = {}): Promise<void> {
    const timeout = options.timeout ?? defaultConnectTimeout
    if (!(Number.isFinite(timeout) && timeout >= 0)) {
      throw new RangeError(`timeout must be 0 or more milliseconds, got ${timeout}`)
    }
    const android = toNativeAndroid(options.android)
    await connectionStore.start()
    await prepare(options)
    try {
      const identity = connectionStore.scannedIdentity(this.id)
      await Connection.connect(this.id, { timeoutMs: Math.round(timeout), remember: options.remember !== false, identity, android })
    } catch (error) {
      throw toBleError(error, 'connect')
    }
  }

  /** Disconnects, or cancels a connection attempt, also one `ble.connect()` is still finding; resolves once disconnected. */
  disconnect(): Promise<void> {
    connectionStore.cancel(this.id)
    return Connection.disconnect(this.id)
  }

  /** The services discovered when the device connected. Rejects with `notConnected` while it isn't. */
  async getServices(): Promise<Service[]> {
    try {
      return await Connection.getServices(this.id)
    } catch (error) {
      throw toBleError(error, 'getServices')
    }
  }

  /**
   * Reads a characteristic's value. The device must be connected; otherwise it rejects at once with `notConnected`.
   * Rejects with `characteristicNotFound` for a UUID the device doesn't have, `unsupported` when the characteristic
   * can't be read, `timeout` after 10 s by default, `pairingRequired` or `gattError` when the device refuses.
   */
  async read(service: string, characteristic: string, options: OperationOptions = {}): Promise<ArrayBuffer> {
    this.requireConnected('read')
    try {
      return await Gatt.read(this.id, service, characteristic, operationTimeout(options))
    } catch (error) {
      throw toBleError(error, 'read', this.id)
    }
  }

  /**
   * Writes a value, waiting for the device to confirm unless `withoutResponse`. Values up to 512 bytes go as a long
   * write when they need to. The device must be connected; otherwise it rejects at once with `notConnected`. Also
   * rejects with `valueTooLong`, `characteristicNotFound`, `unsupported`, `timeout`, `pairingRequired` or `gattError`.
   */
  async write(service: string, characteristic: string, value: ArrayBuffer | Uint8Array, options: WriteOptions = {}): Promise<void> {
    const operation = options.withoutResponse === true ? 'write without response' : 'write'
    this.requireConnected(operation)
    const buffer = value instanceof Uint8Array ? copyToBuffer(value) : value
    try {
      await Gatt.write(this.id, service, characteristic, buffer, options.withoutResponse !== true, operationTimeout(options))
    } catch (error) {
      throw toBleError(error, operation, this.id)
    }
  }

  /**
   * Subscribes to the characteristic's notifications (or indications; Bonded picks) and resolves once the device
   * switched them on. `onValue` gets each value in order. The subscription lives until `remove()`, across
   * disconnects: after every reconnect Bonded switches notifications back on, and `onValue` keeps firing. One
   * subscription per characteristic: a second rejects with `alreadySubscribed`. The device must be connected;
   * otherwise it rejects at once with `notConnected`. `forget()` removes the device's subscriptions.
   */
  async subscribe(
    service: string,
    characteristic: string,
    onValue: (value: ArrayBuffer) => void,
    options: SubscribeOptions = {}
  ): Promise<{ remove(): void }> {
    this.requireConnected('subscribe')
    const deviceId = this.id
    const key = `${deviceId}\n${service.toLowerCase()}\n${characteristic.toLowerCase()}`
    let isRemoved = false
    let id: number
    try {
      // Removing a subscription finishes natively a moment later; subscribing again waits for it.
      await unsubscribing.get(key)
      id = await Gatt.subscribe(
        deviceId,
        service,
        characteristic,
        (values) => {
          if (!isRemoved) values.forEach(onValue)
        },
        (message) => {
          const error = toBleError(new Error(message), 'subscribe', deviceId)
          if (!isRemoved && error instanceof BleError) options.onError?.(error)
        },
        () => {
          if (isRemoved) return
          isRemoved = true
          options.onEnd?.()
        }
      )
    } catch (error) {
      throw toBleError(error, 'subscribe', deviceId)
    }
    return {
      remove() {
        if (isRemoved) return
        isRemoved = true
        const removal = Gatt.unsubscribe(id).catch(() => {})
        unsubscribing.set(key, removal)
        void removal.then(() => {
          if (unsubscribing.get(key) === removal) unsubscribing.delete(key)
        })
      },
    }
  }

  /**
   * Cuts `data` to the maximum write length and writes the chunks in order, as one operation: no other read or write
   * on the device gets between them, so firmware that puts them back together can rely on that. Waits for the
   * device to confirm each chunk unless `withoutResponse`; the timeout applies to each chunk. Empty data resolves
   * without sending anything. Stops at the first chunk that fails and rejects with its error plus `chunkIndex`
   * (counting from 0), `chunkCount` and `sentLength`, the bytes the device took before it. The device must be
   * connected; otherwise it rejects at once with `notConnected`.
   */
  async writeChunked(service: string, characteristic: string, data: ArrayBuffer | Uint8Array, options: WriteOptions = {}): Promise<void> {
    this.requireConnected('writeChunked')
    const buffer = data instanceof Uint8Array ? copyToBuffer(data) : data
    try {
      await Gatt.writeChunked(this.id, service, characteristic, buffer, options.withoutResponse !== true, operationTimeout(options))
    } catch (error) {
      throw toBleError(error, 'writeChunked', this.id)
    }
  }

  /**
   * The most one write carries without being split, in bytes: the connection's MTU minus 3. The phone and the device
   * agree on it each time they connect, so it differs between devices and phones; read it while connected. A write
   * with response still takes up to 512 bytes as a long write. Rejects with `notConnected` while the device isn't
   * connected.
   */
  async getMaxWriteLength(): Promise<number> {
    this.requireConnected('getMaxWriteLength')
    try {
      return await Gatt.getMaxWriteLength(this.id)
    } catch (error) {
      throw toBleError(error, 'getMaxWriteLength', this.id)
    }
  }

  /**
   * The connected device's signal strength in dBm: closer to 0 is stronger, such as -50 next to the phone and -90
   * near the edge of range. Rejects with `notConnected` while the device isn't connected and `timeout` after 10 s by
   * default. In components, `useRssi()` reads it on an interval.
   */
  async readRssi(options: OperationOptions = {}): Promise<number> {
    this.requireConnected('readRssi')
    try {
      return await Gatt.readRssi(this.id, operationTimeout(options))
    } catch (error) {
      throw toBleError(error, 'readRssi', this.id)
    }
  }

  private requireConnected(operation: string): void {
    if (this.connectionState === 'connected') return
    throw new BleError('notConnected', `The device is ${this.connectionState}; connect it before you ${operation}.`, {
      deviceId: this.id,
      operation,
    })
  }

  /** Calls `onChange` with the device's current connection, then every change. In components, prefer `useDevice()`. */
  onConnectionStateChange(onChange: (change: ConnectionChange) => void): { remove(): void } {
    return connectionStore.subscribe((connection) => {
      if (connection.id === this.id) onChange({ state: connection.state, lastDisconnect: connection.lastDisconnect })
    })
  }
}

function operationTimeout(options: OperationOptions): number {
  const timeout = options.timeout ?? defaultOperationTimeout
  if (!(Number.isFinite(timeout) && timeout > 0)) throw new RangeError(`timeout must be more than 0 milliseconds, got ${timeout}`)
  return Math.round(timeout)
}

/** The bytes a view covers, as their own buffer: a view can cover part of a larger one. */
function copyToBuffer(view: Uint8Array): ArrayBuffer {
  const copy = new Uint8Array(view.byteLength)
  copy.set(view)
  return copy.buffer
}
