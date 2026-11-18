package com.margelo.nitro.blebonded.features.bluetoothState.application.useCases.getAdapterState

import com.margelo.nitro.blebonded.features.bluetoothState.application.interfaces.AdapterStateSource
import com.margelo.nitro.blebonded.features.bluetoothState.domain.AdapterState

/** Reads the phone's Bluetooth radio state. */
class GetAdapterState(private val source: AdapterStateSource) {
  suspend operator fun invoke(): AdapterState = source.currentState()
}
