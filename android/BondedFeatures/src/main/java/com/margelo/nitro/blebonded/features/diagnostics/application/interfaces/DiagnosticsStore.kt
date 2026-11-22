package com.margelo.nitro.blebonded.features.diagnostics.application.interfaces

import com.margelo.nitro.blebonded.features.diagnostics.domain.DiagnosticEvent

/**
 * Where recorded events survive the app being killed and relaunched. Implemented by a file in the app's private
 * storage, and by a fake in tests.
 */
interface DiagnosticsStore {
  suspend fun load(): List<DiagnosticEvent>

  suspend fun save(events: List<DiagnosticEvent>)

  suspend fun delete()
}
