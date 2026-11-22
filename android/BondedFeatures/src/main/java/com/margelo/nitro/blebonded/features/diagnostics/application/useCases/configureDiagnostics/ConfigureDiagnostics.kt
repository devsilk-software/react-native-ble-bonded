package com.margelo.nitro.blebonded.features.diagnostics.application.useCases.configureDiagnostics

import com.margelo.nitro.blebonded.features.diagnostics.application.services.DiagnosticsRecorder

/** Turns diagnostics on or off and sets how many events to keep. Turning off deletes the stored events. */
class ConfigureDiagnostics(private val recorder: DiagnosticsRecorder) {
  suspend operator fun invoke(enabled: Boolean?, capacity: Int?) = recorder.configure(enabled, capacity)
}
