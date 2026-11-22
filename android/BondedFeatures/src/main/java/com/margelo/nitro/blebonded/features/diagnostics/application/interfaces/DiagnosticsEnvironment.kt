package com.margelo.nitro.blebonded.features.diagnostics.application.interfaces

import com.margelo.nitro.blebonded.features.diagnostics.domain.DiagnosticEvent

/** The time and the app's foreground or background state, which every recorded event carries. */
interface DiagnosticsEnvironment {
  /** Milliseconds since the Unix epoch. */
  fun now(): Long

  fun appActivity(): DiagnosticEvent.AppActivity
}
