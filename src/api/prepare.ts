import { BluetoothState } from '../specs/BluetoothState.nitro'
import { Permissions } from '../specs/Permissions.nitro'

/** What scanning and connecting may ask for before they start. */
export interface PrepareOptions {
  /** Ask for Bluetooth permission first when it can still be asked. Default `true`. */
  autoRequestPermission?: boolean
  /** Ask the user to turn Bluetooth on first (Android; iOS can't). Default `true`. */
  autoEnableBluetooth?: boolean
}

/** Asks for permission and to turn Bluetooth on, unless switched off. Native code then reports what's still wrong. */
export async function prepare(options: PrepareOptions): Promise<void> {
  if (options.autoRequestPermission !== false && (await Permissions.getStatus()) === 'denied') {
    await Permissions.request()
  }
  if (options.autoEnableBluetooth !== false && (await BluetoothState.getState()) === 'poweredOff') {
    await BluetoothState.requestEnable()
  }
}
