package com.margelo.nitro.blebonded.features.connection.application.services

import com.margelo.nitro.blebonded.features.connection.application.interfaces.ConnectionSource
import com.margelo.nitro.blebonded.features.connection.application.interfaces.PlatformDisconnect
import com.margelo.nitro.blebonded.features.connection.application.interfaces.RememberedDeviceStore
import com.margelo.nitro.blebonded.features.connection.domain.AndroidConnectionOptions
import com.margelo.nitro.blebonded.features.connection.domain.AndroidConnectionOverrides
import com.margelo.nitro.blebonded.features.connection.domain.ConnectOptions
import com.margelo.nitro.blebonded.features.connection.domain.ConnectionReadiness
import com.margelo.nitro.blebonded.features.connection.domain.ConnectionChange
import com.margelo.nitro.blebonded.features.connection.domain.ConnectionState
import com.margelo.nitro.blebonded.features.connection.domain.DeviceIdentity
import com.margelo.nitro.blebonded.features.connection.domain.RememberedDevice
import com.margelo.nitro.blebonded.features.connection.domain.Disconnect
import com.margelo.nitro.blebonded.features.connection.domain.DisconnectReason
import com.margelo.nitro.blebonded.features.connection.domain.GattService
import com.margelo.nitro.blebonded.features.connection.domain.RetryPolicy
import com.margelo.nitro.blebonded.shared.application.EventBus
import com.margelo.nitro.blebonded.shared.domain.BleError
import com.margelo.nitro.blebonded.shared.domain.DeviceId
import com.margelo.nitro.blebonded.shared.domain.DomainEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * Owns every device's connection: connects and discovers in one step, gives up after the timeout, retries
 * failures that may pass on their own, shares one attempt between callers, cancels an attempt on `disconnect`,
 * and reports each change, current state first (decision A6), and publishes it for diagnostics.
 */
