package com.margelo.nitro.blebonded.features.permissions.application.interfaces

import com.margelo.nitro.blebonded.features.permissions.domain.PermissionStatus
import kotlinx.coroutines.flow.Flow

/**
 * Where the `permissions` feature learns and asks for the app's Bluetooth permission. Implemented by an adapter
 * on the platform, and by a fake in tests.
 */
interface PermissionSource {
  /** The current status, without asking the user. */
  suspend fun currentStatus(): PermissionStatus

  /** Asks the user where the platform allows it, and returns the resulting status. */
  suspend fun request(): PermissionStatus

  /** Every status the platform reports from now on, repeats included. */
  fun statusChanges(): Flow<PermissionStatus>
}
