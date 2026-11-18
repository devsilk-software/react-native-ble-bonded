package com.margelo.nitro.blebonded.mocks

import com.margelo.nitro.blebonded.features.bluetoothState.application.interfaces.AdapterStateSource
import com.margelo.nitro.blebonded.features.bluetoothState.domain.AdapterState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** An [AdapterStateSource] that reports whatever state the test sets, then the changes it lists. */
class FakeAdapterStateSource(
  private val state: AdapterState,
  private val changes: Flow<AdapterState> = emptyFlow(),
) : AdapterStateSource {
  override suspend fun currentState(): AdapterState = state

  override fun stateChanges(): Flow<AdapterState> = changes
}
