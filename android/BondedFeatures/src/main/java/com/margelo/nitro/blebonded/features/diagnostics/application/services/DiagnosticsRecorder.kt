package com.margelo.nitro.blebonded.features.diagnostics.application.services

import com.margelo.nitro.blebonded.features.diagnostics.application.interfaces.DiagnosticsEnvironment
import com.margelo.nitro.blebonded.features.diagnostics.application.interfaces.DiagnosticsStore
import com.margelo.nitro.blebonded.features.diagnostics.domain.DiagnosticEvent
import com.margelo.nitro.blebonded.features.diagnostics.domain.EventLog
import com.margelo.nitro.blebonded.shared.domain.DomainEvent
import com.margelo.nitro.blebonded.shared.domain.EventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Records domain events from the event bus while diagnostics are on, keeps the newest up to the capacity, and
 * saves them in batches so they survive the app being killed. Off by default: nothing is recorded or written.
 */
class DiagnosticsRecorder(
  private val bus: EventBus,
  private val store: DiagnosticsStore,
  private val environment: DiagnosticsEnvironment,
  private val scope: CoroutineScope,
  private val saveDelayMs: Long = 2_000,
) {
  private val mutex = Mutex()

  /**
   * Held through every storage operation, snapshot included, so saves and deletes reach the store in the order
   * they were asked for, and a save taken before a clear can't write after it.
   */
  private val storage = Mutex()
  private val live = MutableSharedFlow<DiagnosticEvent>(extraBufferCapacity = 64)
  private var log = EventLog()
  private var hasLoaded = false
  private var recording: Job? = null
  private var pendingSave: Job? = null

  var isEnabled = false
    private set

  /** Changes the settings; null keeps the current value. Turning off deletes the stored events. */
  suspend fun configure(enabled: Boolean?, capacity: Int?) {
    mutex.withLock {
      if (capacity != null) {
        log.setCapacity(capacity)
        if (isEnabled) scheduleSave()
      }
    }
    if (enabled == null || enabled == isEnabled) return
    if (enabled) start() else stop()
  }

  suspend fun events(since: Long?): List<DiagnosticEvent> = mutex.withLock { log.events(since) }

  suspend fun clear() {
    storage.withLock {
      mutex.withLock {
        log.clear()
        pendingSave?.cancel()
        pendingSave = null
      }
      store.delete()
    }
  }

  /** Every event recorded from now on. */
  fun liveEvents(): Flow<DiagnosticEvent> = live

  /** Writes the events now instead of after the batching delay. */
  suspend fun saveNow() {
    mutex.withLock { pendingSave?.cancel() }
    savePending()
  }

  /** Saves without cancelling the pending save, which may be the coroutine calling this. */
  private suspend fun savePending() {
    storage.withLock {
      val events =
        mutex.withLock {
          pendingSave = null
          if (!isEnabled) return
          log.events
        }
      store.save(events)
    }
  }

  private suspend fun start() {
    val stored = if (hasLoaded) emptyList() else storage.withLock { store.load() }
    mutex.withLock {
      isEnabled = true
      if (!hasLoaded) {
        log = EventLog(log.capacity, stored + log.events)
        hasLoaded = true
      }
    }
    recording = scope.launch(start = CoroutineStart.UNDISPATCHED) { bus.events().collect { record(it) } }
  }

  private suspend fun stop() {
    recording?.cancel()
    recording = null
    mutex.withLock {
      isEnabled = false
      hasLoaded = true
    }
    clear()
  }

  private suspend fun record(domainEvent: DomainEvent) {
    val event =
      DiagnosticEvent(
        kind = DiagnosticEvent.Kind.valueOf(domainEvent.kind.name),
        at = environment.now(),
        appActivity = environment.appActivity(),
        deviceId = domainEvent.deviceId?.rawValue,
        code = domainEvent.errorCode?.publicName,
        platformCode = domainEvent.platformCode,
        detail = domainEvent.detail,
      )
    mutex.withLock {
      if (!isEnabled) return
      log.append(event)
      scheduleSave()
    }
    live.emit(event)
  }

  private fun scheduleSave() {
    if (pendingSave != null) return
    pendingSave =
      scope.launch {
        delay(saveDelayMs)
        savePending()
      }
  }
}
