package com.margelo.nitro.blebonded.mocks

import com.margelo.nitro.blebonded.features.gatt.application.interfaces.CharacteristicSource
import com.margelo.nitro.blebonded.features.gatt.domain.CharacteristicAddress
import com.margelo.nitro.blebonded.shared.domain.BleError
import com.margelo.nitro.blebonded.shared.domain.CharacteristicProperty
import com.margelo.nitro.blebonded.shared.domain.CharacteristicUuid
import com.margelo.nitro.blebonded.shared.domain.DeviceId
import com.margelo.nitro.blebonded.shared.domain.ServiceUuid
import kotlinx.coroutines.awaitCancellation
import java.util.concurrent.CopyOnWriteArrayList

/** A [CharacteristicSource] with the characteristics the test gives it, values it returns, and writes it records. */
class FakeCharacteristicSource(private val characteristics: Map<CharacteristicAddress, List<CharacteristicProperty>>) : CharacteristicSource {
  val values = HashMap<CharacteristicAddress, ByteArray>()
  val writes = CopyOnWriteArrayList<Pair<List<Byte>, Boolean>>()

  @Volatile var refusal: BleError? = null

  @Volatile var isAnswering = true

  @Volatile var maxLength = 20

  override suspend fun properties(address: CharacteristicAddress, id: DeviceId): List<CharacteristicProperty>? = characteristics[address]

  override suspend fun maxWriteLength(id: DeviceId): Int = maxLength

  override suspend fun read(address: CharacteristicAddress, id: DeviceId): ByteArray {
    answer()
    return values[address] ?: ByteArray(0)
  }

  override suspend fun write(value: ByteArray, address: CharacteristicAddress, id: DeviceId, withResponse: Boolean) {
    answer()
    writes.add(value.toList() to withResponse)
  }

  private suspend fun answer() {
    refusal?.let { throw it }
    if (!isAnswering) awaitCancellation()
  }
}

val testCharacteristic = CharacteristicAddress(ServiceUuid.parse("B0ED0001-0000-4000-8000-00000000B0ED")!!, CharacteristicUuid.parse("2A37")!!)
val readOnlyCharacteristic = CharacteristicAddress(ServiceUuid.parse("180D")!!, CharacteristicUuid.parse("2A38")!!)
