package com.margelo.nitro.blebonded.features.gatt.domain

import com.margelo.nitro.blebonded.shared.domain.BleError
import com.margelo.nitro.blebonded.shared.domain.DescriptorUuid
import com.margelo.nitro.blebonded.shared.domain.DeviceId

/** The checks before a descriptor is read or written. Each failure names the problem and what to do. */
object DescriptorCheck {
  /** [descriptors] is null when the device has no such characteristic. */
  fun failure(
    operation: CharacteristicOperation,
    descriptor: DescriptorUuid,
    address: CharacteristicAddress,
    id: DeviceId,
    descriptors: List<DescriptorUuid>?,
    length: Int = 0,
  ): BleError? {
    if (descriptors == null) {
      return BleError(
        BleError.Code.CHARACTERISTIC_NOT_FOUND,
        "The device has no characteristic ${address.characteristic} in service ${address.service}. device.getServices() lists the services and characteristics it has.",
        deviceId = id,
        operation = operation.publicName,
      )
    }
    if (descriptor !in descriptors) {
      val present = if (descriptors.isEmpty()) "none" else descriptors.joinToString(", ") { it.rawValue }
      return BleError(
        BleError.Code.DESCRIPTOR_NOT_FOUND,
        "Characteristic ${address.characteristic} has no descriptor $descriptor; it has $present. device.getServices({ descriptors: true }) lists them.",
        deviceId = id,
        operation = operation.publicName,
      )
    }
    if (operation == CharacteristicOperation.WRITE_DESCRIPTOR && descriptor == DescriptorUuid.CLIENT_CONFIGURATION) {
      return BleError(
        BleError.Code.UNSUPPORTED,
        "The notification descriptor (2902) is Bonded's to write: device.subscribe() switches notifications on, and its remove() off.",
        deviceId = id,
        operation = operation.publicName,
      )
    }
    if (operation == CharacteristicOperation.WRITE_DESCRIPTOR && length > MAX_ATTRIBUTE_LENGTH) {
      return BleError(
        BleError.Code.VALUE_TOO_LONG,
        "Value is $length bytes; a descriptor allows at most $MAX_ATTRIBUTE_LENGTH, Bluetooth's limit for a value.",
        deviceId = id,
        operation = operation.publicName,
        length = length,
        maxLength = MAX_ATTRIBUTE_LENGTH,
      )
    }
    return null
  }
}
