package com.margelo.nitro.blebonded.features.scanning.adapters.systemScanEnvironment

import com.margelo.nitro.blebonded.features.scanning.application.interfaces.ScanEnvironment
import com.margelo.nitro.blebonded.infrastructure.lifecycle.AppForeground
import kotlinx.coroutines.flow.Flow

/** Answers [ScanEnvironment] with the app's activity lifecycle and the system's monotonic clock. */
class SystemScanEnvironment(private val foreground: AppForeground) : ScanEnvironment {
  override fun isInBackground(): Boolean = !foreground.isInForeground()

  override fun backgroundChanges(): Flow<Boolean> = foreground.backgroundChanges()

  override fun now(): Long = android.os.SystemClock.elapsedRealtime()
}
