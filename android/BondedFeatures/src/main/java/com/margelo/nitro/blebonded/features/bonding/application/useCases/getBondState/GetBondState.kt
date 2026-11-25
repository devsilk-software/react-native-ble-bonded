package com.margelo.nitro.blebonded.features.bonding.application.useCases.getBondState

import com.margelo.nitro.blebonded.features.bonding.application.interfaces.BondSource
import com.margelo.nitro.blebonded.features.bonding.domain.BondState
import com.margelo.nitro.blebonded.shared.domain.DeviceId

/** Android's bond state for the device. */
class GetBondState(private val source: BondSource) {
  operator fun invoke(id: DeviceId): BondState = source.bondState(id)
}
