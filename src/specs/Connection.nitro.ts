import { Android, Apple, type HybridObject } from 'nitro-core'

export * from '#nitro/Connection'

/** Where a device's connection stands. `connected` means ready: its services are discovered. */
export type ConnectionState = 'disconnected' | 'connecting' | 'connected' | 'disconnecting'

/**
 * Why a device disconnected: the app asked, it went out of range, the device ended the connection, Bluetooth
 * went off, or anything else, including a failed or timed-out connection attempt.
 */
export type DisconnectReason = 'requested' | 'outOfRange' | 'deviceDisconnected' | 'bluetoothOff' | 'failed'

export type CharacteristicProperty =
  'broadcast' | 'read' | 'writeWithoutResponse' | 'write' | 'notify' | 'indicate' | 'authenticatedSignedWrites' | 'extendedProperties'

/** When Android refreshes its cache of a device's services: after discovery fails, every time, or never. */
export type ServiceCacheRefresh = 'onFailure' | 'always' | 'never'

/** Android's connection workarounds. Every field is optional; omitted fields keep their current value. */
export interface NativeAndroidConnectionOptions {
  retryTransientErrors?: boolean
  closeGattOnDisconnect?: boolean
  waitForBonding?: boolean
  discoveryDelayMs?: number
  highPriorityDuringSetup?: boolean
  refreshServiceCache?: ServiceCacheRefresh
  mtu?: number
}

/** How a device was found, kept with a remembered device to find it again when its ID changes. */
export interface NativeDeviceIdentity {
  name?: string
  services: string[]
  companyId?: number
}

export interface NativeConnectOptions {
  /** Milliseconds; 0 to wait until the device appears. */
  timeoutMs: number
  /** Remember the device once connected. */
  remember: boolean
  identity?: NativeDeviceIdentity
  android?: NativeAndroidConnectionOptions
}

/** One device's connection, as listeners see it. The disconnect fields describe the last disconnection. */
export interface NativeConnectionChange {
  id: string
  name?: string
  state: ConnectionState
  reason?: DisconnectReason
  /** A `BleError` code, for every reason but `requested`. */
  errorCode?: string
  errorMessage?: string
  platformCode?: number
  isRemembered: boolean
  /** Set while the device is remembered. */
  identity?: NativeDeviceIdentity
  /** Milliseconds since the Unix epoch; set while the device is remembered. */
  lastConnectedAt?: number
}

export interface NativeCharacteristic {
  uuid: string
  properties: CharacteristicProperty[]
  /** Its descriptors' UUIDs; set only when asked for. */
  descriptors?: string[]
}

export interface NativeService {
  uuid: string
  characteristics: NativeCharacteristic[]
}

@Apple({
  language: 'swift',
  implementation: { className: 'HybridConnection' },
})
@Android({
  language: 'kotlin',
  implementation: { className: 'HybridConnection' },
})
export declare class Connection extends HybridObject {
  /**
   * Connects and discovers the device's services; resolves once it's ready. Resolves at once when already
   * connected and shares a running attempt. Rejects with a `BleError` message.
   */
  static connect(id: string, options: NativeConnectOptions): Promise<void>

  /** Disconnects, or cancels a connection attempt; resolves once disconnected. */
  static disconnect(id: string): Promise<void>

  /** Every known device's current connection, remembered devices included. */
  static getConnections(): Promise<NativeConnectionChange[]>

  /** Disconnects the device and forgets it; the system's pairing stays. */
  static forget(id: string): Promise<void>

  /**
   * The services discovered when the device connected, with each characteristic's descriptors when `descriptors`
   * is true (discovered then, the first time). Rejects with `notConnected` while it isn't connected.
   */
  static getServices(id: string, descriptors: boolean): Promise<NativeService[]>

  /**
   * Calls `onChange` with every known device's current connection, remembered devices included, then every
   * change. Resolves with an ID for
   * `removeListener` once registered.
   */
  static addListener(onChange: (change: NativeConnectionChange) => void): Promise<number>

  /** Stops the listener `addListener` returned this ID for. */
  static removeListener(id: number): void

  /** Changes Android's connection workarounds for every connection; does nothing on iOS. */
  static configureAndroid(options: NativeAndroidConnectionOptions): void
}
