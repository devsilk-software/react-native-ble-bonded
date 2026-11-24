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
  /** The Bluetooth error the device answered with, by name, such as `invalidAttributeValueLength`. */
  val attError: String? = null,
  /** For [Code.VALUE_TOO_LONG]: the value's length and the most the operation allows, in bytes. */
  val length: Int? = null,
  val maxLength: Int? = null,
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

    /** A value longer than the operation allows; `length` and `maxLength` say by how much. */
    VALUE_TOO_LONG,

    /** The device has no such service or characteristic. */
    CHARACTERISTIC_NOT_FOUND,

    /** The characteristic already has a subscription; there's one at a time. */
    ALREADY_SUBSCRIBED,
    ;

    /** The code's public name, as JavaScript and iOS write it (`bluetoothOff`). */
    val publicName: String
      get() = name.lowercase().split('_').mapIndexed { index, part -> if (index == 0) part else part.replaceFirstChar(Char::uppercase) }.joinToString("")
  }
}
