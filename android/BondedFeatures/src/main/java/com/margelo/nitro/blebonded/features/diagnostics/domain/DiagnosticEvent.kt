package com.margelo.nitro.blebonded.features.diagnostics.domain

/** One recorded event: metadata only, never characteristic values. */
data class DiagnosticEvent(
  val kind: Kind,
  /** Milliseconds since the Unix epoch. */
  val at: Long,
  val appActivity: AppActivity,
  val deviceId: String? = null,
  val code: String? = null,
  val platformCode: Int? = null,
  val detail: String? = null,
) {
  enum class Kind { STATE_CHANGED, PERMISSION_CHANGED, SCAN_STARTED, SCAN_STOPPED }

  /** Whether the app was in the foreground or the background when the event happened. */
  enum class AppActivity { FOREGROUND, BACKGROUND }
}
