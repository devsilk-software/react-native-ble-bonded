package com.margelo.nitro.blebonded.features.bluetoothState.application.useCases.observeAdapterState

import com.margelo.nitro.blebonded.features.bluetoothState.application.interfaces.AdapterStateSource
import com.margelo.nitro.blebonded.features.bluetoothState.domain.AdapterState
import com.margelo.nitro.blebonded.shared.application.currentThenChanges
import kotlinx.coroutines.flow.Flow

/** Follows the phone's Bluetooth radio: the current state at once, then each change, repeats suppressed. */
class ObserveAdapterState(private val source: AdapterStateSource) {
  operator fun invoke(): Flow<AdapterState> = currentThenChanges({ source.currentState() }, source.stateChanges())
}
