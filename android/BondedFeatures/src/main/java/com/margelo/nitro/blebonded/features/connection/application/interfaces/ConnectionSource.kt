package com.margelo.nitro.blebonded.features.connection.application.interfaces

import com.margelo.nitro.blebonded.features.connection.domain.AndroidConnectionOptions
import com.margelo.nitro.blebonded.features.connection.domain.ConnectMode
import com.margelo.nitro.blebonded.features.connection.domain.ConnectionReadiness
import com.margelo.nitro.blebonded.features.connection.domain.Disconnect
import com.margelo.nitro.blebonded.features.connection.domain.GattService
import com.margelo.nitro.blebonded.shared.domain.BleError
import com.margelo.nitro.blebonded.shared.domain.DeviceId
import kotlinx.coroutines.flow.Flow

/** The platform's connections, in Bonded's words. Implemented with Android's GATT client, and by a fake in tests. */
interface ConnectionSource {
  fun readiness(): ConnectionReadiness

  /**
   * Connects and discovers the device's services, applying [options]; returns them once the device is ready.
   * Throws a [BleError]. Cancelling the caller cancels the attempt.
   */
  suspend fun attempt(id: DeviceId, mode: ConnectMode, options: AndroidConnectionOptions): List<GattService>

  /** Disconnects a connected device and returns once it has. */
  suspend fun disconnect(id: DeviceId)

  /** Every disconnection the app didn't ask for. */
  fun disconnections(): Flow<PlatformDisconnect>

  /** Whether an attempt failed for a reason that may pass on its own. */
  fun isTransient(error: BleError, options: AndroidConnectionOptions): Boolean

  /** The device's name, as the platform last saw it. */
  fun name(id: DeviceId): String?
}

data class PlatformDisconnect(val id: DeviceId, val disconnect: Disconnect)
