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
