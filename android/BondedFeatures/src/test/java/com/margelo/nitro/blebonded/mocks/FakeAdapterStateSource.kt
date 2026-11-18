package com.margelo.nitro.blebonded.mocks

import com.margelo.nitro.blebonded.features.bluetoothState.application.interfaces.AdapterStateSource
import com.margelo.nitro.blebonded.features.bluetoothState.domain.AdapterState

/** An [AdapterStateSource] that reports whatever state the test sets. */
class FakeAdapterStateSource(private val state: AdapterState) : AdapterStateSource {
  override suspend fun currentState(): AdapterState = state
}
