package com.margelo.nitro.blebonded.infrastructure.bluetooth

/**
 * The system's "Turn on Bluetooth?" dialog. Showing it needs the app's current activity, which only the Nitro
 * module can reach (through React Native), so the module implements this and passes it to the composition root.
 */
interface BluetoothEnablePrompt {
  /** Shows the dialog and returns whether the user agreed; false at once when there is no activity. */
  suspend fun requestEnable(): Boolean
}
