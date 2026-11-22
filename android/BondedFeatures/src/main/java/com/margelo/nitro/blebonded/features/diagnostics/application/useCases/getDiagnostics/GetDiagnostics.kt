package com.margelo.nitro.blebonded.features.diagnostics.application.useCases.getDiagnostics

import com.margelo.nitro.blebonded.features.diagnostics.application.services.DiagnosticsRecorder
import com.margelo.nitro.blebonded.features.diagnostics.domain.DiagnosticEvent

/** The recorded events, oldest first; only those at or after [since] (milliseconds) when given. */
class GetDiagnostics(private val recorder: DiagnosticsRecorder) {
  suspend operator fun invoke(since: Long?): List<DiagnosticEvent> = recorder.events(since)
}
