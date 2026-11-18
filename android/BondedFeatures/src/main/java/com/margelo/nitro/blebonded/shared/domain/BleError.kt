package com.margelo.nitro.blebonded.shared.domain

/**
 * Every failure Bonded reports, with a code that means the same on iOS and Android and the platform's own code
 * for whoever is debugging (decision 5).
 */
class BleError(
  val code: Code,
  override val message: String,
  /** The GATT status or other Android code, when Android gave one. */
  val platformCode: Int? = null,
  val deviceId: DeviceId? = null,
  /** The operation that failed, such as `connect` or `read`. */
  val operation: String? = null,
) : Exception(message) {
  enum class Code {
    BLUETOOTH_OFF,
    UNAUTHORIZED,
    DEVICE_NOT_FOUND,
    NOT_CONNECTED,
    CONNECTION_FAILED,
    DISCONNECTED,
    TIMEOUT,
    PAIRING_REQUIRED,
    GATT_ERROR,
    CANCELLED,
    UNSUPPORTED,
    UNKNOWN,
  }
}
