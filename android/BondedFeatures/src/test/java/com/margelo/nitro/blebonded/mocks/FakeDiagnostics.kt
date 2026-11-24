package com.margelo.nitro.blebonded.mocks

import com.margelo.nitro.blebonded.features.diagnostics.application.interfaces.DiagnosticsEnvironment
import com.margelo.nitro.blebonded.features.diagnostics.application.interfaces.DiagnosticsStore
import com.margelo.nitro.blebonded.features.diagnostics.application.services.DiagnosticsRecorder
import com.margelo.nitro.blebonded.features.diagnostics.domain.DiagnosticEvent
import com.margelo.nitro.blebonded.shared.application.EventBus
import com.margelo.nitro.blebonded.shared.domain.DomainEvent
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicLong

/** A [DiagnosticsStore] in memory, starting with whatever a previous launch left. */
class FakeDiagnosticsStore(saved: List<DiagnosticEvent> = emptyList()) : DiagnosticsStore {
  @Volatile var saved: List<DiagnosticEvent> = saved
    private set

  @Volatile var deleteCount = 0
    private set

  override suspend fun load(): List<DiagnosticEvent> = saved

  override suspend fun save(events: List<DiagnosticEvent>) {
    saved = events
  }

  override suspend fun delete() {
    saved = emptyList()
    deleteCount += 1
  }
}

/** A [DiagnosticsEnvironment] with a clock that ticks by one millisecond per event and a fixed app state. */
class FakeDiagnosticsEnvironment(private val activity: DiagnosticEvent.AppActivity = DiagnosticEvent.AppActivity.FOREGROUND) :
  DiagnosticsEnvironment {
  private val clock = AtomicLong(1000)

  override fun now(): Long = clock.incrementAndGet()

  override fun appActivity(): DiagnosticEvent.AppActivity = activity
}

/** Publishes events and waits until the recorder has recorded them. */
suspend fun publish(events: List<DomainEvent>, bus: EventBus, recorder: DiagnosticsRecorder, count: Int) {
  events.forEach { bus.publish(it) }
  repeat(200) {
    if (recorder.events(null).size >= count) return
    delay(5)
  }
}
