package com.margelo.nitro.blebonded.mocks

import com.margelo.nitro.blebonded.features.bonding.application.interfaces.BondSource
import com.margelo.nitro.blebonded.features.bonding.domain.BondState
import com.margelo.nitro.blebonded.features.bonding.domain.BondTrigger
import com.margelo.nitro.blebonded.shared.domain.BleError
import com.margelo.nitro.blebonded.shared.domain.CharacteristicUuid
import com.margelo.nitro.blebonded.shared.domain.DeviceId
import com.margelo.nitro.blebonded.shared.domain.ServiceUuid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.atomic.AtomicInteger

/** A [BondSource] whose bond and trigger the test describes; bonding ends as [bondOutcome] says. */
class FakeBondSource : BondSource {
  /** Null when the device has no such characteristic. */
  @Volatile var isTriggerReadable: Boolean? = true

  @Volatile var bondOutcome = BondState.BONDED

  @Volatile var triggerRefusal: BleError? = null
  val state = MutableStateFlow(BondState.NONE)
  val bondAttempts = AtomicInteger()
  val triggerReads = AtomicInteger()

  override suspend fun isReadable(trigger: BondTrigger, id: DeviceId): Boolean? = isTriggerReadable

  override fun bondState(id: DeviceId): BondState = state.value

  override suspend fun bond(id: DeviceId): BondState {
    bondAttempts.incrementAndGet()
    state.value = BondState.BONDING
    state.value = bondOutcome
    return bondOutcome
  }

  override suspend fun readTrigger(trigger: BondTrigger, id: DeviceId) {
    triggerReads.incrementAndGet()
    triggerRefusal?.let { throw it }
  }

  override fun bondStates(id: DeviceId): Flow<BondState> = state

  override suspend fun removeBond(id: DeviceId) {
    state.value = BondState.NONE
  }
}

val testTrigger = BondTrigger(ServiceUuid.parse("B0ED0001-0000-4000-8000-00000000B0ED")!!, CharacteristicUuid.parse("B0ED0006-0000-4000-8000-00000000B0ED")!!)
