package com.margelo.nitro.blebonded.features.scanning

import com.margelo.nitro.blebonded.features.scanning.application.services.ScanCoordinator
import com.margelo.nitro.blebonded.features.scanning.domain.ManufacturerData
import com.margelo.nitro.blebonded.features.scanning.domain.NameFilter
import com.margelo.nitro.blebonded.features.scanning.domain.ScanEvent
import com.margelo.nitro.blebonded.features.scanning.domain.ScanReadiness
import com.margelo.nitro.blebonded.features.scanning.domain.ScanRequest
import com.margelo.nitro.blebonded.features.scanning.domain.ScanStartLimiter
import com.margelo.nitro.blebonded.features.scanning.domain.ScanStopReason
import com.margelo.nitro.blebonded.features.scanning.domain.ScannedDevice
import com.margelo.nitro.blebonded.mocks.FakeScanEnvironment
import com.margelo.nitro.blebonded.mocks.FakeScanSource
import com.margelo.nitro.blebonded.mocks.ScanRecorder
import com.margelo.nitro.blebonded.mocks.advertisement
import com.margelo.nitro.blebonded.mocks.eventually
import com.margelo.nitro.blebonded.shared.domain.BleError
import com.margelo.nitro.blebonded.shared.domain.DeviceId
import com.margelo.nitro.blebonded.shared.domain.DomainEvent
import com.margelo.nitro.blebonded.shared.domain.EventBus
import com.margelo.nitro.blebonded.shared.domain.ServiceUuid
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout

private val heartRate = ServiceUuid.parse("180D")!!

private fun coordinator(
  source: FakeScanSource = FakeScanSource(),
  environment: FakeScanEnvironment = FakeScanEnvironment(),
  bus: EventBus = EventBus(),
) = ScanCoordinator(source, environment, bus, CoroutineScope(SupervisorJob() + Dispatchers.Default))

