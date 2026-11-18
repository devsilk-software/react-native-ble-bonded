package com.margelo.nitro.blebonded.features.bluetoothState.domain

/** The phone's Bluetooth radio, in Bonded's words. */
enum class AdapterState {
  /** Not known yet: the system hasn't reported a state. */
  UNKNOWN,

  /** The system is restarting Bluetooth. */
  RESETTING,

  /** The phone has no Bluetooth Low Energy. */
  UNSUPPORTED,

  /** The user hasn't granted the app Bluetooth permission. */
  UNAUTHORIZED,
  POWERED_OFF,
  POWERED_ON,
}
