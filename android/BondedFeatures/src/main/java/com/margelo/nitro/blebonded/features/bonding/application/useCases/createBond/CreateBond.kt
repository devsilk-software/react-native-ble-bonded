package com.margelo.nitro.blebonded.features.bonding.application.useCases.createBond

import com.margelo.nitro.blebonded.features.bonding.application.interfaces.BondSource
import com.margelo.nitro.blebonded.features.bonding.domain.BondState
import com.margelo.nitro.blebonded.features.bonding.domain.BondTrigger
import com.margelo.nitro.blebonded.shared.application.EventBus
import com.margelo.nitro.blebonded.shared.domain.BleError
import com.margelo.nitro.blebonded.shared.domain.DeviceId
import com.margelo.nitro.blebonded.shared.domain.DomainEvent

/**
 * Bonds with the device, then reads the trigger, a characteristic that needs encryption, to confirm encrypted reads
 * work, as they do on iOS once paired. Skips bonding when already bonded. Settles on Android's outcome, with a
 * 120-second backstop for a pairing that never reports.
 */
class CreateBond(private val source: BondSource, private val bus: EventBus) {
  suspend operator fun invoke(trigger: BondTrigger, id: DeviceId) {
    val isReadable =
      source.isReadable(trigger, id)
        ?: throw BleError(
          BleError.Code.CHARACTERISTIC_NOT_FOUND,
          "The device has no characteristic ${trigger.characteristic} in service ${trigger.service} to start pairing with. device.getServices() lists the ones it has.",
          deviceId = id,
          operation = "createBond",
        )
    if (!isReadable) {
      throw BleError(
        BleError.Code.UNSUPPORTED,
        "Characteristic ${trigger.characteristic} can't be read, so reading it can't confirm pairing. Give one that can be read and needs encryption.",
        deviceId = id,
        operation = "createBond",
      )
    }
    try {
      if (source.bondState(id) != BondState.BONDED && source.bond(id) != BondState.BONDED) {
        throw BleError(BleError.Code.PAIRING_REQUIRED, "Pairing was declined or failed.", deviceId = id, operation = "createBond")
      }
      source.readTrigger(trigger, id)
    } catch (error: BleError) {
      // Each outcome is recorded, as on iOS: bonded, or none with why.
      bus.publish(DomainEvent(DomainEvent.Kind.BOND_STATE_CHANGED, id, detail = BondState.NONE.publicName, errorCode = error.code, platformCode = error.platformCode))
      throw error
    }
    bus.publish(DomainEvent(DomainEvent.Kind.BOND_STATE_CHANGED, id, detail = BondState.BONDED.publicName))
  }
}
