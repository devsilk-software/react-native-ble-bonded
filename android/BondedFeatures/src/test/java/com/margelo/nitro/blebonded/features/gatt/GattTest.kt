package com.margelo.nitro.blebonded.features.gatt

import com.margelo.nitro.blebonded.features.gatt.application.useCases.readCharacteristic.ReadCharacteristic
import com.margelo.nitro.blebonded.features.gatt.application.useCases.writeCharacteristic.WriteCharacteristic
import com.margelo.nitro.blebonded.features.gatt.domain.CharacteristicAddress
import com.margelo.nitro.blebonded.features.gatt.domain.CharacteristicOperation
import com.margelo.nitro.blebonded.features.gatt.domain.DeviceRefusal
import com.margelo.nitro.blebonded.mocks.FakeCharacteristicSource
import com.margelo.nitro.blebonded.mocks.readOnlyCharacteristic
import com.margelo.nitro.blebonded.mocks.testCharacteristic
import com.margelo.nitro.blebonded.shared.domain.BleError
import com.margelo.nitro.blebonded.shared.domain.CharacteristicProperty
import com.margelo.nitro.blebonded.shared.domain.CharacteristicUuid
import com.margelo.nitro.blebonded.shared.domain.DeviceId
import com.margelo.nitro.blebonded.shared.domain.DomainEvent
import com.margelo.nitro.blebonded.shared.domain.EventBus
import com.margelo.nitro.blebonded.shared.domain.ServiceUuid
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout

private val device = DeviceId("A")

private fun source() =
  FakeCharacteristicSource(
    mapOf(
      testCharacteristic to
        listOf(CharacteristicProperty.READ, CharacteristicProperty.WRITE, CharacteristicProperty.WRITE_WITHOUT_RESPONSE, CharacteristicProperty.NOTIFY),
      readOnlyCharacteristic to listOf(CharacteristicProperty.READ, CharacteristicProperty.NOTIFY),
    ),
  )

class GattTest : BehaviorSpec({
  given("a connected device") {
    then("reads a characteristic's value") {
      val source = source()
      source.values[testCharacteristic] = byteArrayOf(1, 2, 3)
      ReadCharacteristic(source, EventBus())(testCharacteristic, device).toList() shouldBe listOf<Byte>(1, 2, 3)
    }

    then("writes a value with and without response") {
      val source = source()
      val write = WriteCharacteristic(source, EventBus())
      write(byteArrayOf(1), testCharacteristic, device, withResponse = true)
      write(byteArrayOf(2), testCharacteristic, device, withResponse = false)
      source.writes.map { it.first } shouldBe listOf(listOf<Byte>(1), listOf<Byte>(2))
      source.writes.map { it.second } shouldBe listOf(true, false)
    }

    then("refuses an operation on a characteristic the device doesn't have") {
      val missing = CharacteristicAddress(ServiceUuid.parse("180F")!!, CharacteristicUuid.parse("2A19")!!)
      val error = shouldThrow<BleError> { ReadCharacteristic(source(), EventBus())(missing, device) }
      error.code shouldBe BleError.Code.CHARACTERISTIC_NOT_FOUND
      error.message shouldContain "2a19"
      error.message shouldContain "getServices()"
    }

    then("refuses an operation the characteristic doesn't allow") {
      val source = source()
      val error = shouldThrow<BleError> { WriteCharacteristic(source, EventBus())(byteArrayOf(1), readOnlyCharacteristic, device, withResponse = true) }
      error.code shouldBe BleError.Code.UNSUPPORTED
      error.message shouldContain "it allows read, notify"
      source.writes shouldBe emptyList()
    }

    then("refuses a value longer than the write allows") {
      val source = source()
      val write = WriteCharacteristic(source, EventBus())
      val tooLong = shouldThrow<BleError> { write(ByteArray(21), testCharacteristic, device, withResponse = false) }
      tooLong.code shouldBe BleError.Code.VALUE_TOO_LONG
      tooLong.length shouldBe 21
      tooLong.maxLength shouldBe 20
      tooLong.message shouldContain "Value is 21 bytes"
      tooLong.message shouldContain "at most 20"
      shouldThrow<BleError> { write(ByteArray(513), testCharacteristic, device, withResponse = true) }.maxLength shouldBe 512
      write(ByteArray(512), testCharacteristic, device, withResponse = true)
      source.writes.map { it.first.size } shouldBe listOf(512)
    }

    then("gives up on an operation after its timeout") {
      val source = source()
      source.isAnswering = false
      shouldThrow<BleError> { ReadCharacteristic(source, EventBus())(testCharacteristic, device, timeoutMs = 30) }.code shouldBe BleError.Code.TIMEOUT
    }

    then("publishes each operation for diagnostics") {
      val bus = EventBus()
      val published = CoroutineScope(Dispatchers.Default).async(start = CoroutineStart.UNDISPATCHED) { bus.events().take(3).toList() }
      val source = source()
      ReadCharacteristic(source, bus)(testCharacteristic, device)
      runCatching { ReadCharacteristic(source, bus)(readOnlyCharacteristic, device) }
      runCatching { WriteCharacteristic(source, bus)(byteArrayOf(1), readOnlyCharacteristic, device, withResponse = true) }
      val events = withTimeout(1_000) { published.await() }
      events.all { it.kind == DomainEvent.Kind.GATT_OPERATION && it.deviceId == device } shouldBe true
      events[0].detail!! shouldStartWith "read 00002a37"
      events[2].errorCode shouldBe BleError.Code.UNSUPPORTED
    }
  }

  given("errors the device answers with") {
    then("names the error the device answered with") {
      val length = DeviceRefusal.error(0x0D, CharacteristicOperation.WRITE, testCharacteristic, device)
      length.code shouldBe BleError.Code.GATT_ERROR
      length.attError shouldBe "invalidAttributeValueLength"
      length.platformCode shouldBe 0x0D
      length.message shouldContain "refused the value's length"
      val firmware = DeviceRefusal.error(0x81, CharacteristicOperation.READ, testCharacteristic, device)
      firmware.attError shouldBe "applicationError"
      firmware.message shouldContain "0x81"
    }

    then("asks for pairing when the characteristic needs it") {
      listOf(0x05, 0x0C, 0x0F).forEach {
        DeviceRefusal.error(it, CharacteristicOperation.READ, testCharacteristic, device).code shouldBe BleError.Code.PAIRING_REQUIRED
      }
    }
  }
})
