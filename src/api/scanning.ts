// Scanning types and the conversion between the public options and results and the native ones.
import type { NativeScanOptions, NativeScannedDevice, NativeScanStop, ScanStopReason } from '../specs/Scanner.nitro'
import { BleError, type BleErrorCode } from './BleError'

export type { ScanStopReason }

/** Which device names a scan reports: an exact name, or parts that must all match. */
export type ScanNameFilter = string | { startsWith?: string; endsWith?: string; includes?: string }

export interface ScanOptions {
  /** Only devices advertising one of these services (short `180D` or full UUIDs). Needed to scan in the background. */
  services?: string[]
  /** Only devices with this name, or whose name matches these parts. Matched natively. */
  name?: ScanNameFilter
  /** Anything else, such as a company ID or an RSSI threshold. Runs in JavaScript for each report. */
  filter?: (device: ScannedDevice) => boolean
  /** Report every advertisement, not just each device's first. Default `false`. */
  allowDuplicates?: boolean
  /** Include every advertisement field the platform reports. Default `false`. */
  fullAdvertisement?: boolean
  /** Stop after this many milliseconds; `0` for never. Default 30,000. */
  timeout?: number
  /** Ask for Bluetooth permission first when it can still be asked. Default `true`. */
  autoRequestPermission?: boolean
  /** Ask the user to turn Bluetooth on first (Android; iOS can't). Default `true`. */
  autoEnableBluetooth?: boolean
}

/** Manufacturer-specific data with its company ID split off. */
export interface ManufacturerData {
  /** The Bluetooth SIG company identifier, such as `0x0059` for Nordic Semiconductor. */
  companyId: number
  data: ArrayBuffer
}

/** A device a scan found. The fields after `manufacturerData` come only with `fullAdvertisement: true`. */
export interface ScannedDevice {
  id: string
  name?: string
  rssi: number
  /** Full lowercase UUIDs. */
  serviceUuids: string[]
  manufacturerData?: ManufacturerData
  serviceData?: Record<string, ArrayBuffer>
  txPowerLevel?: number
  isConnectable?: boolean
  rawManufacturerData?: ArrayBuffer
  solicitedServiceUuids?: string[]
  /** iOS only. */
  overflowServiceUuids?: string[]
  /** Android only: the whole advertisement as received. */
  rawScanRecord?: ArrayBuffer
  /** Android 8+ only. */
  primaryPhy?: number
  /** Android 8+ only. */
  secondaryPhy?: number
  /** Milliseconds since the Unix epoch. */
  timestamp?: number
}

/** Why and how a scan ended. */
export interface ScanStop {
  reason: ScanStopReason
  /** Set when the scan failed, such as when Bluetooth was switched off. */
  error?: BleError
}

/** A running scan. */
export interface Scan {
  /** Stops the scan. Does nothing when it has already ended. */
  stop(): void
  /** Resolves when the scan ends, for whatever reason. */
  readonly stopped: Promise<ScanStop>
}

export const defaultScanTimeout = 30_000

export function toNativeOptions(options: ScanOptions): NativeScanOptions {
  const timeout = options.timeout ?? defaultScanTimeout
  if (!(Number.isFinite(timeout) && timeout >= 0)) {
    throw new RangeError(`timeout must be 0 or more milliseconds, got ${timeout}`)
  }
  const name = options.name
  return {
    services: options.services ?? [],
    nameEquals: typeof name === 'string' ? name : undefined,
    nameStartsWith: typeof name === 'object' ? name.startsWith : undefined,
    nameEndsWith: typeof name === 'object' ? name.endsWith : undefined,
    nameIncludes: typeof name === 'object' ? name.includes : undefined,
    allowDuplicates: options.allowDuplicates ?? false,
    fullAdvertisement: options.fullAdvertisement ?? false,
    timeoutMs: Math.round(timeout),
  }
}

export function fromNativeDevice(native: NativeScannedDevice): ScannedDevice {
  const device: ScannedDevice = { id: native.id, rssi: native.rssi, serviceUuids: native.serviceUuids }
  if (native.name != null) device.name = native.name
  if (native.manufacturerData != null) device.manufacturerData = native.manufacturerData
  if (native.serviceData != null) {
    device.serviceData = Object.fromEntries(native.serviceData.map((entry) => [entry.uuid, entry.data]))
  }
  const optional = [
    'txPowerLevel',
    'isConnectable',
    'rawManufacturerData',
    'solicitedServiceUuids',
    'overflowServiceUuids',
    'rawScanRecord',
    'primaryPhy',
    'secondaryPhy',
    'timestamp',
  ] as const
  for (const key of optional) {
    if (native[key] != null) Object.assign(device, { [key]: native[key] })
  }
  return device
}

export function fromNativeStop(native: NativeScanStop): ScanStop {
  if (native.errorCode == null) return { reason: native.reason }
  return {
    reason: native.reason,
    error: new BleError(native.errorCode as BleErrorCode, native.errorMessage ?? 'Scanning failed', {
      platformCode: native.platformCode,
      operation: 'scan',
    }),
  }
}
