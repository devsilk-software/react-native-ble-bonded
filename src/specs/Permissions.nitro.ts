import { Android, Apple, type HybridObject } from 'nitro-core'

export * from '#nitro/Permissions'

/**
 * Whether the app may use Bluetooth, with the statuses of react-native-permissions (without `limited`, which
 * Bluetooth doesn't have).
 *
 * - `unavailable`: the phone has no Bluetooth Low Energy.
 * - `denied`: not granted, but the app can ask (including when it never has).
 * - `granted`: the app may use Bluetooth.
 * - `blocked`: not granted, and only the system settings can change that.
 */
export type BluetoothPermission = 'unavailable' | 'denied' | 'granted' | 'blocked'

@Apple({
  language: 'swift',
  implementation: { className: 'HybridPermissions' },
})
@Android({
  language: 'kotlin',
  implementation: { className: 'HybridPermissions' },
})
export declare class Permissions extends HybridObject {
  /** The current status, without asking the user. */
  static getStatus(): Promise<BluetoothPermission>

  /** Asks the user where the platform allows it, and resolves with the resulting status. */
  static request(): Promise<BluetoothPermission>

  /**
   * Calls `onChange` with the current status, then with every change, including changes made in the system
   * settings while the app was in the background; repeats are suppressed. Returns an ID for `removeListener`.
   */
  static addListener(onChange: (status: BluetoothPermission) => void): number

  /** Stops the listener `addListener` returned this ID for. */
  static removeListener(id: number): void
}
