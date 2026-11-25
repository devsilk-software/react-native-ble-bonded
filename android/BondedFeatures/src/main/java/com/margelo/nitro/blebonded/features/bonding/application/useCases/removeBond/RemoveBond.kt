package com.margelo.nitro.blebonded.features.bonding.application.useCases.removeBond

import com.margelo.nitro.blebonded.features.bonding.application.interfaces.BondSource
import com.margelo.nitro.blebonded.features.bonding.domain.BondState
import com.margelo.nitro.blebonded.shared.application.EventBus
import com.margelo.nitro.blebonded.shared.domain.DeviceId
import com.margelo.nitro.blebonded.shared.domain.DomainEvent

/** Removes Android's bond with the device, best-effort; does nothing when there's none. */
class RemoveBond(private val source: BondSource, private val bus: EventBus) {
  suspend operator fun invoke(id: DeviceId) {
    if (source.bondState(id) == BondState.NONE) return
    source.removeBond(id)
    bus.publish(DomainEvent(DomainEvent.Kind.BOND_STATE_CHANGED, id, detail = BondState.NONE.publicName))
  }
}
