package com.margelo.nitro.blebonded.features.scanning.application.interfaces

import kotlinx.coroutines.flow.Flow

/** The app's foreground state, which decides whether an unfiltered scan may run (decision R3), and the clock. */
interface ScanEnvironment {
  fun isInBackground(): Boolean

  /** True each time the app goes to the background, false each time it returns. */
  fun backgroundChanges(): Flow<Boolean>

  /** Milliseconds, for spacing scan starts. */
  fun now(): Long
}
