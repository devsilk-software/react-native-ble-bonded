package com.margelo.nitro.blebonded.features.connection

import com.margelo.nitro.blebonded.features.connection.adapters.androidConnectionSource.AndroidConnectionSource
import com.margelo.nitro.blebonded.features.connection.application.services.ConnectionCoordinator
import com.margelo.nitro.blebonded.features.connection.domain.AndroidConnectionOptions
import com.margelo.nitro.blebonded.features.connection.domain.AndroidConnectionOverrides
import com.margelo.nitro.blebonded.shared.domain.CharacteristicProperty
import com.margelo.nitro.blebonded.features.connection.domain.ConnectMode
import com.margelo.nitro.blebonded.features.connection.domain.ConnectOptions
import com.margelo.nitro.blebonded.features.connection.domain.ConnectionReadiness
import com.margelo.nitro.blebonded.features.connection.domain.ConnectionChange
import com.margelo.nitro.blebonded.features.connection.domain.ConnectionState
import com.margelo.nitro.blebonded.features.connection.domain.DeviceIdentity
import com.margelo.nitro.blebonded.features.connection.domain.RememberedDevice
import com.margelo.nitro.blebonded.features.connection.domain.Disconnect
import com.margelo.nitro.blebonded.features.connection.domain.DisconnectReason
import com.margelo.nitro.blebonded.features.connection.domain.RetryPolicy
import com.margelo.nitro.blebonded.features.connection.domain.ServiceCacheRefresh
import com.margelo.nitro.blebonded.features.connection.domain.SetupStep
import com.margelo.nitro.blebonded.features.connection.domain.disconnectReason
import com.margelo.nitro.blebonded.features.connection.domain.setupSteps
import com.margelo.nitro.blebonded.mocks.ConnectionRecorder
import com.margelo.nitro.blebonded.mocks.FakeRememberedDeviceStore
import com.margelo.nitro.blebonded.mocks.FakeConnectionSource
import com.margelo.nitro.blebonded.mocks.eventually
import com.margelo.nitro.blebonded.mocks.heartRateService
import com.margelo.nitro.blebonded.mocks.transientFailure
import com.margelo.nitro.blebonded.shared.domain.BleError
import com.margelo.nitro.blebonded.shared.domain.DeviceId
import com.margelo.nitro.blebonded.shared.domain.DomainEvent
import com.margelo.nitro.blebonded.shared.domain.EventBus
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout

private val device = DeviceId("A")

private fun coordinator(
  source: FakeConnectionSource,
  bus: EventBus = EventBus(),
  store: FakeRememberedDeviceStore = FakeRememberedDeviceStore(),
) = ConnectionCoordinator(source, bus, CoroutineScope(SupervisorJob() + Dispatchers.Default), store, RetryPolicy(directAttempts = 2)) { 5_000 }

private fun background() = CoroutineScope(SupervisorJob() + Dispatchers.Default)

