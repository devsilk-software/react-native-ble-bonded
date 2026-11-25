package com.margelo.nitro.blebonded.features.bonding.domain

import com.margelo.nitro.blebonded.shared.domain.CharacteristicUuid
import com.margelo.nitro.blebonded.shared.domain.ServiceUuid

/** The characteristic whose read confirms the bond: one that needs an encrypted connection. */
data class BondTrigger(val service: ServiceUuid, val characteristic: CharacteristicUuid)

/** Whether Android has a bond with the device: paired and keys stored. */
enum class BondState(val publicName: String) {
  BONDED("bonded"),
  BONDING("bonding"),
  NONE("none"),
}
