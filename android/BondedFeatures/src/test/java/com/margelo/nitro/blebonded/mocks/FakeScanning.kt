package com.margelo.nitro.blebonded.mocks

import com.margelo.nitro.blebonded.features.scanning.application.interfaces.ScanEnvironment
import com.margelo.nitro.blebonded.features.scanning.application.interfaces.ScanSource
import com.margelo.nitro.blebonded.features.scanning.application.services.ScanSession
import com.margelo.nitro.blebonded.features.scanning.domain.Advertisement
import com.margelo.nitro.blebonded.features.scanning.domain.ScanEvent
import com.margelo.nitro.blebonded.features.scanning.domain.ScanReadiness
import com.margelo.nitro.blebonded.features.scanning.domain.ScannedDevice
import com.margelo.nitro.blebonded.shared.domain.DeviceId
import com.margelo.nitro.blebonded.shared.domain.ServiceUuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** A [ScanSource] the test drives: it reports the readiness it's given and the advertisements the test emits. */
class FakeScanSource(
  @Volatile var currentReadiness: ScanReadiness = ScanReadiness.READY,
) : ScanSource {
  private val advertisements = MutableSharedFlow<Advertisement?>(extraBufferCapacity = 64)
  private val active = AtomicInteger(0)
  val startCount = AtomicInteger(0)

  @Volatile var lastServices: List<ServiceUuid> = emptyList()
    private set

  val isScanning: Boolean get() = active.get() > 0

  override fun readiness(): ScanReadiness = currentReadiness

  override fun advertisements(services: List<ServiceUuid>, allowDuplicates: Boolean): Flow<Advertisement> =
    flow {
      startCount.incrementAndGet()
      lastServices = services
      var isSubscribed = false
      try {
        advertisements
          .onSubscription {
            isSubscribed = true
            active.incrementAndGet()
          }.takeWhile { it != null }
          .collect { emit(it!!) }
      } finally {
        if (isSubscribed) active.decrementAndGet()
      }
    }

  suspend fun emit(advertisement: Advertisement) {
    eventually { isScanning }
    advertisements.emit(advertisement)
  }

  /** Bluetooth goes off: readiness changes and the platform stops scanning on its own. */
  suspend fun turnBluetoothOff() {
    currentReadiness = ScanReadiness.BLUETOOTH_OFF
    advertisements.emit(null)
  }
}

/** A [ScanEnvironment] whose app the test sends to the background and back, with a clock the test sets. */
class FakeScanEnvironment(
  @Volatile var isBackground: Boolean = false,
  @Volatile var clock: Long = 0,
) : ScanEnvironment {
  private val changes = MutableSharedFlow<Boolean>(extraBufferCapacity = 16)

  override fun isInBackground(): Boolean = isBackground

  override fun backgroundChanges(): Flow<Boolean> = changes

  override fun now(): Long = clock

  suspend fun setInBackground(isInBackground: Boolean) {
    isBackground = isInBackground
    changes.emit(isInBackground)
  }
}

/** Collects a scan's events as they arrive. */
class ScanRecorder(session: ScanSession) {
  val events = CopyOnWriteArrayList<ScanEvent>()

  init {
    CoroutineScope(SupervisorJob() + Dispatchers.Default).launch { session.events.collect { events.add(it) } }
  }

  val devices: List<ScannedDevice> get() = events.filterIsInstance<ScanEvent.Device>().map { it.device }
  val stop: ScanEvent.Stopped? get() = events.filterIsInstance<ScanEvent.Stopped>().firstOrNull()
}

/** Waits until [condition] holds, for up to a second. */
suspend fun eventually(condition: () -> Boolean) {
  repeat(200) {
    if (condition()) return
    delay(5)
  }
}

fun advertisement(id: String, name: String? = null, rssi: Int = -60, manufacturer: List<Byte>? = null): Advertisement =
  Advertisement(
    id = DeviceId(id),
    name = name,
    rssi = rssi,
    serviceUuids = listOf(ServiceUuid.parse("180D")!!),
    rawManufacturerData = manufacturer,
    txPowerLevel = 4,
    isConnectable = true,
    timestamp = 1000,
  )
