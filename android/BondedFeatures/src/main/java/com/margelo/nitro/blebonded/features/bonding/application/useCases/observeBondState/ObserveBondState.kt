package com.margelo.nitro.blebonded.features.bonding.application.useCases.observeBondState

import com.margelo.nitro.blebonded.features.bonding.application.interfaces.BondSource
import com.margelo.nitro.blebonded.features.bonding.domain.BondState
import com.margelo.nitro.blebonded.shared.domain.DeviceId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Android's bond state for the device now, then every change, each once. */
class ObserveBondState(private val source: BondSource) {
  operator fun invoke(id: DeviceId): Flow<BondState> = source.bondStates(id).distinctUntilChanged()
}
