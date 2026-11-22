package com.margelo.nitro.blebonded.features.diagnostics.application.useCases.clearDiagnostics

import com.margelo.nitro.blebonded.features.diagnostics.application.services.DiagnosticsRecorder

/** Deletes the recorded events, in memory and in storage. */
class ClearDiagnostics(private val recorder: DiagnosticsRecorder) {
  suspend operator fun invoke() = recorder.clear()
}
