package com.margelo.nitro.blebonded.features.diagnostics.application.useCases.observeDiagnostics

import com.margelo.nitro.blebonded.features.diagnostics.application.services.DiagnosticsRecorder
import com.margelo.nitro.blebonded.features.diagnostics.domain.DiagnosticEvent
import kotlinx.coroutines.flow.Flow

/** Every event recorded from now on. */
class ObserveDiagnostics(private val recorder: DiagnosticsRecorder) {
  operator fun invoke(): Flow<DiagnosticEvent> = recorder.liveEvents()
}
