package com.margelo.nitro.blebonded.features.gatt.domain

import com.margelo.nitro.blebonded.shared.domain.BleError
import com.margelo.nitro.blebonded.shared.domain.CharacteristicProperty
import com.margelo.nitro.blebonded.shared.domain.CharacteristicUuid
import com.margelo.nitro.blebonded.shared.domain.DeviceId
import com.margelo.nitro.blebonded.shared.domain.ServiceUuid

/** A characteristic on a device: the service it belongs to and its own UUID. */
data class CharacteristicAddress(val service: ServiceUuid, val characteristic: CharacteristicUuid)

/** What the app asks of a characteristic. */
enum class CharacteristicOperation(val publicName: String, val properties: List<CharacteristicProperty>) {
  READ("read", listOf(CharacteristicProperty.READ)),
  WRITE("write", listOf(CharacteristicProperty.WRITE)),
  WRITE_WITHOUT_RESPONSE("write without response", listOf(CharacteristicProperty.WRITE_WITHOUT_RESPONSE)),
  SUBSCRIBE("subscribe", listOf(CharacteristicProperty.NOTIFY, CharacteristicProperty.INDICATE)),
  READ_DESCRIPTOR("descriptor read", emptyList()),
  WRITE_DESCRIPTOR("descriptor write", emptyList()),
}

/** The longest value a write with response may carry: Bluetooth's limit for a characteristic's value. */
const val MAX_ATTRIBUTE_LENGTH = 512

/** The checks before anything is sent. Each failure names the problem, the values involved and what to do. */
object OperationCheck {
  /** [properties] is null when the device has no such characteristic. */
  fun failure(
    operation: CharacteristicOperation,
    address: CharacteristicAddress,
    id: DeviceId,
    properties: List<CharacteristicProperty>?,
    length: Int = 0,
    maxWriteLength: Int = MAX_ATTRIBUTE_LENGTH,
  ): BleError? {
    val what = "characteristic ${address.characteristic} in service ${address.service}"
    if (properties == null) {
      return BleError(
        BleError.Code.CHARACTERISTIC_NOT_FOUND,
        "The device has no $what. device.getServices() lists the services and characteristics it has.",
        deviceId = id,
        operation = operation.publicName,
      )
    }
    if (operation.properties.none { it in properties }) {
      val allowed = if (properties.isEmpty()) "nothing" else properties.joinToString(", ") { it.publicName }
      return BleError(
        BleError.Code.UNSUPPORTED,
        "The $what doesn't allow ${operation.publicName}; it allows $allowed.",
        deviceId = id,
        operation = operation.publicName,
      )
    }
    return when {
      operation == CharacteristicOperation.WRITE && length > MAX_ATTRIBUTE_LENGTH ->
        BleError(
          BleError.Code.VALUE_TOO_LONG,
          "Value is $length bytes; a write with response allows at most $MAX_ATTRIBUTE_LENGTH, Bluetooth's limit for a characteristic. Split it into writes your firmware puts back together.",
          deviceId = id,
          operation = operation.publicName,
          length = length,
          maxLength = MAX_ATTRIBUTE_LENGTH,
        )
      operation == CharacteristicOperation.WRITE_WITHOUT_RESPONSE && length > maxWriteLength ->
        BleError(
          BleError.Code.VALUE_TOO_LONG,
          "Value is $length bytes; a write without response to this device allows at most $maxWriteLength. Split it into pieces your firmware puts back together, or write with response (up to $MAX_ATTRIBUTE_LENGTH bytes).",
          deviceId = id,
          operation = operation.publicName,
          length = length,
          maxLength = maxWriteLength,
        )
      else -> null
    }
  }
}
