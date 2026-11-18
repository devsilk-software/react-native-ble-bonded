import { Linking } from 'react-native'
import { BluetoothState, type BluetoothAdapterState } from '../specs/BluetoothState.nitro'
import { Permissions, type BluetoothPermission } from '../specs/Permissions.nitro'

export type { BluetoothAdapterState, BluetoothPermission }

/** A listener registration. Call `remove()` to stop receiving updates. */
export interface Subscription {
  remove(): void
}

function subscription(remove: () => void): Subscription {
  let removed = false
  return {
    remove() {
      if (removed) return
      removed = true
      remove()
    },
  }
}

/**
 * Bonded's public API: one manager for every Bluetooth feature (decision 4). It combines the Nitro
 * HybridObjects and adds nothing but validation, error mapping and convenience (decision A5).
 */
export const ble = {
  /** The phone's Bluetooth radio state. Never shows the iOS permission prompt. */
  getState(): Promise<BluetoothAdapterState> {
    return BluetoothState.getState()
  },

  /** Calls `onChange` with the current state, then with every change. In components, prefer `useBluetoothState()`. */
  onStateChange(onChange: (state: BluetoothAdapterState) => void): Subscription {
    const id = BluetoothState.addStateListener(onChange)
    return subscription(() => BluetoothState.removeStateListener(id))
  },

  /** Whether the app may use Bluetooth, without asking the user. */
  getPermissionStatus(): Promise<BluetoothPermission> {
    return Permissions.getStatus()
  },

  /**
   * Asks for Bluetooth permission where the platform allows it, and resolves with the resulting status. On iOS
   * this is when the system prompt appears; before it, Bonded never shows it.
   */
  requestPermissions(): Promise<BluetoothPermission> {
    return Permissions.request()
  },

  /**
   * Calls `onChange` with the current permission status, then with every change, including changes made in the
   * system settings. In components, prefer `useBluetoothPermission()`.
   */
  onPermissionChange(onChange: (status: BluetoothPermission) => void): Subscription {
    const id = Permissions.addListener(onChange)
    return subscription(() => Permissions.removeListener(id))
  },

  /** Opens the app's system settings, where a `blocked` permission can be granted. */
  openSettings(): Promise<void> {
    return Linking.openSettings()
  },
}
