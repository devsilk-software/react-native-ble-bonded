import { Android, Apple, type HybridObject } from 'nitro-core'

export * from '#nitro/BluetoothState'

/**
 * The phone's Bluetooth radio as Bonded sees it.
 *
 * - `unknown`: not known yet (iOS reports this until Core Bluetooth has started).
 * - `resetting`: the system is restarting Bluetooth.
 * - `unsupported`: the phone has no Bluetooth Low Energy.
 * - `unauthorized`: the user hasn't granted the app Bluetooth permission.
 * - `poweredOff`: Bluetooth is switched off.
 * - `poweredOn`: Bluetooth is on and ready.
 */
export type BluetoothAdapterState = 'unknown' | 'resetting' | 'unsupported' | 'unauthorized' | 'poweredOff' | 'poweredOn'

@Apple({
  language: 'swift',
  implementation: { className: 'HybridBluetoothState' },
})
@Android({
  language: 'kotlin',
  implementation: { className: 'HybridBluetoothState' },
})
export declare class BluetoothState extends HybridObject {
  /** The radio's current state. */
  static getState(): Promise<BluetoothAdapterState>
}
