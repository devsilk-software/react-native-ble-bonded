package com.margelo.nitro.blebonded.features.gatt.domain

import com.margelo.nitro.blebonded.shared.domain.BleError
import com.margelo.nitro.blebonded.shared.domain.DeviceId

/**
 * The Bluetooth (ATT) errors a device can answer an operation with, by name, so an app reads what went wrong without
 * a table of numbers.
 */
object DeviceRefusal {
  private val names =
    mapOf(
      0x01 to ("invalidHandle" to "the characteristic's handle isn't valid"),
      0x02 to ("readNotPermitted" to "the characteristic can't be read"),
      0x03 to ("writeNotPermitted" to "the characteristic can't be written"),
      0x04 to ("invalidPdu" to "the request was malformed"),
      0x05 to ("insufficientAuthentication" to "the characteristic needs a paired device"),
      0x06 to ("requestNotSupported" to "the device doesn't support this request"),
      0x07 to ("invalidOffset" to "the offset is past the value's end"),
      0x08 to ("insufficientAuthorization" to "the device didn't authorize this request"),
      0x09 to ("prepareQueueFull" to "too many long writes are queued on the device"),
      0x0A to ("attributeNotFound" to "the device has no such attribute"),
      0x0B to ("attributeNotLong" to "the value can't be read or written in parts"),
      0x0C to ("insufficientEncryptionKeySize" to "the encryption key is too short"),
      0x0D to ("invalidAttributeValueLength" to "the device refused the value's length"),
      0x0E to ("unlikelyError" to "the device failed in an unlikely way"),
      0x0F to ("insufficientEncryption" to "the characteristic needs a paired, encrypted connection"),
      0x10 to ("unsupportedGroupType" to "the device doesn't support this grouping"),
      0x11 to ("insufficientResources" to "the device ran out of resources"),
    )

  /** Codes that mean the device wants a paired, encrypted connection first. */
  private val pairing = setOf(0x05, 0x0C, 0x0F)

  /** Whether [code] is a Bluetooth error with a name, as opposed to a platform's own status. */
  fun isNamed(code: Int): Boolean = code in names

  /** The [BleError] for an ATT error code the device answered [operation] with. */
  fun error(code: Int, operation: CharacteristicOperation, address: CharacteristicAddress, id: DeviceId): BleError {
    val what = "the ${operation.publicName} of characteristic ${address.characteristic}"
    if (code in 0x80..0x9F) {
      return BleError(
        BleError.Code.GATT_ERROR,
        "The device refused $what with its own error 0x${code.toString(16)}; look it up in the firmware's error list.",
        code,
        id,
        operation.publicName,
        attError = "applicationError",
      )
    }
    val (name, description) = names[code] ?: ("unknown" to "the device refused it with an unknown error $code")
    return BleError(
      if (code in pairing) BleError.Code.PAIRING_REQUIRED else BleError.Code.GATT_ERROR,
      "The device refused $what: $description.",
      code,
      id,
      operation.publicName,
      attError = name,
    )
  }
}
