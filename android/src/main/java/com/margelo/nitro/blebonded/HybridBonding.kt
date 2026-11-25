package com.margelo.nitro.blebonded

import com.margelo.nitro.blebonded.features.bonding.domain.BondTrigger
import com.margelo.nitro.blebonded.shared.domain.BleError
import com.margelo.nitro.blebonded.shared.domain.CharacteristicUuid
import com.margelo.nitro.blebonded.shared.domain.DeviceId
import com.margelo.nitro.blebonded.shared.domain.ServiceUuid
import com.margelo.nitro.core.Callback
import com.margelo.nitro.core.Promise
import com.margelo.nitro.core.invoke
import com.margelo.nitro.blebonded.features.bonding.domain.BondState as State

/**
 * The `Bonding` HybridObject: calls the `bonding` feature's use cases and maps between them and the generated Nitro
 * types. Nothing else lives here (decision A5).
 */
class HybridBonding : HybridBondingSpec() {
  companion object : HybridBondingSpec.Companion {
    private val listeners = ListenerRegistry()

    override fun createBond(id: String, service: String, characteristic: String): Promise<Unit> {
      val serviceUuid = ServiceUuid.parse(service) ?: throw Exception(BleError(BleError.Code.UNKNOWN, "Not a service UUID: $service", operation = "createBond").jsMessage)
      val characteristicUuid =
        CharacteristicUuid.parse(characteristic) ?: throw Exception(BleError(BleError.Code.UNKNOWN, "Not a characteristic UUID: $characteristic", operation = "createBond").jsMessage)
      return Promise.async {
        try {
          root().createBond(BondTrigger(serviceUuid, characteristicUuid), DeviceId(id))
        } catch (error: BleError) {
          throw Exception(error.jsMessage)
        }
      }
    }

    override fun getBondState(id: String): Promise<BondState> =
      Promise.async {
        try {
          root().getBondState(DeviceId(id)).toSpec()
        } catch (error: BleError) {
          throw Exception(error.jsMessage)
        }
      }

    override fun removeBond(id: String): Promise<Unit> =
      Promise.async {
        try {
          root().removeBond(DeviceId(id))
        } catch (error: BleError) {
          throw Exception(error.jsMessage)
        }
      }

    override fun addBondStateListener(id: String, onChange: Callback<(state: BondState) -> Unit>): Promise<Double> =
      Promise.async { listeners.add(root().observeBondState(DeviceId(id))) { state -> onChange(state.toSpec()) } }

    override fun removeBondStateListener(listener: Double) = listeners.remove(listener)

    private fun State.toSpec(): BondState =
      when (this) {
        State.BONDED -> BondState.BONDED
        State.BONDING -> BondState.BONDING
        State.NONE -> BondState.NONE
      }
  }
}
