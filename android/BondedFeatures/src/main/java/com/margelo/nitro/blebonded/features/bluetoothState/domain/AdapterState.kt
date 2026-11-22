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

/** The state's public name, as JavaScript sees it. */
val AdapterState.publicName: String
  get() =
    when (this) {
      AdapterState.UNKNOWN -> "unknown"
      AdapterState.RESETTING -> "resetting"
      AdapterState.UNSUPPORTED -> "unsupported"
      AdapterState.UNAUTHORIZED -> "unauthorized"
      AdapterState.POWERED_OFF -> "poweredOff"
      AdapterState.POWERED_ON -> "poweredOn"
    }
