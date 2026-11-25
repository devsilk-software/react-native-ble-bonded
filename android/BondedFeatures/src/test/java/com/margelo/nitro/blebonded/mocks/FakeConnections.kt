package com.margelo.nitro.blebonded.mocks

import com.margelo.nitro.blebonded.features.connection.application.interfaces.ConnectionSource
import com.margelo.nitro.blebonded.features.connection.application.interfaces.PlatformDisconnect
import com.margelo.nitro.blebonded.features.connection.application.interfaces.RememberedDeviceStore
import com.margelo.nitro.blebonded.features.connection.domain.AndroidConnectionOptions
import com.margelo.nitro.blebonded.shared.domain.CharacteristicProperty
import com.margelo.nitro.blebonded.features.connection.domain.ConnectMode
import com.margelo.nitro.blebonded.features.connection.domain.ConnectionChange
import com.margelo.nitro.blebonded.features.connection.domain.ConnectionReadiness
import com.margelo.nitro.blebonded.features.connection.domain.ConnectionState
import com.margelo.nitro.blebonded.features.connection.domain.Disconnect
import com.margelo.nitro.blebonded.features.connection.domain.GattCharacteristic
import com.margelo.nitro.blebonded.features.connection.domain.GattService
import com.margelo.nitro.blebonded.features.connection.domain.RememberedDevice
import com.margelo.nitro.blebonded.shared.domain.BleError
import com.margelo.nitro.blebonded.shared.domain.CharacteristicUuid
import com.margelo.nitro.blebonded.shared.domain.DescriptorUuid
import com.margelo.nitro.blebonded.shared.domain.DeviceId
import com.margelo.nitro.blebonded.shared.domain.ServiceUuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * A [ConnectionSource] the test scripts: each attempt takes the next outcome, and an attempt without one waits for
 * the device until it's cancelled.
 */
class FakeConnectionSource(
  @Volatile var currentReadiness: ConnectionReadiness = ConnectionReadiness.READY,
  outcomes: List<Result<List<GattService>>> = emptyList(),
  private val transientCodes: Set<Int> = setOf(133),
) : ConnectionSource {
  private val outcomes = ConcurrentLinkedQueue(outcomes)
  private val drops = MutableSharedFlow<PlatformDisconnect>(extraBufferCapacity = 16)
  val modes = CopyOnWriteArrayList<ConnectMode>()
  val options = CopyOnWriteArrayList<AndroidConnectionOptions>()
  val cancelledAttempts = AtomicInteger(0)
  val disconnectCount = AtomicInteger(0)

  val attemptCount: Int get() = modes.size

  override fun readiness(): ConnectionReadiness = currentReadiness

  override suspend fun attempt(id: DeviceId, mode: ConnectMode, options: AndroidConnectionOptions): List<GattService> {
    modes.add(mode)
    this.options.add(options)
    outcomes.poll()?.let {
      if (lateSuccessMs > 0) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { kotlinx.coroutines.delay(lateSuccessMs) }
      return it.getOrThrow()
    }
    try {
      awaitCancellation()
    } catch (cancelled: CancellationException) {
      cancelledAttempts.incrementAndGet()
      throw cancelled
    }
  }

  /** How long a disconnect takes, for tests of what happens meanwhile. */
  @Volatile var disconnectPauseMs = 0L

  /** How long the next successful attempt takes, ignoring cancellation, as a platform step that can't be interrupted. */
  @Volatile var lateSuccessMs = 0L

  override suspend fun disconnect(id: DeviceId) {
    disconnectCount.incrementAndGet()
    if (disconnectPauseMs > 0) kotlinx.coroutines.delay(disconnectPauseMs)
  }

  override fun disconnections(): Flow<PlatformDisconnect> = drops

  override fun isTransient(error: BleError, options: AndroidConnectionOptions): Boolean =
    options.retryTransientErrors && error.platformCode in transientCodes

  override fun name(id: DeviceId): String = "Thermo-1"

  /** Every characteristic gets the descriptors 2902 and 2901. */
  override suspend fun withDescriptors(services: List<GattService>, id: DeviceId): List<GattService> =
    services.map { service ->
      service.copy(characteristics = service.characteristics.map { it.copy(descriptors = listOf(DescriptorUuid.parse("2902")!!, DescriptorUuid.parse("2901")!!)) })
    }

  /** The device drops the connection by itself. */
  suspend fun drop(id: DeviceId, disconnect: Disconnect) {
    drops.emit(PlatformDisconnect(id, disconnect))
  }
}

/** Collects connection changes as they arrive. */
class ConnectionRecorder(flow: Flow<ConnectionChange>) {
  val changes = CopyOnWriteArrayList<ConnectionChange>()

  init {
    CoroutineScope(SupervisorJob() + Dispatchers.Default).launch(start = CoroutineStart.UNDISPATCHED) { flow.collect { changes.add(it) } }
  }

  val states: List<ConnectionState> get() = changes.map { it.state }
}

val heartRateService =
  GattService(
    ServiceUuid.parse("180D")!!,
    listOf(GattCharacteristic(CharacteristicUuid.parse("2A37")!!, listOf(CharacteristicProperty.NOTIFY))),
  )

fun transientFailure() = BleError(BleError.Code.CONNECTION_FAILED, "GATT error", 133, operation = "connect")

/** A [RememberedDeviceStore] in memory, starting with what a previous launch saved. */
class FakeRememberedDeviceStore(
  @Volatile var saved: List<RememberedDevice> = emptyList(),
) : RememberedDeviceStore {
  override suspend fun load(): List<RememberedDevice> = saved

  override suspend fun save(devices: List<RememberedDevice>) {
    saved = devices
  }
}
