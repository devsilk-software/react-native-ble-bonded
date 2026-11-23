package com.margelo.nitro.blebonded.features.scanning.domain

import com.margelo.nitro.blebonded.shared.domain.BleError

/** What a running scan reports. */
sealed interface ScanEvent {
  data class Device(val device: ScannedDevice) : ScanEvent

  /** The scan has ended; nothing follows. [error] is set when it ended because something failed. */
  data class Stopped(val reason: ScanStopReason, val error: BleError? = null) : ScanEvent
}

/** Why a scan ended. */
enum class ScanStopReason(val publicName: String) {
  /** The app stopped it. */
  STOPPED("stopped"),

  /** A new scan replaced it. */
  REPLACED("replaced"),
  TIMEOUT("timeout"),

  /** Bluetooth was switched off, permission was revoked, or Android refused to scan. */
  FAILED("failed"),
}

/** Whether the phone can scan right now, and if not, why. */
enum class ScanReadiness {
  READY,
  BLUETOOTH_OFF,
  UNAUTHORIZED,
  UNSUPPORTED,
  ;

  /** The error a scan reports when it can't run; null when ready. */
  val error: BleError?
    get() =
      when (this) {
        READY -> null
        BLUETOOTH_OFF -> BleError(BleError.Code.BLUETOOTH_OFF, "Bluetooth is off", operation = "scan")
        UNAUTHORIZED -> BleError(BleError.Code.UNAUTHORIZED, "Bluetooth permission is not granted", operation = "scan")
        UNSUPPORTED -> BleError(BleError.Code.UNSUPPORTED, "This phone has no Bluetooth Low Energy", operation = "scan")
      }
}
