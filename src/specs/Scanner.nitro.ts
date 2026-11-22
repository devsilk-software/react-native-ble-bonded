import { Android, Apple, type HybridObject } from 'nitro-core'

export * from '#nitro/Scanner'

/** What the native scanner looks for. `src/api` turns the public options into this. */
export interface NativeScanOptions {
  /** Full or short service UUIDs; empty for every device. */
  services: string[]
  nameEquals?: string
  nameStartsWith?: string
  nameEndsWith?: string
  nameIncludes?: string
  allowDuplicates: boolean
  fullAdvertisement: boolean
  /** Milliseconds; 0 for never. */
  timeoutMs: number
}

/** Manufacturer-specific data with its company ID split off. */
export interface ManufacturerData {
  /** The Bluetooth SIG company identifier, such as `0x0059` for Nordic Semiconductor. */
  companyId: number
  data: ArrayBuffer
}

export interface NativeServiceData {
  uuid: string
  data: ArrayBuffer
}

/** One device as the native scanner reports it. The fields after `manufacturerData` come with `fullAdvertisement`. */
export interface NativeScannedDevice {
  id: string
  name?: string
  rssi: number
  serviceUuids: string[]
  manufacturerData?: ManufacturerData
  serviceData?: NativeServiceData[]
  txPowerLevel?: number
  isConnectable?: boolean
  rawManufacturerData?: ArrayBuffer
  solicitedServiceUuids?: string[]
  overflowServiceUuids?: string[]
  rawScanRecord?: ArrayBuffer
  primaryPhy?: number
  secondaryPhy?: number
  timestamp?: number
}

/** Why a scan ended: the app stopped it, a new scan replaced it, it timed out, or something failed. */
export type ScanStopReason = 'stopped' | 'replaced' | 'timeout' | 'failed'

export interface NativeScanStop {
  reason: ScanStopReason
  /** A `BleError` code, when the scan failed. */
  errorCode?: string
  errorMessage?: string
  platformCode?: number
}

@Apple({
  language: 'swift',
  implementation: { className: 'HybridScanner' },
})
@Android({
  language: 'kotlin',
  implementation: { className: 'HybridScanner' },
})
export declare class Scanner extends HybridObject {
  /**
   * Starts a scan, replacing the running one, and resolves with its ID once it runs. Rejects with a `BleError`
   * message when the phone can't scan. `onStop` is called once when the scan ends, for whatever reason.
   */
  static start(
    options: NativeScanOptions,
    onDevice: (device: NativeScannedDevice) => void,
    onStop: (stop: NativeScanStop) => void
  ): Promise<number>

  /** Stops the scan with this ID; does nothing when it has already ended. */
  static stop(id: number): void
}
