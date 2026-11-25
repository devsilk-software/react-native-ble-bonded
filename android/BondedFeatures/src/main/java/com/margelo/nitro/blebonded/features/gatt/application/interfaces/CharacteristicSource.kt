package com.margelo.nitro.blebonded.features.gatt.application.interfaces

import com.margelo.nitro.blebonded.features.gatt.domain.CharacteristicAddress
import com.margelo.nitro.blebonded.shared.domain.BleError
import com.margelo.nitro.blebonded.shared.domain.CharacteristicProperty
import com.margelo.nitro.blebonded.shared.domain.DeviceId
import kotlinx.coroutines.flow.Flow

/**
 * The platform's operations on a connected device, in Bonded's words: its characteristics and its signal strength.
 * Implemented with Android's GATT client, and by a fake in tests.
 */
interface CharacteristicSource {
  /**
   * What the characteristic allows, from the services discovered at connect; null when the device has no such
   * characteristic. Throws `notConnected` while the device isn't connected.
   */
  suspend fun properties(address: CharacteristicAddress, id: DeviceId): List<CharacteristicProperty>?

  /** The longest value one write may carry without being split: the connection's MTU minus 3. Throws `notConnected`. */
  suspend fun maxWriteLength(id: DeviceId): Int

  /** Reads the characteristic's value. Throws a `BleError`; cancelling stops waiting. */
  suspend fun read(address: CharacteristicAddress, id: DeviceId): ByteArray

  /** Writes [value]. With response, returns once the device confirmed; without, once the platform accepted it. */
  suspend fun write(value: ByteArray, address: CharacteristicAddress, id: DeviceId, withResponse: Boolean)

  /**
   * Writes [pieces] in order as one operation, so nothing else on the device runs between them. Each piece that takes
   * longer than [timeoutMs] fails. Throws [PieceFailure] naming the piece that failed; the ones after it aren't sent.
   */
  suspend fun write(pieces: List<ByteArray>, address: CharacteristicAddress, id: DeviceId, withResponse: Boolean, timeoutMs: Long)

  /** The connected device's signal strength in dBm. Throws a `BleError`; cancelling stops waiting. */
  suspend fun readRssi(id: DeviceId): Int

  /** Switches notifications (or indications) on or off and returns once the device confirmed. */
  suspend fun setNotifications(isOn: Boolean, indicate: Boolean, address: CharacteristicAddress, id: DeviceId)

  /** Every notified value. */
  fun notifications(): Flow<CharacteristicNotification>
}

/** A piece of a chunked write failed: which one, counting from 0, and why. */
class PieceFailure(val index: Int, val error: BleError) : Exception(error.message)

/** A value a device notified. */
class CharacteristicNotification(val id: DeviceId, val address: CharacteristicAddress, val value: ByteArray)
