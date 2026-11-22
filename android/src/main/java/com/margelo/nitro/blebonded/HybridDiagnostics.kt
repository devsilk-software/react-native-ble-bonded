package com.margelo.nitro.blebonded

import com.margelo.nitro.core.Callback
import com.margelo.nitro.core.Promise
import com.margelo.nitro.core.invoke
import com.margelo.nitro.blebonded.features.diagnostics.domain.DiagnosticEvent as RecordedEvent

/**
 * The `Diagnostics` HybridObject: calls the `diagnostics` feature's use cases and maps their results to the
 * generated Nitro types. Nothing else lives here (decision A5).
 */
class HybridDiagnostics : HybridDiagnosticsSpec() {
  companion object : HybridDiagnosticsSpec.Companion {
    private val listeners = ListenerRegistry()

    override fun configure(config: DiagnosticsConfig): Promise<Unit> =
      Promise.async { root().configureDiagnostics(config.enabled, config.capacity?.toInt()) }

    override fun getEvents(since: Double?): Promise<Array<DiagnosticEvent>> =
      Promise.async { root().getDiagnostics(since?.toLong()).map { it.toSpec() }.toTypedArray() }

    override fun clear(): Promise<Unit> = Promise.async { root().clearDiagnostics() }

    override fun addListener(onEvent: Callback<(event: DiagnosticEvent) -> Unit>): Promise<Double> =
      Promise.async { listeners.add(root().observeDiagnostics()) { event -> onEvent(event.toSpec()) } }

    override fun removeListener(id: Double) = listeners.remove(id)

    private fun RecordedEvent.toSpec(): DiagnosticEvent =
      DiagnosticEvent(
        type =
          when (kind) {
            RecordedEvent.Kind.STATE_CHANGED -> DiagnosticEventType.STATECHANGED
            RecordedEvent.Kind.PERMISSION_CHANGED -> DiagnosticEventType.PERMISSIONCHANGED
          },
        at = at.toDouble(),
        appState =
          when (appActivity) {
            RecordedEvent.AppActivity.FOREGROUND -> DiagnosticAppState.FOREGROUND
            RecordedEvent.AppActivity.BACKGROUND -> DiagnosticAppState.BACKGROUND
          },
        deviceId = deviceId,
        code = code,
        platformCode = platformCode?.toDouble(),
        detail = detail,
      )
  }
}
