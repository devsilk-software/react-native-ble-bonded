import { Android, Apple, type HybridObject } from 'nitro-core'

export * from '#nitro/Bonding'

/** Whether Android has a bond with the device: paired and keys stored. */
export type BondState = 'bonded' | 'bonding' | 'none'

@Apple({
  language: 'swift',
  implementation: { className: 'HybridBonding' },
})
@Android({
  language: 'kotlin',
  implementation: { className: 'HybridBonding' },
})
export declare class Bonding extends HybridObject {
  /**
   * Pairs with the device and resolves once encrypted reads of the trigger characteristic work; at once when already
   * paired. Rejects with a `BleError` message: `pairingRequired` when pairing is declined or fails.
   */
  static createBond(id: string, service: string, characteristic: string): Promise<void>

  /** Android's bond state for the device. iOS rejects with `unsupported`. */
  static getBondState(id: string): Promise<BondState>

  /** Removes Android's bond with the device, best-effort. iOS rejects with `unsupported`. */
  static removeBond(id: string): Promise<void>

  /**
   * Calls `onChange` with Android's current bond state for the device, then every change; resolves with an ID for
   * `removeBondStateListener`. iOS rejects with `unsupported`.
   */
  static addBondStateListener(id: string, onChange: (state: BondState) => void): Promise<number>

  /** Stops the listener `addBondStateListener` returned this ID for. */
  static removeBondStateListener(listener: number): void
}
