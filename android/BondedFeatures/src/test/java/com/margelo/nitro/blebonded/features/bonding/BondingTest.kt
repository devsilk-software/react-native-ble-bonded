package com.margelo.nitro.blebonded.features.bonding

import com.margelo.nitro.blebonded.features.bonding.application.useCases.createBond.CreateBond
import com.margelo.nitro.blebonded.features.bonding.application.useCases.getBondState.GetBondState
import com.margelo.nitro.blebonded.features.bonding.application.useCases.observeBondState.ObserveBondState
import com.margelo.nitro.blebonded.features.bonding.application.useCases.removeBond.RemoveBond
import com.margelo.nitro.blebonded.features.bonding.domain.BondState
import com.margelo.nitro.blebonded.mocks.FakeBondSource
import com.margelo.nitro.blebonded.mocks.testTrigger
import com.margelo.nitro.blebonded.shared.application.EventBus
import com.margelo.nitro.blebonded.shared.domain.BleError
import com.margelo.nitro.blebonded.shared.domain.DeviceId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList

private val device = DeviceId("A")

class BondingTest : BehaviorSpec({
  given("a connected device") {
    then("pairs by reading the trigger") {
      val source = FakeBondSource()
      CreateBond(source, EventBus())(testTrigger, device)
      source.triggerReads.get() shouldBe 1
    }

    then("reports a declined pairing as pairing required") {
      val source = FakeBondSource().apply { bondOutcome = BondState.NONE }
      shouldThrow<BleError> { CreateBond(source, EventBus())(testTrigger, device) }.code shouldBe BleError.Code.PAIRING_REQUIRED
      source.triggerReads.get() shouldBe 0
    }

    then("refuses a trigger the device doesn't have") {
      val source = FakeBondSource().apply { isTriggerReadable = null }
      val error = shouldThrow<BleError> { CreateBond(source, EventBus())(testTrigger, device) }
      error.code shouldBe BleError.Code.CHARACTERISTIC_NOT_FOUND
      error.message shouldContain "getServices()"
      source.bondAttempts.get() shouldBe 0
    }

    then("refuses a trigger that can't be read") {
      val source = FakeBondSource().apply { isTriggerReadable = false }
      shouldThrow<BleError> { CreateBond(source, EventBus())(testTrigger, device) }.code shouldBe BleError.Code.UNSUPPORTED
      source.bondAttempts.get() shouldBe 0
    }

    then("bonds explicitly, then reads the trigger to confirm") {
      val source = FakeBondSource()
      CreateBond(source, EventBus())(testTrigger, device)
      source.bondAttempts.get() shouldBe 1
      source.triggerReads.get() shouldBe 1
      GetBondState(source)(device) shouldBe BondState.BONDED
    }

    then("skips bonding when already bonded") {
      val source = FakeBondSource().apply { state.value = BondState.BONDED }
      CreateBond(source, EventBus())(testTrigger, device)
      source.bondAttempts.get() shouldBe 0
      source.triggerReads.get() shouldBe 1
    }

    then("reports the bond state now, then each change") {
      val source = FakeBondSource()
      val states = ObserveBondState(source)(device)
      states.first() shouldBe BondState.NONE
      source.state.value = BondState.BONDED
      states.take(1).toList() shouldBe listOf(BondState.BONDED)
    }

    then("removes the bond") {
      val source = FakeBondSource().apply { state.value = BondState.BONDED }
      RemoveBond(source, EventBus())(device)
      GetBondState(source)(device) shouldBe BondState.NONE
    }
  }
})
