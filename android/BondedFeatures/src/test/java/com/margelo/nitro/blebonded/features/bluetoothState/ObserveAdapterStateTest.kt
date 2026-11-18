package com.margelo.nitro.blebonded.features.bluetoothState

import com.margelo.nitro.blebonded.features.bluetoothState.application.useCases.observeAdapterState.ObserveAdapterState
import com.margelo.nitro.blebonded.features.bluetoothState.domain.AdapterState
import com.margelo.nitro.blebonded.mocks.FakeAdapterStateSource
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList

class ObserveAdapterStateTest : BehaviorSpec({
  given("a radio that is off") {
    `when`("it turns on, then the permission is revoked") {
      then("reports the current state first, then each change") {
        val source =
          FakeAdapterStateSource(AdapterState.POWERED_OFF, flowOf(AdapterState.POWERED_ON, AdapterState.UNAUTHORIZED))

        ObserveAdapterState(source)().toList() shouldBe
          listOf(AdapterState.POWERED_OFF, AdapterState.POWERED_ON, AdapterState.UNAUTHORIZED)
      }
    }
  }

  given("a radio that is on") {
    `when`("the system reports the same states repeatedly") {
      then("suppresses repeated states") {
        val changes =
          flowOf(
            AdapterState.POWERED_ON,
            AdapterState.POWERED_ON,
            AdapterState.POWERED_OFF,
            AdapterState.POWERED_OFF,
            AdapterState.POWERED_ON,
          )

        ObserveAdapterState(FakeAdapterStateSource(AdapterState.POWERED_ON, changes))().toList() shouldBe
          listOf(AdapterState.POWERED_ON, AdapterState.POWERED_OFF, AdapterState.POWERED_ON)
      }
    }
  }
})
