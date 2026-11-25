package com.margelo.nitro.blebonded.features.gatt

import com.margelo.nitro.blebonded.features.gatt.application.useCases.getMaxWriteLength.GetMaxWriteLength
import com.margelo.nitro.blebonded.features.gatt.application.useCases.readRssi.ReadRssi
import com.margelo.nitro.blebonded.features.gatt.application.useCases.writeChunked.WriteChunked
import com.margelo.nitro.blebonded.mocks.FakeCharacteristicSource
import com.margelo.nitro.blebonded.mocks.readOnlyCharacteristic
import com.margelo.nitro.blebonded.mocks.testCharacteristic
import com.margelo.nitro.blebonded.shared.application.EventBus
import com.margelo.nitro.blebonded.shared.domain.BleError
import com.margelo.nitro.blebonded.shared.domain.CharacteristicProperty
import com.margelo.nitro.blebonded.shared.domain.DeviceId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

private val device = DeviceId("A")

private fun source() =
  FakeCharacteristicSource(
    mapOf(
      testCharacteristic to
        listOf(CharacteristicProperty.READ, CharacteristicProperty.WRITE, CharacteristicProperty.WRITE_WITHOUT_RESPONSE, CharacteristicProperty.NOTIFY),
      readOnlyCharacteristic to listOf(CharacteristicProperty.READ, CharacteristicProperty.NOTIFY),
    ),
  )

class ChunkedWriteTest : BehaviorSpec({
  given("a connected device") {
    then("reports the most one write carries") {
      GetMaxWriteLength(source())(device) shouldBe 20
    }

    then("cuts data to the maximum write length and sends the chunks in order") {
      val source = source()
      val data = ByteArray(45) { it.toByte() }
      WriteChunked(source, EventBus())(data, testCharacteristic, device, withResponse = false)
      source.writes.map { it.first.size } shouldBe listOf(20, 20, 5)
      source.writes.flatMap { it.first } shouldBe data.toList()
      source.writes.map { it.second }.toSet() shouldBe setOf(false)
    }

    then("stops at the chunk that failed and says which one and how many bytes were sent before it") {
      val source = source()
      source.failingPiece = 1 to BleError(BleError.Code.GATT_ERROR, "The device refused it", 14, attError = "unlikelyError")
      val error = shouldThrow<BleError> { WriteChunked(source, EventBus())(ByteArray(45), testCharacteristic, device, withResponse = true) }
      error.code shouldBe BleError.Code.GATT_ERROR
      error.chunkIndex shouldBe 1
      error.chunkCount shouldBe 3
      error.sentLength shouldBe 20
      error.platformCode shouldBe 14
      error.attError shouldBe "unlikelyError"
      error.message shouldContain "Chunk 1 of 3"
      error.message shouldContain "The device refused it"
      source.writes.size shouldBe 1
    }

    then("sends nothing for empty data") {
      val source = source()
      WriteChunked(source, EventBus())(ByteArray(0), testCharacteristic, device, withResponse = true)
      source.writes.size shouldBe 0
    }

    then("refuses a chunked write the characteristic doesn't allow, before sending anything") {
      val source = source()
      shouldThrow<BleError> { WriteChunked(source, EventBus())(byteArrayOf(1, 2, 3), readOnlyCharacteristic, device, withResponse = false) }
        .code shouldBe BleError.Code.UNSUPPORTED
      source.writes.size shouldBe 0
    }

    then("reads the signal strength") {
      val source = source()
      source.rssi = -42
      ReadRssi(source)(device) shouldBe -42
    }

    then("gives up reading the signal strength after its timeout") {
      val source = source()
      source.isAnswering = false
      shouldThrow<BleError> { ReadRssi(source)(device, timeoutMs = 50) }.code shouldBe BleError.Code.TIMEOUT
    }
  }
})
