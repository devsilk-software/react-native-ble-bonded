package com.margelo.nitro.blebonded.features.gatt

import com.margelo.nitro.blebonded.features.gatt.application.services.SubscriptionRegistry
import com.margelo.nitro.blebonded.features.gatt.domain.SubscriptionEvent
import com.margelo.nitro.blebonded.mocks.FakeCharacteristicSource
import com.margelo.nitro.blebonded.mocks.eventually
import com.margelo.nitro.blebonded.mocks.indicateOnlyCharacteristic
import com.margelo.nitro.blebonded.mocks.readOnlyCharacteristic
import com.margelo.nitro.blebonded.mocks.testCharacteristic
import com.margelo.nitro.blebonded.shared.domain.BleError
import com.margelo.nitro.blebonded.shared.domain.CharacteristicProperty
import com.margelo.nitro.blebonded.shared.domain.DeviceId
import com.margelo.nitro.blebonded.shared.domain.DomainEvent
import com.margelo.nitro.blebonded.shared.domain.EventBus
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList

private val device = DeviceId("A")

private fun source() =
  FakeCharacteristicSource(
    mapOf(
      testCharacteristic to listOf(CharacteristicProperty.READ, CharacteristicProperty.NOTIFY, CharacteristicProperty.INDICATE),
      readOnlyCharacteristic to listOf(CharacteristicProperty.READ),
      indicateOnlyCharacteristic to listOf(CharacteristicProperty.INDICATE),
    ),
  )

private fun registry(source: FakeCharacteristicSource, bus: EventBus = EventBus()) =
  SubscriptionRegistry(source, bus, CoroutineScope(SupervisorJob() + Dispatchers.Default))

/** Collects a subscription's events. */
private class Recorder(subscription: SubscriptionRegistry.Subscription) {
  val events = CopyOnWriteArrayList<SubscriptionEvent>()

  @Volatile var isFinished = false

  init {
    CoroutineScope(Dispatchers.Default).launch(start = CoroutineStart.UNDISPATCHED) {
      subscription.events.collect { events.add(it) }
      isFinished = true
    }
  }

  val values: List<List<Byte>> get() = events.filterIsInstance<SubscriptionEvent.Value>().map { it.value.toList() }
}

class SubscriptionTest : BehaviorSpec({
  given("a connected device") {
    then("subscribes and delivers each notification") {
      val source = source()
      val recorder = Recorder(registry(source).subscribe(testCharacteristic, device))
      source.notify(byteArrayOf(1), testCharacteristic, device)
      source.notify(byteArrayOf(2), testCharacteristic, device)
      source.notify(byteArrayOf(9), readOnlyCharacteristic, device)
      eventually { recorder.values.size == 2 }
      recorder.values shouldBe listOf(listOf<Byte>(1), listOf<Byte>(2))
    }

    then("refuses a second subscription to the same characteristic") {
      val registry = registry(source())
      registry.subscribe(testCharacteristic, device)
      val error = shouldThrow<BleError> { registry.subscribe(testCharacteristic, device) }
      error.code shouldBe BleError.Code.ALREADY_SUBSCRIBED
      error.message shouldContain "Remove it first"
    }

    then("refuses to subscribe to a characteristic that neither notifies nor indicates") {
      shouldThrow<BleError> { registry(source()).subscribe(readOnlyCharacteristic, device) }.code shouldBe BleError.Code.UNSUPPORTED
    }

    then("uses notifications when it can and indications otherwise") {
      val source = source()
      val registry = registry(source)
      registry.subscribe(testCharacteristic, device)
      registry.subscribe(indicateOnlyCharacteristic, device)
      source.notificationSwitches.map { it.second } shouldBe listOf(false, true)
    }

    then("stops notifications when the subscription is removed") {
      val source = source()
      val registry = registry(source)
      val subscription = registry.subscribe(testCharacteristic, device)
      val recorder = Recorder(subscription)
      registry.remove(subscription.id)
      eventually { recorder.isFinished }
      source.notificationSwitches.map { it.first } shouldBe listOf(true, false)
      registry.subscribe(testCharacteristic, device)
    }
  }

  given("a device that reconnects") {
    then("switches notifications back on after a reconnect") {
      val source = source()
      val bus = EventBus()
      registry(source, bus).subscribe(testCharacteristic, device)
      bus.publish(DomainEvent(DomainEvent.Kind.CONNECTED, device))
      eventually { source.notificationSwitches.size == 2 }
      source.notificationSwitches.map { it.first } shouldBe listOf(true, true)
    }

    then("reports when notifications can't be switched back on") {
      val source = source()
      val bus = EventBus()
      val recorder = Recorder(registry(source, bus).subscribe(testCharacteristic, device))
      val refusal = BleError(BleError.Code.PAIRING_REQUIRED, "needs pairing")
      source.switchFailure = refusal
      bus.publish(DomainEvent(DomainEvent.Kind.CONNECTED, device))
      eventually { recorder.events.size == 1 }
      source.switchFailure = null
      source.notify(byteArrayOf(3), testCharacteristic, device)
      eventually { recorder.events.size == 2 }
      (recorder.events[0] as SubscriptionEvent.Failed).error shouldBe refusal
      recorder.values shouldBe listOf(listOf<Byte>(3))
    }
  }

  given("a device the app forgets") {
    then("removes a forgotten device's subscriptions") {
      val source = source()
      val bus = EventBus()
      val registry = registry(source, bus)
      val recorder = Recorder(registry.subscribe(testCharacteristic, device))
      bus.publish(DomainEvent(DomainEvent.Kind.FORGOTTEN, device))
      eventually { recorder.isFinished }
      recorder.events shouldBe emptyList()
      registry.subscribe(testCharacteristic, device)
    }
  }
})