class ConnectionCoordinator(
  private val source: ConnectionSource,
  private val bus: EventBus,
  private val scope: CoroutineScope,
  private val store: RememberedDeviceStore,
  private val retry: RetryPolicy = RetryPolicy(),
  private val now: () -> Long = System::currentTimeMillis,
) {
  private val lock = Mutex()
  private val devices = HashMap<DeviceId, Entry>()
  private val remembered = HashMap<DeviceId, RememberedDevice>()
  private var hasLoaded = false

  /** Held through each save, which writes the list as it is then, so saves never land out of order. */
  private val saving = Mutex()
  private val changes = MutableSharedFlow<ConnectionChange>(extraBufferCapacity = 64)
  private var watching: Job? = null

  /** The workarounds every connection uses unless its own options say otherwise. */
  @Volatile var androidDefaults = AndroidConnectionOptions()
    private set

  private class Entry {
    var name: String? = null
    var state = ConnectionState.DISCONNECTED
    var lastDisconnect: Disconnect? = null
    var services: List<GattService> = emptyList()
    var attempt: Deferred<List<GattService>>? = null
    var work: Job? = null
    var isCancelling = false

    /** A `disconnect()` still under way; a new connection waits for it. */
    var disconnecting: Deferred<Unit>? = null
  }

  fun configureAndroid(overrides: AndroidConnectionOverrides) {
    androidDefaults = androidDefaults.with(overrides)
  }

  /**
   * Connects and discovers the device's services; returns once it's ready. Returns at once when it's already
   * connected, and shares the running attempt when one is under way. Throws a [BleError].
   */
  /** Disconnects the device and forgets it. The system's pairing stays. */
  suspend fun forget(id: DeviceId) {
    loadRemembered()
    disconnect(id)
    bus.publish(DomainEvent(DomainEvent.Kind.FORGOTTEN, id))
    lock.withLock {
      if (remembered.remove(id) == null) return
      emit(id)
    }
    persist()
  }

  /** Every known device's current connection, remembered devices included. */
  suspend fun connections(): List<ConnectionChange> {
    loadRemembered()
    return lock.withLock { (devices.keys + remembered.keys).sortedBy { it.rawValue }.map { snapshot(it) } }
  }

  /** Saves the remembered devices as they are once the previous save has finished. */
  private suspend fun persist() {
    saving.withLock { store.save(lock.withLock { remembered.values.toList() }) }
  }

  /** The remembered devices, most recently connected first. */
  suspend fun rememberedDevices(): List<RememberedDevice> {
    loadRemembered()
    return lock.withLock { remembered.values.sortedByDescending { it.lastConnectedAt } }
  }

  private suspend fun loadRemembered() {
    if (lock.withLock { hasLoaded }) return
    val stored = store.load()
    lock.withLock {
      if (hasLoaded) return
      hasLoaded = true
      stored.forEach { remembered.putIfAbsent(it.id, it) }
    }
  }

  suspend fun connect(id: DeviceId, options: ConnectOptions = ConnectOptions()) {
    loadRemembered()
    watchDisconnections()
    while (true) {
      val disconnecting = lock.withLock { devices[id]?.disconnecting } ?: break
      disconnecting.await()
    }
    source.readiness().error?.let { throw it }
    var isNew = false
    val attempt =
      lock.withLock {
        val entry = devices.getOrPut(id) { Entry() }
        if (entry.state == ConnectionState.CONNECTED) return
        entry.attempt ?: scope.async(start = CoroutineStart.LAZY) { run(id, options) }.also {
          isNew = true
          entry.attempt = it
          entry.name = source.name(id) ?: entry.name
          entry.state = ConnectionState.CONNECTING
          emit(id, entry)
        }
      }
    if (isNew) {
      bus.publish(DomainEvent(DomainEvent.Kind.CONNECTING, id))
      attempt.start()
    }
    attempt.await()
  }

  /** Disconnects the device and returns once it has. An attempt under way is cancelled; its callers get `cancelled`. */
  suspend fun disconnect(id: DeviceId) {
    val waitFor: Deferred<*> =
      lock.withLock {
        val entry = devices[id] ?: return
        entry.attempt?.let {
          entry.isCancelling = true
          entry.work?.cancel()
          it
        } ?: entry.disconnecting ?: run {
          if (entry.state != ConnectionState.CONNECTED) return
          entry.state = ConnectionState.DISCONNECTING
          emit(id, entry)
          scope.async(start = CoroutineStart.LAZY) { finishDisconnect(id) }.also { entry.disconnecting = it }
        }
      }
    waitFor.start()
    runCatching { waitFor.await() }
  }

  private suspend fun finishDisconnect(id: DeviceId) {
    source.disconnect(id)
    lock.withLock {
      val entry = devices.getValue(id)
      entry.state = ConnectionState.DISCONNECTED
      entry.lastDisconnect = Disconnect(DisconnectReason.REQUESTED)
      entry.services = emptyList()
      entry.disconnecting = null
      emit(id, entry)
    }
    bus.publish(DomainEvent(DomainEvent.Kind.DISCONNECTED, id, detail = DisconnectReason.REQUESTED.publicName))
  }

  /** The services discovered when the device connected. Throws `notConnected` while it isn't connected. */
  suspend fun services(id: DeviceId): List<GattService> =
    lock.withLock {
      val entry = devices[id]
      if (entry == null || entry.state != ConnectionState.CONNECTED) {
        throw BleError(BleError.Code.NOT_CONNECTED, "The device isn't connected", deviceId = id, operation = "getServices")
      }
      entry.services
    }

  /** The services, with each characteristic's descriptors when [descriptors]. Throws `notConnected` while it isn't connected. */
  suspend fun services(id: DeviceId, descriptors: Boolean): List<GattService> {
    val services = services(id)
    return if (descriptors) source.withDescriptors(services, id) else services
  }

  /** Every known device's current connection, remembered devices included, then every change. */
  fun changes(): Flow<ConnectionChange> =
    changes.onSubscription {
      loadRemembered()
      val current = lock.withLock { (devices.keys + remembered.keys).sortedBy { it.rawValue }.map { snapshot(it) } }
      current.forEach { emit(it) }
    }

  // Attempts

  private suspend fun run(id: DeviceId, options: ConnectOptions): List<GattService> {
    val started = System.currentTimeMillis()
    val android = androidDefaults.with(options.android)
    val work = scope.async { attempts(id, android, isWaiting = options.timeoutMs == null) }
    lock.withLock {
      val entry = devices[id]
      entry?.work = work
      // `disconnect()` may have come before the work existed.
      if (entry?.isCancelling == true) work.cancel()
    }
    try {
      val timeoutMs = options.timeoutMs
      val services = if (timeoutMs == null) work.await() else withTimeout(timeoutMs) { work.await() }
      // One locked section from this check to `connected`: a `disconnect()` or `forget()` either comes before it,
      // and the failure path drops the link, or after it, and finds the device connected.
      lock.withLock {
        val entry = devices.getValue(id)
        if (entry.isCancelling) throw CancellationException("disconnected while connecting")
        entry.name = source.name(id) ?: entry.name
        if (options.remember) {
          val known = remembered[id]?.identity ?: DeviceIdentity(name = entry.name)
          remembered[id] = RememberedDevice(id, entry.name, known.updated(options.identity), now())
        }
        entry.state = ConnectionState.CONNECTED
        entry.services = services
        entry.attempt = null
        entry.work = null
        emit(id, entry)
      }
      if (options.remember) persist()
      bus.publish(DomainEvent(DomainEvent.Kind.CONNECTED, id, detail = "in ${System.currentTimeMillis() - started} ms"))
      return services
    } catch (failure: Throwable) {
      work.cancel()
      if (failure is CancellationException && failure !is TimeoutCancellationException) currentCoroutineContext().ensureActive()
      // Whatever the platform had connected by now goes, so nothing holds the device after `disconnect()`.
      if (lock.withLock { devices.getValue(id).isCancelling }) source.disconnect(id)
      val disconnect: Disconnect
      val error: BleError
      lock.withLock {
        val entry = devices.getValue(id)
        error =
          when {
            entry.isCancelling -> cancelled(id)
            failure is TimeoutCancellationException ->
              BleError(BleError.Code.TIMEOUT, "Couldn't connect within ${options.timeoutMs} ms", deviceId = id, operation = "connect")
            failure is BleError -> failure
            failure is CancellationException -> cancelled(id)
            else -> BleError(BleError.Code.UNKNOWN, failure.toString(), deviceId = id, operation = "connect")
          }
        disconnect = if (entry.isCancelling) Disconnect(DisconnectReason.REQUESTED) else Disconnect(DisconnectReason.FAILED, error)
        entry.state = ConnectionState.DISCONNECTED
        entry.attempt = null
        entry.work = null
        entry.isCancelling = false
        entry.lastDisconnect = disconnect
        emit(id, entry)
      }
      bus.publish(
        DomainEvent(
          DomainEvent.Kind.DISCONNECTED,
          id,
          detail = disconnect.reason.publicName,
          errorCode = disconnect.error?.code,
          platformCode = disconnect.error?.platformCode,
        ),
      )
      throw error
    }
  }

  /**
   * With [isWaiting], the caller waits for the device without a timeout, so Bluetooth going off doesn't fail the
   * attempt: it waits for Bluetooth to come back and tries again.
   */
  private suspend fun attempts(id: DeviceId, options: AndroidConnectionOptions, isWaiting: Boolean): List<GattService> {
    var attempt = 1
    while (true) {
      try {
        return source.attempt(id, retry.mode(attempt), options)
      } catch (error: BleError) {
        if (isWaiting && error.code == BleError.Code.BLUETOOTH_OFF) {
          while (source.readiness() != ConnectionReadiness.READY) delay(1_000)
          continue
        }
        if (!source.isTransient(error, options)) throw error
        currentCoroutineContext().ensureActive()
        attempt += 1
        bus.publish(
          DomainEvent(DomainEvent.Kind.CONNECTION_RETRIED, id, detail = "attempt $attempt", errorCode = error.code, platformCode = error.platformCode),
        )
        delay(retry.backoffMs(attempt))
      }
    }
  }

  private fun cancelled(id: DeviceId) =
    BleError(BleError.Code.CANCELLED, "The connection attempt was cancelled", deviceId = id, operation = "connect")

  // Disconnections the app didn't ask for

  private suspend fun watchDisconnections() {
    lock.withLock {
      if (watching != null) return
      watching = scope.launch(start = CoroutineStart.UNDISPATCHED) { source.disconnections().collect { handle(it) } }
    }
  }

  private suspend fun handle(disconnection: PlatformDisconnect) {
    lock.withLock {
      // During an attempt the attempt itself fails; during `disconnect()` the reason is `requested`.
      val entry = devices[disconnection.id]
      if (entry == null || entry.state != ConnectionState.CONNECTED) return
      entry.state = ConnectionState.DISCONNECTED
      entry.lastDisconnect = disconnection.disconnect
      entry.services = emptyList()
      emit(disconnection.id, entry)
    }
    val error = disconnection.disconnect.error
    bus.publish(
      DomainEvent(
        DomainEvent.Kind.DISCONNECTED,
        disconnection.id,
        detail = disconnection.disconnect.reason.publicName,
        errorCode = error?.code,
        platformCode = error?.platformCode,
      ),
    )
  }

  // State, with the lock held

  private fun emit(id: DeviceId, entry: Entry) {
    changes.tryEmit(snapshot(id, entry))
  }

  private fun emit(id: DeviceId) {
    changes.tryEmit(snapshot(id))
  }

  private fun snapshot(id: DeviceId): ConnectionChange =
    devices[id]?.let { snapshot(id, it) }
      ?: remembered[id]?.let { ConnectionChange(id, it.name, ConnectionState.DISCONNECTED, null, it) }
      ?: ConnectionChange(id, null, ConnectionState.DISCONNECTED, null)

  private fun snapshot(id: DeviceId, entry: Entry): ConnectionChange {
    val saved = remembered[id]
    return ConnectionChange(id, entry.name ?: saved?.name, entry.state, entry.lastDisconnect, saved)
  }
}
