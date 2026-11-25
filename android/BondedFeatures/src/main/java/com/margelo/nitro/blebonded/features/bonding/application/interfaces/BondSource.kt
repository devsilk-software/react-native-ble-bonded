package com.margelo.nitro.blebonded.features.bonding.application.interfaces

import com.margelo.nitro.blebonded.features.bonding.domain.BondState
import com.margelo.nitro.blebonded.features.bonding.domain.BondTrigger
import com.margelo.nitro.blebonded.shared.domain.DeviceId
import kotlinx.coroutines.flow.Flow

/** The platform's bonds, in Bonded's words. Implemented with Android's Bluetooth APIs, and by a fake in tests. */
interface BondSource {
  /** Whether the trigger can be read; null when the device has no such characteristic. Throws `notConnected`. */
  suspend fun isReadable(trigger: BondTrigger, id: DeviceId): Boolean?

  fun bondState(id: DeviceId): BondState

  /**
   * Starts bonding, unless it's under way, and returns how it ended: [BondState.BONDED], or [BondState.NONE] when the
   * user declined or pairing failed. Throws a `BleError` when Android refuses to start.
   */
  suspend fun bond(id: DeviceId): BondState

  /** Reads the trigger, to confirm encrypted reads work. Throws `pairingRequired` when the device still refuses. */
  suspend fun readTrigger(trigger: BondTrigger, id: DeviceId)

  /** The bond state now, then every change. */
  fun bondStates(id: DeviceId): Flow<BondState>

  /** Removes the bond, best-effort, and returns once Android reports none. Throws `unsupported` where Android doesn't allow it. */
  suspend fun removeBond(id: DeviceId)
}
