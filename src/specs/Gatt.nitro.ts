import { Android, Apple, type HybridObject } from 'nitro-core'

export * from '#nitro/Gatt'

@Apple({
  language: 'swift',
  implementation: { className: 'HybridGatt' },
})
@Android({
  language: 'kotlin',
  implementation: { className: 'HybridGatt' },
})
export declare class Gatt extends HybridObject {
  /**
   * Reads a characteristic's value. Rejects with a `BleError` message: `characteristicNotFound`, `unsupported`,
   * `notConnected`, `timeout`, `pairingRequired` or `gattError`.
   */
  static read(id: string, service: string, characteristic: string, timeoutMs: number): Promise<ArrayBuffer>

  /**
   * Subscribes to a characteristic's notifications and resolves with the subscription's ID once the device switched
   * them on. `onValues` gets the values in order, in batches about every 16 ms; `onError` a `BleError` message when
   * switching them back on after a reconnect failed; `onEnd` once when Bonded removed the subscription itself, because
   * the app forgot the device. Rejects with a `BleError` message, also `alreadySubscribed`.
   */
  static subscribe(
    id: string,
    service: string,
    characteristic: string,
    onValues: (values: ArrayBuffer[]) => void,
    onError: (message: string) => void,
    onEnd: () => void
  ): Promise<number>

  /** Removes the subscription and switches the device's notifications off when it's connected. */
  static unsubscribe(subscription: number): Promise<void>

  /**
   * Writes a value; without response, resolves once the platform accepted it. Rejects with a `BleError` message, also
   * `valueTooLong`.
   */
  static write(
    id: string,
    service: string,
    characteristic: string,
    value: ArrayBuffer,
    withResponse: boolean,
    timeoutMs: number
  ): Promise<void>
}
