package com.margelo.nitro.blebonded.features.bluetoothState.application.interfaces

import com.margelo.nitro.blebonded.features.bluetoothState.domain.AdapterState
import kotlinx.coroutines.flow.Flow

/**
 * Where the `bluetoothState` feature learns the radio's state. Implemented by an adapter on the platform's
 * Bluetooth client, and by a fake in tests.
 */
interface AdapterStateSource {
  suspend fun currentState(): AdapterState

  /** Every state the system reports from now on, repeats included. */
  fun stateChanges(): Flow<AdapterState>
}
