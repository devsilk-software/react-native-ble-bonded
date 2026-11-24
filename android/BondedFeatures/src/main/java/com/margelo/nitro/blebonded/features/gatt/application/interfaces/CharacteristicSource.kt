package com.margelo.nitro.blebonded.features.gatt.application.interfaces

import com.margelo.nitro.blebonded.features.gatt.domain.CharacteristicAddress
import com.margelo.nitro.blebonded.shared.domain.CharacteristicProperty
import com.margelo.nitro.blebonded.shared.domain.DeviceId

/** The platform's characteristics, in Bonded's words. Implemented with Android's GATT client, and by a fake in tests. */
interface CharacteristicSource {
  /**
   * What the characteristic allows, from the services discovered at connect; null when the device has no such
   * characteristic. Throws `notConnected` while the device isn't connected.
   */
  suspend fun properties(address: CharacteristicAddress, id: DeviceId): List<CharacteristicProperty>?

  /** The longest value one write without response may carry. */
  suspend fun maxWriteLength(id: DeviceId): Int

  /** Reads the characteristic's value. Throws a `BleError`; cancelling stops waiting. */
  suspend fun read(address: CharacteristicAddress, id: DeviceId): ByteArray

  /** Writes [value]. With response, returns once the device confirmed; without, once the platform accepted it. */
  suspend fun write(value: ByteArray, address: CharacteristicAddress, id: DeviceId, withResponse: Boolean)
}
