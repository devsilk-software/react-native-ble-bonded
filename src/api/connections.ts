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
}

export interface ConnectOptions extends PrepareOptions {
  /** Give up after this many milliseconds; `0` to wait until the device appears. Default 30,000. */
  timeout?: number
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

/** One device's connection, as the store keeps it. Replaced, never changed, so hooks can compare it. */
export interface DeviceConnection extends ConnectionChange {
  id: string
  name?: string
}

/** Calls each listener with every known device's current connection, then every change. */
class ConnectionStore {
  private readonly devices = new Map<string, DeviceConnection>()
  private readonly names = new Map<string, string>()
  /** Devices known only by a name a scan saw, kept so reading one twice returns the same object. */
  private readonly named = new Map<string, DeviceConnection>()
  private readonly listeners = new Set<(connection: DeviceConnection) => void>()
  private registration: Promise<void> | undefined
  private list: DeviceConnection[] = []

  /**
   * Starts the native listener once; resolves when it's registered and has reported the current state. A failed
   * registration isn't kept, so the next call tries again.
   */
  start(): Promise<void> {
    this.registration ??= Connection.addListener((change) => this.apply(change)).then(
      () => undefined,
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
  rememberName(id: string, name: string): void {
    if (this.names.get(id) === name) return
    this.names.set(id, name)
    const known = this.devices.get(id)
    if (known == null) {
      this.named.set(id, { id, name, state: 'disconnected' })
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
  return {
    retryTransientErrors: options.retryTransientErrors,
    closeGattOnDisconnect: options.closeGattOnDisconnect,
    waitForBonding: options.waitForBonding,
    discoveryDelayMs: delay,
    highPriorityDuringSetup: options.highPriorityDuringSetup,
    refreshServiceCache: options.refreshServiceCache,
  }
}

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
      await Connection.connect(this.id, { timeoutMs: Math.round(timeout), android })
    } catch (error) {
      throw toBleError(error, 'connect')
    }
  }

  /** Disconnects, or cancels a connection attempt; resolves once disconnected. */
  disconnect(): Promise<void> {
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

  /** Calls `onChange` with the device's current connection, then every change. In components, prefer `useDevice()`. */
  onConnectionStateChange(onChange: (change: ConnectionChange) => void): { remove(): void } {
    return connectionStore.subscribe((connection) => {
      if (connection.id === this.id) onChange({ state: connection.state, lastDisconnect: connection.lastDisconnect })
    })
  }
}
