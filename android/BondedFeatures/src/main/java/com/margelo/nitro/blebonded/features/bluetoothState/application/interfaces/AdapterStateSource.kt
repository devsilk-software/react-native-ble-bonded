package com.margelo.nitro.blebonded.features.bluetoothState.application.interfaces

import com.margelo.nitro.blebonded.features.bluetoothState.domain.AdapterState

/**
 * Where the `bluetoothState` feature learns the radio's state. Implemented by an adapter on the platform's
 * Bluetooth client, and by a fake in tests.
 */
fun interface AdapterStateSource {
  suspend fun currentState(): AdapterState
}
