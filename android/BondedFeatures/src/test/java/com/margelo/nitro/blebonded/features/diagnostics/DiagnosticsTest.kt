package com.margelo.nitro.blebonded.features.diagnostics

import com.margelo.nitro.blebonded.features.diagnostics.application.services.DiagnosticsRecorder
import com.margelo.nitro.blebonded.features.diagnostics.domain.DiagnosticEvent
import com.margelo.nitro.blebonded.mocks.FakeDiagnosticsEnvironment
import com.margelo.nitro.blebonded.mocks.FakeDiagnosticsStore
import com.margelo.nitro.blebonded.mocks.publish
import com.margelo.nitro.blebonded.shared.domain.DomainEvent
import com.margelo.nitro.blebonded.shared.domain.EventBus
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

private fun recorder(
  store: FakeDiagnosticsStore = FakeDiagnosticsStore(),
  activity: DiagnosticEvent.AppActivity = DiagnosticEvent.AppActivity.FOREGROUND,
  saveDelayMs: Long = 60_000,
): Pair<DiagnosticsRecorder, EventBus> {
  val bus = EventBus()
  val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  return DiagnosticsRecorder(bus, store, FakeDiagnosticsEnvironment(activity), scope, saveDelayMs) to bus
}

private fun state(detail: String) = DomainEvent(DomainEvent.Kind.STATE_CHANGED, detail = detail)

class DiagnosticsTest : BehaviorSpec({
  given("diagnostics that were never turned on") {
    `when`("a feature publishes an event") {
      then("records nothing while diagnostics are off") {
        val store = FakeDiagnosticsStore()
        val (recorder, bus) = recorder(store)
        publish(listOf(state("poweredOn")), bus, recorder, count = 1)
        recorder.saveNow()
        recorder.events(null).shouldBeEmpty()
        store.saved.shouldBeEmpty()
      }
    }
  }

  given("diagnostics turned on") {
    `when`("features publish events while the app is in the background") {
      then("records each published event with its time and the app's state") {
        val (recorder, bus) = recorder(activity = DiagnosticEvent.AppActivity.BACKGROUND)
        recorder.configure(enabled = true, capacity = null)
        publish(
          listOf(state("poweredOff"), DomainEvent(DomainEvent.Kind.PERMISSION_CHANGED, detail = "granted")),
          bus,
          recorder,
          count = 2,
        )
        recorder.events(null) shouldBe
          listOf(
            DiagnosticEvent(DiagnosticEvent.Kind.STATE_CHANGED, 1001, DiagnosticEvent.AppActivity.BACKGROUND, detail = "poweredOff"),
            DiagnosticEvent(DiagnosticEvent.Kind.PERMISSION_CHANGED, 1002, DiagnosticEvent.AppActivity.BACKGROUND, detail = "granted"),
          )
      }
    }

    `when`("more events arrive than the capacity") {
      then("keeps only the newest events up to the capacity") {
        val (recorder, bus) = recorder()
        recorder.configure(enabled = true, capacity = 2)
        publish(listOf("unknown", "poweredOff", "poweredOn").map(::state), bus, recorder, count = 3)
        recorder.events(null).map { it.detail } shouldBe listOf("poweredOff", "poweredOn")
      }
    }

    `when`("asked for the events since a time") {
      then("returns the events at or after a given time") {
        val (recorder, bus) = recorder()
        recorder.configure(enabled = true, capacity = null)
        publish(listOf("unknown", "poweredOff", "poweredOn").map(::state), bus, recorder, count = 3)
        recorder.events(1002).map { it.detail } shouldBe listOf("poweredOff", "poweredOn")
      }
    }

    `when`("a listener is added") {
      then("delivers every event recorded after a listener is added") {
        val (recorder, bus) = recorder()
        recorder.configure(enabled = true, capacity = null)
        val received = CompletableDeferred<DiagnosticEvent>()
        val listener =
          CoroutineScope(Dispatchers.Default).launch(start = CoroutineStart.UNDISPATCHED) {
            received.complete(recorder.liveEvents().first())
          }

        publish(listOf(state("poweredOn")), bus, recorder, count = 1)

        withTimeout(1_000) { received.await() }.detail shouldBe "poweredOn"
        listener.cancel()
      }
    }

    `when`("events were recorded") {
      then("saves the recorded events so they survive the app being killed") {
        val store = FakeDiagnosticsStore()
        val (recorder, bus) = recorder(store, saveDelayMs = 10)
        recorder.configure(enabled = true, capacity = null)
        publish(listOf(state("poweredOn")), bus, recorder, count = 1)
        repeat(200) { if (store.saved.isEmpty()) delay(5) }
        store.saved.map { it.detail } shouldBe listOf("poweredOn")
      }
    }
  }

  given("events saved before a relaunch") {
    val earlier = DiagnosticEvent(DiagnosticEvent.Kind.PERMISSION_CHANGED, 500, DiagnosticEvent.AppActivity.FOREGROUND, detail = "denied")

    `when`("diagnostics are turned on again") {
      then("restores the events saved before a relaunch") {
        val (recorder, _) = recorder(FakeDiagnosticsStore(listOf(earlier)))
        recorder.configure(enabled = true, capacity = null)
        recorder.events(null) shouldBe listOf(earlier)
      }
    }

    `when`("they are cleared, then diagnostics are turned off") {
      then("deletes the stored events when cleared or turned off") {
        val store = FakeDiagnosticsStore(listOf(earlier))
        val (recorder, _) = recorder(store)
        recorder.configure(enabled = true, capacity = null)

        recorder.clear()
        recorder.events(null).shouldBeEmpty()
        store.saved.shouldBeEmpty()

        recorder.configure(enabled = false, capacity = null)
        store.deleteCount shouldBe 2
      }
    }
  }
})