class ScanningTest : BehaviorSpec({
  given("a running scan") {
    `when`("a device advertises") {
      then("reports each device found with its advertisement") {
        val source = FakeScanSource()
        val scan = ScanRecorder(coordinator(source).start(ScanRequest()))
        source.emit(advertisement("A", name = "Thermo-1", rssi = -42))
        eventually { scan.devices.size == 1 }
        scan.devices shouldBe listOf(ScannedDevice(DeviceId("A"), "Thermo-1", -42, listOf(heartRate)))
      }
    }

    `when`("a device advertises again") {
      then("reports each device once unless duplicates are allowed") {
        val source = FakeScanSource()
        val coordinator = coordinator(source)

        val once = ScanRecorder(coordinator.start(ScanRequest()))
        listOf(-60, -55, -50).forEach { source.emit(advertisement("A", rssi = it)) }
        source.emit(advertisement("B"))
        eventually { once.devices.size == 2 }
        once.devices.map { it.id.rawValue } shouldBe listOf("A", "B")

        val every = ScanRecorder(coordinator.start(ScanRequest(allowDuplicates = true)))
        listOf(-60, -55, -50).forEach { source.emit(advertisement("A", rssi = it)) }
        eventually { every.devices.size == 3 }
        every.devices.map { it.rssi } shouldBe listOf(-60, -55, -50)
      }
    }

    `when`("the scan filters by name") {
      then("reports only devices whose name matches the filter") {
        NameFilter.Exact("Thermo-1").matches("Thermo-1") shouldBe true
        NameFilter.Exact("Thermo-1").matches("Thermo-12") shouldBe false
        NameFilter.Pattern("Thermo-", "A", "3F").matches("Thermo-3F2A") shouldBe true
        NameFilter.Pattern("Thermo-", "B").matches("Thermo-3F2A") shouldBe false
        NameFilter.Pattern(includes = "x").matches(null) shouldBe false

        val source = FakeScanSource()
        val scan = ScanRecorder(coordinator(source).start(ScanRequest(name = NameFilter.Pattern(startsWith = "Thermo-"))))
        source.emit(advertisement("A", name = "Headphones"))
        source.emit(advertisement("B"))
        source.emit(advertisement("C", name = "Thermo-3F2A"))
        eventually { scan.devices.size == 1 }
        scan.devices.map { it.id.rawValue } shouldBe listOf("C")
      }
    }

    `when`("a device advertises manufacturer data") {
      then("splits the company ID off the manufacturer data") {
        ManufacturerData.fromRaw(listOf(0x59, 0x00, 0x01, 0x02).map { it.toByte() }) shouldBe
          ManufacturerData(0x0059, listOf<Byte>(1, 2))
        ManufacturerData.fromRaw(listOf(0x59.toByte())).shouldBeNull()

        val source = FakeScanSource()
        val scan = ScanRecorder(coordinator(source).start(ScanRequest()))
        source.emit(advertisement("A", manufacturer = listOf(0x4C, 0x00, 0x10).map { it.toByte() }))
        eventually { scan.devices.size == 1 }
        scan.devices.first().manufacturerData shouldBe ManufacturerData(0x004C, listOf<Byte>(0x10))
      }
    }

    `when`("the scan asks for the full advertisement or not") {
      then("includes the full advertisement only when asked") {
        val source = FakeScanSource()
        val coordinator = coordinator(source)
        val raw = listOf(0x4C, 0x00, 0x10).map { it.toByte() }

        val lean = ScanRecorder(coordinator.start(ScanRequest()))
        source.emit(advertisement("A", manufacturer = raw))
        eventually { lean.devices.size == 1 }
        lean.devices.first().full.shouldBeNull()

        val full = ScanRecorder(coordinator.start(ScanRequest(fullAdvertisement = true)))
        source.emit(advertisement("A", manufacturer = raw))
        eventually { full.devices.size == 1 }
        val details = full.devices.first().full.shouldNotBeNull()
        details.txPowerLevel shouldBe 4
        details.isConnectable shouldBe true
        details.rawManufacturerData shouldBe raw
        details.timestamp shouldBe 1000
      }
    }

    `when`("the timeout passes") {
      then("stops the scan after the timeout") {
        val source = FakeScanSource()
        val scan = ScanRecorder(coordinator(source).start(ScanRequest(timeoutMs = 20)))
        eventually { scan.stop != null }
        scan.stop shouldBe ScanEvent.Stopped(ScanStopReason.TIMEOUT)
        eventually { !source.isScanning }
        source.isScanning shouldBe false
      }
    }

    `when`("a new scan starts") {
      then("replaces a running scan when a new one starts") {
        val source = FakeScanSource()
        val coordinator = coordinator(source)
        val first = ScanRecorder(coordinator.start(ScanRequest()))
        val second = ScanRecorder(coordinator.start(ScanRequest()))
        source.emit(advertisement("A"))
        eventually { second.devices.size == 1 && first.stop != null }
        first.stop shouldBe ScanEvent.Stopped(ScanStopReason.REPLACED)
        first.devices shouldBe emptyList()
        source.isScanning shouldBe true
      }
    }

    `when`("Bluetooth goes off") {
      then("reports a failure when Bluetooth goes off during a scan") {
        val source = FakeScanSource()
        val scan = ScanRecorder(coordinator(source).start(ScanRequest()))
        eventually { source.isScanning }
        source.turnBluetoothOff()
        eventually { scan.stop != null }
        scan.stop?.reason shouldBe ScanStopReason.FAILED
        scan.stop?.error?.code shouldBe BleError.Code.BLUETOOTH_OFF
      }
    }

    `when`("the app goes to the background and back during an unfiltered scan") {
      then("pauses an unfiltered scan in the background and resumes it in the foreground") {
        val source = FakeScanSource()
        val environment = FakeScanEnvironment()
        val scan = ScanRecorder(coordinator(source, environment).start(ScanRequest()))
        eventually { source.isScanning }

        environment.setInBackground(true)
        eventually { !source.isScanning }
        source.isScanning shouldBe false

        environment.setInBackground(false)
        source.emit(advertisement("A"))
        eventually { scan.devices.size == 1 }
        scan.stop.shouldBeNull()
        scan.devices.map { it.id.rawValue } shouldBe listOf("A")
      }
    }
  }

  given("a phone that can't scan") {
    `when`("a scan starts") {
      then("refuses to scan while Bluetooth is off or without permission") {
        listOf(
          ScanReadiness.BLUETOOTH_OFF to BleError.Code.BLUETOOTH_OFF,
          ScanReadiness.UNAUTHORIZED to BleError.Code.UNAUTHORIZED,
          ScanReadiness.UNSUPPORTED to BleError.Code.UNSUPPORTED,
        ).forEach { (readiness, code) ->
          val source = FakeScanSource(readiness)
          shouldThrow<BleError> { coordinator(source).start(ScanRequest()) }.code shouldBe code
          source.startCount.get() shouldBe 0
        }
      }
    }
  }

  given("an app in the background") {
    `when`("a scan starts") {
      then("refuses an unfiltered scan in the background") {
        val source = FakeScanSource()
        val coordinator = coordinator(source, FakeScanEnvironment(isBackground = true))
        shouldThrow<BleError> { coordinator.start(ScanRequest()) }.code shouldBe BleError.Code.UNSUPPORTED
        coordinator.start(ScanRequest(services = listOf(heartRate)))
        eventually { source.isScanning }
        source.lastServices shouldBe listOf(heartRate)
      }
    }
  }

  given("an event bus") {
    `when`("a scan starts and stops") {
      then("publishes when a scan starts and stops") {
        val bus = EventBus()
        val published = CoroutineScope(Dispatchers.Default).async(start = CoroutineStart.UNDISPATCHED) { bus.events().take(2).toList() }
        val coordinator = coordinator(bus = bus)
        val session = coordinator.start(ScanRequest())
        coordinator.stop(session.id)
        withTimeout(1_000) { published.await() } shouldBe
          listOf(
            DomainEvent(DomainEvent.Kind.SCAN_STARTED, detail = "unfiltered"),
            DomainEvent(DomainEvent.Kind.SCAN_STOPPED, detail = "stopped"),
          )
      }
    }
  }

  given("service UUIDs written in any form") {
    then("expands short service UUIDs to their full form") {
      ServiceUuid.parse("180D")?.rawValue shouldBe "0000180d-0000-1000-8000-00805f9b34fb"
      ServiceUuid.parse("0000FEAA")?.rawValue shouldBe "0000feaa-0000-1000-8000-00805f9b34fb"
      ServiceUuid.parse("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")?.rawValue shouldBe "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
      ServiceUuid.parse("180D") shouldBe ServiceUuid.parse("0000180d-0000-1000-8000-00805f9b34fb")
      ServiceUuid.parse("not a uuid").shouldBeNull()
      ServiceUuid.parse("180").shouldBeNull()
    }
  }

  given("Android's limit of five scan starts in 30 seconds") {
    then("spaces scan starts to stay within Android's limit") {
      val limiter = ScanStartLimiter()
      repeat(5) { limiter.delayBeforeStart(it * 1_000L) shouldBe 0; limiter.recordStart(it * 1_000L) }
      limiter.delayBeforeStart(5_000) shouldBe 25_000
      limiter.delayBeforeStart(30_000) shouldBe 0

      val bus = EventBus()
      val environment = FakeScanEnvironment(clock = 0)
      val published = CoroutineScope(Dispatchers.Default).async(start = CoroutineStart.UNDISPATCHED) { bus.events().take(11).toList() }
      val coordinator = coordinator(environment = environment, bus = bus)
      repeat(6) { coordinator.start(ScanRequest()) }
      withTimeout(1_000) { published.await() }.last() shouldBe
        DomainEvent(DomainEvent.Kind.SCAN_STARTED, detail = "unfiltered, delayed 30000 ms")
    }
  }
})
