import { Android, Apple, type HybridObject } from 'nitro-core'

export * from '#nitro/BluetoothState'

/**
 * The phone's Bluetooth radio as Bonded sees it.
 *
 * - `unknown`: not known yet (iOS reports this until Core Bluetooth has started).
 * - `resetting`: the system is restarting Bluetooth.
 * - `unsupported`: the phone has no Bluetooth Low Energy.
 * - `unauthorized`: the app may not use Bluetooth yet (permission not granted, or not requested on iOS).
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
  /** The radio's current state. Never shows the iOS permission prompt. */
  static getState(): Promise<BluetoothAdapterState>

  /**
   * Calls `onChange` with the current state, then with every change; repeats are suppressed.
   * Returns an ID for `removeStateListener`.
   */
  static addStateListener(onChange: (state: BluetoothAdapterState) => void): number

  /**
   * Asks the user to turn Bluetooth on and resolves with the resulting state. Android shows the system dialog;
   * iOS has no public way to, so it resolves with the current state without showing anything.
   */
  static requestEnable(): Promise<BluetoothAdapterState>

  /** Stops the listener `addStateListener` returned this ID for. */
  static removeStateListener(id: number): void
}
