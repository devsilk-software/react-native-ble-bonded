package com.margelo.nitro.blebonded.features.diagnostics.adapters.systemDiagnosticsEnvironment

import com.margelo.nitro.blebonded.features.diagnostics.application.interfaces.DiagnosticsEnvironment
import com.margelo.nitro.blebonded.features.diagnostics.domain.DiagnosticEvent
import com.margelo.nitro.blebonded.infrastructure.lifecycle.AppForeground

/** Answers [DiagnosticsEnvironment] with the system clock and the app's foreground or background state. */
class SystemDiagnosticsEnvironment(private val foreground: AppForeground) : DiagnosticsEnvironment {
  override fun now(): Long = System.currentTimeMillis()

  override fun appActivity(): DiagnosticEvent.AppActivity =
    if (foreground.isInForeground()) DiagnosticEvent.AppActivity.FOREGROUND else DiagnosticEvent.AppActivity.BACKGROUND
}
