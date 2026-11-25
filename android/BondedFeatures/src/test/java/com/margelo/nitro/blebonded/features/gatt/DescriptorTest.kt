package com.margelo.nitro.blebonded.features.gatt

import com.margelo.nitro.blebonded.features.gatt.application.useCases.readDescriptor.ReadDescriptor
import com.margelo.nitro.blebonded.features.gatt.application.useCases.writeDescriptor.WriteDescriptor
import com.margelo.nitro.blebonded.mocks.FakeCharacteristicSource
import com.margelo.nitro.blebonded.mocks.readOnlyCharacteristic
import com.margelo.nitro.blebonded.mocks.testCharacteristic
import com.margelo.nitro.blebonded.shared.application.EventBus
import com.margelo.nitro.blebonded.shared.domain.BleError
import com.margelo.nitro.blebonded.shared.domain.CharacteristicProperty
import com.margelo.nitro.blebonded.shared.domain.DescriptorUuid
import com.margelo.nitro.blebonded.shared.domain.DeviceId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

private val device = DeviceId("A")
private val userDescription = DescriptorUuid.parse("2901")!!
private val custom = DescriptorUuid.parse("B0ED0005-0000-4000-8000-00000000B0ED")!!

private fun source() =
  FakeCharacteristicSource(mapOf(testCharacteristic to listOf(CharacteristicProperty.READ, CharacteristicProperty.WRITE, CharacteristicProperty.NOTIFY))).apply {
    setDescriptor(userDescription, "Counter".toByteArray(), testCharacteristic)
    setDescriptor(DescriptorUuid.CLIENT_CONFIGURATION, byteArrayOf(0, 0), testCharacteristic)
    setDescriptor(custom, ByteArray(0), testCharacteristic)
  }

class DescriptorTest : BehaviorSpec({
  given("a connected device") {
    then("reads a descriptor's value") {
      String(ReadDescriptor(source(), EventBus())(userDescription, testCharacteristic, device)) shouldBe "Counter"
    }

    then("writes a descriptor's value") {
      val source = source()
      WriteDescriptor(source, EventBus())(byteArrayOf(1, 2), custom, testCharacteristic, device)
      source.descriptorWrites.toList() shouldBe listOf(custom to listOf<Byte>(1, 2))
    }

    then("refuses a descriptor the characteristic doesn't have, naming the ones it has") {
      val read = ReadDescriptor(source(), EventBus())
      val missing = shouldThrow<BleError> { read(DescriptorUuid.parse("2904")!!, testCharacteristic, device) }
      missing.code shouldBe BleError.Code.DESCRIPTOR_NOT_FOUND
      missing.message shouldContain "00002901"
      missing.message shouldContain "getServices({ descriptors: true })"
      shouldThrow<BleError> { read(userDescription, readOnlyCharacteristic, device) }.code shouldBe BleError.Code.CHARACTERISTIC_NOT_FOUND
    }

    then("refuses to write the notification descriptor, pointing to subscribe") {
      val source = source()
      val refused = shouldThrow<BleError> { WriteDescriptor(source, EventBus())(byteArrayOf(1, 0), DescriptorUuid.CLIENT_CONFIGURATION, testCharacteristic, device) }
      refused.code shouldBe BleError.Code.UNSUPPORTED
      refused.message shouldContain "device.subscribe()"
      source.descriptorWrites.size shouldBe 0
      ReadDescriptor(source, EventBus())(DescriptorUuid.CLIENT_CONFIGURATION, testCharacteristic, device).toList() shouldBe listOf<Byte>(0, 0)
    }

    then("gives up on a descriptor operation after its timeout") {
      val source = source()
      source.isAnswering = false
      shouldThrow<BleError> { ReadDescriptor(source, EventBus())(userDescription, testCharacteristic, device, timeoutMs = 50) }.code shouldBe BleError.Code.TIMEOUT
    }
  }
})
