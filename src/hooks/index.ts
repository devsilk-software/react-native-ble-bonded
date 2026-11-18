import { useSyncExternalStore } from 'react'
import { ble, type BluetoothAdapterState, type BluetoothPermission } from '../api/ble'
import { createExternalStore } from './externalStore'

const bluetoothState = createExternalStore<BluetoothAdapterState, 'unknown'>((onChange) => ble.onStateChange(onChange), 'unknown')
const bluetoothPermission = createExternalStore<BluetoothPermission, undefined>((onChange) => ble.onPermissionChange(onChange), undefined)

/**
 * The phone's Bluetooth radio state, re-rendering on every change. `unknown` until the first report. Never shows
 * the iOS permission prompt.
 */
export function useBluetoothState(): BluetoothAdapterState {
  return useSyncExternalStore(bluetoothState.subscribe, bluetoothState.getSnapshot)
}

/**
 * Whether the app may use Bluetooth, re-rendering on every change, including changes made in the system
 * settings. `undefined` until the first report.
 */
export function useBluetoothPermission(): BluetoothPermission | undefined {
  return useSyncExternalStore(bluetoothPermission.subscribe, bluetoothPermission.getSnapshot)
}

export type { BluetoothAdapterState, BluetoothPermission }