class ConnectionTest : BehaviorSpec({
  given("a device that answers") {
    `when`("the app connects") {
      then("connects and discovers the device's services") {
        val coordinator = coordinator(FakeConnectionSource(outcomes = listOf(Result.success(listOf(heartRateService)))))
        coordinator.connect(device)
        coordinator.services(device) shouldBe listOf(heartRateService)
      }

      then("reports connecting, then connected once services are discovered") {
        val coordinator = coordinator(FakeConnectionSource(outcomes = listOf(Result.success(listOf(heartRateService)))))
        val recorder = ConnectionRecorder(coordinator.changes())
        coordinator.connect(device)
        eventually { recorder.states.size == 2 }
        recorder.states shouldBe listOf(ConnectionState.CONNECTING, ConnectionState.CONNECTED)
        recorder.changes.last().name shouldBe "Thermo-1"
      }
    }

    `when`("the app connects again") {
      then("resolves at once when already connected") {
        val source = FakeConnectionSource(outcomes = listOf(Result.success(listOf(heartRateService))))
        val coordinator = coordinator(source)
        coordinator.connect(device)
        coordinator.connect(device)
        source.attemptCount shouldBe 1
      }
    }

    `when`("the app disconnects") {
      then("disconnects when asked and reports it as requested") {
        val source = FakeConnectionSource(outcomes = listOf(Result.success(listOf(heartRateService))))
        val coordinator = coordinator(source)
        val recorder = ConnectionRecorder(coordinator.changes())
        coordinator.connect(device)
        coordinator.disconnect(device)
        eventually { recorder.states.size == 4 }
        recorder.states shouldBe
          listOf(ConnectionState.CONNECTING, ConnectionState.CONNECTED, ConnectionState.DISCONNECTING, ConnectionState.DISCONNECTED)
        recorder.changes.last().lastDisconnect shouldBe Disconnect(DisconnectReason.REQUESTED)
        source.disconnectCount.get() shouldBe 1
      }
    }

    `when`("connect is called while a disconnect is still under way") {
      then("connects again only after a disconnect still under way has finished") {
        val source =
          FakeConnectionSource(outcomes = listOf(Result.success(listOf(heartRateService)), Result.success(listOf(heartRateService))))
        source.disconnectPauseMs = 50
        val coordinator = coordinator(source)
        val recorder = ConnectionRecorder(coordinator.changes())
        coordinator.connect(device)
        val disconnecting = background().async { coordinator.disconnect(device) }
        eventually { recorder.states.lastOrNull() == ConnectionState.DISCONNECTING }
        coordinator.connect(device)
        disconnecting.await()
        eventually { recorder.states.size == 6 }
        recorder.states shouldBe
          listOf(
            ConnectionState.CONNECTING,
            ConnectionState.CONNECTED,
            ConnectionState.DISCONNECTING,
            ConnectionState.DISCONNECTED,
            ConnectionState.CONNECTING,
            ConnectionState.CONNECTED,
          )
      }
    }

    `when`("the device drops the connection") {
      then("reports why the device disconnected") {
        val source = FakeConnectionSource(outcomes = listOf(Result.success(listOf(heartRateService))))
        val coordinator = coordinator(source)
        val recorder = ConnectionRecorder(coordinator.changes())
        coordinator.connect(device)
        val lost = Disconnect(DisconnectReason.OUT_OF_RANGE, BleError(BleError.Code.DISCONNECTED, "Out of range", 8))
        source.drop(device, lost)
        eventually { recorder.states.last() == ConnectionState.DISCONNECTED }
        recorder.changes.last().lastDisconnect shouldBe lost
      }
    }

    `when`("a listener subscribes after the device connected") {
      then("reports each device's current state first, then each change") {
        val coordinator = coordinator(FakeConnectionSource(outcomes = listOf(Result.success(listOf(heartRateService)))))
        coordinator.connect(device)
        val recorder = ConnectionRecorder(coordinator.changes())
        coordinator.disconnect(device)
        eventually { recorder.states.size == 3 }
        recorder.states shouldBe listOf(ConnectionState.CONNECTED, ConnectionState.DISCONNECTING, ConnectionState.DISCONNECTED)
      }
    }
  }

  given("a device that isn't there") {
    `when`("connect is called twice") {
      then("shares one attempt when connect is called again while connecting") {
        val source = FakeConnectionSource()
        val coordinator = coordinator(source)
        val options = ConnectOptions(timeoutMs = 50)
        val first = background().async { runCatching { coordinator.connect(device, options) } }
        val second = background().async { runCatching { coordinator.connect(device, options) } }
        listOf(first.await(), second.await()).forEach { (it.exceptionOrNull() as BleError).code shouldBe BleError.Code.TIMEOUT }
        source.attemptCount shouldBe 1
      }
    }

    `when`("the timeout passes") {
      then("gives up after the timeout") {
        val source = FakeConnectionSource()
        val coordinator = coordinator(source)
        shouldThrow<BleError> { coordinator.connect(device, ConnectOptions(timeoutMs = 30)) }.code shouldBe BleError.Code.TIMEOUT
        eventually { source.cancelledAttempts.get() == 1 }
        source.cancelledAttempts.get() shouldBe 1
        shouldThrow<BleError> { coordinator.services(device) }
      }
    }

    `when`("the platform finishes connecting after disconnect was called") {
      then("disconnects a device that finished connecting after disconnect was called") {
        val source = FakeConnectionSource(outcomes = listOf(Result.success(listOf(heartRateService))))
        source.lateSuccessMs = 50
        val coordinator = coordinator(source)
        val attempt = background().async { runCatching { coordinator.connect(device, ConnectOptions(timeoutMs = null)) } }
        eventually { source.attemptCount == 1 }
        coordinator.disconnect(device)
        (attempt.await().exceptionOrNull() as BleError).code shouldBe BleError.Code.CANCELLED
        source.disconnectCount.get() shouldBe 1
        shouldThrow<BleError> { coordinator.services(device) }
      }
    }

    `when`("the app disconnects while connecting") {
      then("cancels a connection attempt when disconnect is called") {
        val source = FakeConnectionSource()
        val coordinator = coordinator(source)
        val recorder = ConnectionRecorder(coordinator.changes())
        val attempt = background().async { runCatching { coordinator.connect(device, ConnectOptions(timeoutMs = null)) } }
        eventually { source.attemptCount == 1 }
        coordinator.disconnect(device)
        (attempt.await().exceptionOrNull() as BleError).code shouldBe BleError.Code.CANCELLED
        eventually { recorder.states.last() == ConnectionState.DISCONNECTED }
        recorder.changes.last().lastDisconnect shouldBe Disconnect(DisconnectReason.REQUESTED)
      }
    }
  }

  given("a device that fails") {
    `when`("it fails with transient errors first") {
      then("retries transient failures, first directly, then waiting for the device") {
        val source =
          FakeConnectionSource(
            outcomes = listOf(Result.failure(transientFailure()), Result.failure(transientFailure()), Result.success(listOf(heartRateService))),
          )
        coordinator(source).connect(device)
        source.modes shouldBe listOf(ConnectMode.DIRECT, ConnectMode.DIRECT, ConnectMode.WAITING)
        RetryPolicy().backoffMs(2) shouldBe 250
        RetryPolicy().backoffMs(3) shouldBe 500
        RetryPolicy().backoffMs(9) shouldBe 2_000
      }
    }

    `when`("it fails with an error that won't pass") {
      then("fails at once on an error that won't pass on its own") {
        val refused = BleError(BleError.Code.CONNECTION_FAILED, "Refused", 5, operation = "connect")
        val source = FakeConnectionSource(outcomes = listOf(Result.failure(refused)))
        shouldThrow<BleError> { coordinator(source).connect(device) } shouldBe refused
        source.attemptCount shouldBe 1
      }
    }
  }

  given("a phone that can't connect") {
    then("refuses to connect while Bluetooth is off or without permission") {
      listOf(
        ConnectionReadiness.BLUETOOTH_OFF to BleError.Code.BLUETOOTH_OFF,
        ConnectionReadiness.UNAUTHORIZED to BleError.Code.UNAUTHORIZED,
      ).forEach { (readiness, code) ->
        val source = FakeConnectionSource(readiness)
        shouldThrow<BleError> { coordinator(source).connect(device) }.code shouldBe code
        source.attemptCount shouldBe 0
      }
    }
  }

  given("a connection waiting for the device") {
    then("keeps waiting for the device while Bluetooth is off") {
      val off = BleError(BleError.Code.BLUETOOTH_OFF, "Bluetooth is off", operation = "connect")
      val source = FakeConnectionSource(outcomes = listOf(Result.failure(off), Result.success(listOf(heartRateService))))
      val coordinator = coordinator(source)
      coordinator.connect(device, ConnectOptions(timeoutMs = null))
      source.attemptCount shouldBe 2
      coordinator.services(device) shouldBe listOf(heartRateService)
    }
  }

  given("a device that isn't connected") {
    then("refuses to read services while not connected") {
      shouldThrow<BleError> { coordinator(FakeConnectionSource()).services(device) }.code shouldBe BleError.Code.NOT_CONNECTED
    }
  }

  given("a device the app connects to") {
    then("remembers a device after it connects") {
      val store = FakeRememberedDeviceStore()
      coordinator(FakeConnectionSource(outcomes = listOf(Result.success(emptyList()))), store = store).connect(device)
      store.saved.map { it.id } shouldBe listOf(device)
      store.saved.first().name shouldBe "Thermo-1"
      store.saved.first().lastConnectedAt shouldBe 5_000
    }

    then("remembers nothing when asked not to") {
      val store = FakeRememberedDeviceStore()
      val coordinator = coordinator(FakeConnectionSource(outcomes = listOf(Result.success(emptyList()))), store = store)
      coordinator.connect(device, ConnectOptions(remember = false))
      store.saved shouldBe emptyList()
      coordinator.rememberedDevices() shouldBe emptyList()
    }

    then("forgets a device and disconnects it") {
      val source = FakeConnectionSource(outcomes = listOf(Result.success(emptyList())))
      val store = FakeRememberedDeviceStore()
      val coordinator = coordinator(source, store = store)
      val recorder = ConnectionRecorder(coordinator.changes())
      coordinator.connect(device)
      coordinator.forget(device)
      eventually { recorder.changes.lastOrNull()?.remembered == null && recorder.states.last() == ConnectionState.DISCONNECTED }
      source.disconnectCount.get() shouldBe 1
      store.saved shouldBe emptyList()
      recorder.changes.last().remembered shouldBe null
    }

    then("keeps the identity the device was found by") {
      val store = FakeRememberedDeviceStore()
      val coordinator =
        coordinator(FakeConnectionSource(outcomes = listOf(Result.success(emptyList()), Result.success(emptyList()))), store = store)
      val found = DeviceIdentity("Thermo-1", listOf("0000180d-0000-1000-8000-00805f9b34fb"), 0x0059)
      coordinator.connect(device, ConnectOptions(identity = found))
      coordinator.disconnect(device)
      coordinator.connect(device)
      store.saved.first().identity shouldBe found
    }
  }

  given("a connected device and one remembered before") {
    then("lists every known connection, remembered devices included") {
      val earlier = RememberedDevice(DeviceId("B"), "Thermo-2", DeviceIdentity("Thermo-2"), 1_000)
      val coordinator =
        coordinator(FakeConnectionSource(outcomes = listOf(Result.success(emptyList()))), store = FakeRememberedDeviceStore(listOf(earlier)))
      coordinator.connect(device, ConnectOptions(remember = false))
      val connections = coordinator.connections()
      connections.map { it.id } shouldBe listOf(device, DeviceId("B"))
      connections.map { it.state } shouldBe listOf(ConnectionState.CONNECTED, ConnectionState.DISCONNECTED)
      connections.last().remembered shouldBe earlier
    }
  }

  given("devices remembered before a relaunch") {
    then("reports remembered devices before they connect again") {
      val earlier = RememberedDevice(DeviceId("B"), "Thermo-2", DeviceIdentity("Thermo-2"), 1_000)
      val coordinator = coordinator(FakeConnectionSource(), store = FakeRememberedDeviceStore(listOf(earlier)))
      val recorder = ConnectionRecorder(coordinator.changes())
      eventually { recorder.changes.size == 1 }
      recorder.changes shouldBe listOf(ConnectionChange(DeviceId("B"), "Thermo-2", ConnectionState.DISCONNECTED, null, earlier))
    }
  }

  given("an event bus") {
    then("publishes connection attempts, retries, connections and disconnections") {
      val bus = EventBus()
      val published = background().async(start = CoroutineStart.UNDISPATCHED) { bus.events().map { it.kind }.take(4).toList() }
      val coordinator = coordinator(FakeConnectionSource(outcomes = listOf(Result.failure(transientFailure()), Result.success(emptyList()))), bus)
      coordinator.connect(device)
      coordinator.disconnect(device)
      withTimeout(2_000) { published.await() } shouldBe
        listOf(DomainEvent.Kind.CONNECTING, DomainEvent.Kind.CONNECTION_RETRIED, DomainEvent.Kind.CONNECTED, DomainEvent.Kind.DISCONNECTED)
    }
  }

  given("Android's characteristic property bits") {
    then("reads every characteristic's properties") {
      AndroidConnectionSource.properties(0x02 or 0x10 or 0x04) shouldBe
        listOf(CharacteristicProperty.READ, CharacteristicProperty.WRITE_WITHOUT_RESPONSE, CharacteristicProperty.NOTIFY)
    }
  }

  given("Android's disconnect statuses") {
    then("maps platform disconnect errors to reasons") {
      disconnectReason(8, isBluetoothOff = false) shouldBe DisconnectReason.OUT_OF_RANGE
      disconnectReason(19, isBluetoothOff = false) shouldBe DisconnectReason.DEVICE_DISCONNECTED
      disconnectReason(133, isBluetoothOff = false) shouldBe DisconnectReason.FAILED
      disconnectReason(8, isBluetoothOff = true) shouldBe DisconnectReason.BLUETOOTH_OFF
    }
  }

  given("Android's connection workarounds") {
    then("plans the setup steps from the workarounds and the bond") {
      val defaults = AndroidConnectionOptions()
      setupSteps(defaults, isBonded = false, isBonding = false) shouldBe
        listOf(SetupStep.HighPriority, SetupStep.RequestMtu(517), SetupStep.DiscoverServices, SetupStep.BalancedPriority)
      setupSteps(defaults, isBonded = false, isBonding = true) shouldBe
        listOf(
          SetupStep.HighPriority,
          SetupStep.RequestMtu(517),
          SetupStep.WaitForBonding,
          SetupStep.Pause(600),
          SetupStep.DiscoverServices,
          SetupStep.BalancedPriority,
        )
      val off =
        defaults.with(
          AndroidConnectionOverrides(
            waitForBonding = false,
            discoveryDelayMs = 0,
            highPriorityDuringSetup = false,
            refreshServiceCache = ServiceCacheRefresh.ALWAYS,
            mtu = 0,
          ),
        )
      setupSteps(off, isBonded = true, isBonding = true) shouldBe listOf(SetupStep.RefreshServiceCache, SetupStep.DiscoverServices)
    }

    then("applies a connection's own workarounds over the ones set for every connection") {
      val source = FakeConnectionSource(outcomes = listOf(Result.success(emptyList())))
      val coordinator = coordinator(source)
      coordinator.configureAndroid(AndroidConnectionOverrides(discoveryDelayMs = 300, waitForBonding = false))
      coordinator.connect(device, ConnectOptions(android = AndroidConnectionOverrides(discoveryDelayMs = 0)))
      source.options.single().discoveryDelayMs shouldBe 0
      source.options.single().waitForBonding shouldBe false
      source.options.single().retryTransientErrors shouldBe true
    }

    then("retries nothing when retrying transient errors is switched off") {
      val source = FakeConnectionSource(outcomes = listOf(Result.failure(transientFailure())))
      shouldThrow<BleError> {
        coordinator(source).connect(device, ConnectOptions(android = AndroidConnectionOverrides(retryTransientErrors = false)))
      }.platformCode shouldBe 133
      source.attemptCount shouldBe 1
    }
  }
})
