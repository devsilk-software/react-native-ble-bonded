package com.margelo.nitro.blebonded

import com.margelo.nitro.blebonded.features.gatt.domain.CharacteristicAddress
import com.margelo.nitro.blebonded.shared.domain.BleError
import com.margelo.nitro.blebonded.shared.domain.CharacteristicUuid
import com.margelo.nitro.blebonded.shared.domain.DeviceId
import com.margelo.nitro.blebonded.shared.domain.ServiceUuid
import com.margelo.nitro.core.ArrayBuffer
import com.margelo.nitro.core.Promise

/**
 * The `Gatt` HybridObject: calls the `gatt` feature's use cases and maps between them and the generated Nitro types.
 * Nothing else lives here (decision A5).
 */
class HybridGatt : HybridGattSpec() {
  companion object : HybridGattSpec.Companion {
    override fun read(id: String, service: String, characteristic: String, timeoutMs: Double): Promise<ArrayBuffer> {
      val address = address(service, characteristic)
      return Promise.async {
        try {
          ArrayBuffer.copy(root().readCharacteristic(address, DeviceId(id), timeoutMs.toLong()))
        } catch (error: BleError) {
          throw Exception(error.jsMessage)
        }
      }
    }

    override fun write(
      id: String,
      service: String,
      characteristic: String,
      value: ArrayBuffer,
      withResponse: Boolean,
      timeoutMs: Double,
    ): Promise<Unit> {
      val address = address(service, characteristic)
      val buffer = value.getBuffer(copyIfNeeded = true)
      val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
      return Promise.async {
        try {
          root().writeCharacteristic(bytes, address, DeviceId(id), withResponse, timeoutMs.toLong())
        } catch (error: BleError) {
          throw Exception(error.jsMessage)
        }
      }
    }

    /** From the UUIDs JavaScript passed, short or full; a malformed one rejects with a message naming it. */
    private fun address(service: String, characteristic: String): CharacteristicAddress {
      val serviceUuid = ServiceUuid.parse(service) ?: throw Exception(BleError(BleError.Code.UNKNOWN, "Not a service UUID: $service").jsMessage)
      val characteristicUuid =
        CharacteristicUuid.parse(characteristic) ?: throw Exception(BleError(BleError.Code.UNKNOWN, "Not a characteristic UUID: $characteristic").jsMessage)
      return CharacteristicAddress(serviceUuid, characteristicUuid)
    }
  }
}
