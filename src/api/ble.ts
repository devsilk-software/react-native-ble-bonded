import { BluetoothState, type BluetoothAdapterState } from '../specs/BluetoothState.nitro'

/**
 * Bonded's public API: one manager for every Bluetooth feature (decision 4). It combines the Nitro
 * HybridObjects and adds nothing but validation, error mapping and convenience (decision A5).
 */
export const ble = {
  /** The phone's Bluetooth radio state. Waits for the system's first report if there hasn't been one yet. */
  getState(): Promise<BluetoothAdapterState> {
    return BluetoothState.getState()
  },
}
