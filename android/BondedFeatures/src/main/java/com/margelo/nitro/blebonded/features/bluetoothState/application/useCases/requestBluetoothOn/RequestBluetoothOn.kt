package com.margelo.nitro.blebonded.features.bluetoothState.application.useCases.requestBluetoothOn

import com.margelo.nitro.blebonded.features.bluetoothState.application.interfaces.AdapterStateSource
import com.margelo.nitro.blebonded.features.bluetoothState.domain.AdapterState

/** Asks the user to turn Bluetooth on where the platform allows it, and returns the resulting state. */
class RequestBluetoothOn(private val source: AdapterStateSource) {
  suspend operator fun invoke(): AdapterState = source.requestEnable()
}
