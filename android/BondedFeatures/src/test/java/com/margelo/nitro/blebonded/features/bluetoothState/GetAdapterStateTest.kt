package com.margelo.nitro.blebonded.features.bluetoothState

import android.bluetooth.BluetoothAdapter
import com.margelo.nitro.blebonded.features.bluetoothState.adapters.androidAdapterStateSource.AndroidAdapterStateSource
import com.margelo.nitro.blebonded.features.bluetoothState.application.useCases.getAdapterState.GetAdapterState
import com.margelo.nitro.blebonded.features.bluetoothState.domain.AdapterState
import com.margelo.nitro.blebonded.mocks.FakeAdapterStateSource
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class GetAdapterStateTest : BehaviorSpec({
  given("a radio in any state") {
    AdapterState.entries.forEach { state ->
      `when`("the radio is $state") {
        then("reports the radio's current state") {
          GetAdapterState(FakeAdapterStateSource(state))() shouldBe state
        }
      }
    }
  }

  given("Android's view of the radio") {
    val cases =
      listOf(
        Triple("no Bluetooth Low Energy", AndroidAdapterStateSource.adapterState(false, true, BluetoothAdapter.STATE_ON), AdapterState.UNSUPPORTED),
        Triple("no adapter", AndroidAdapterStateSource.adapterState(true, true, null), AdapterState.UNSUPPORTED),
        Triple("no connect permission", AndroidAdapterStateSource.adapterState(true, false, BluetoothAdapter.STATE_ON), AdapterState.UNAUTHORIZED),
        Triple("on", AndroidAdapterStateSource.adapterState(true, true, BluetoothAdapter.STATE_ON), AdapterState.POWERED_ON),
        Triple("off", AndroidAdapterStateSource.adapterState(true, true, BluetoothAdapter.STATE_OFF), AdapterState.POWERED_OFF),
        Triple("turning on", AndroidAdapterStateSource.adapterState(true, true, BluetoothAdapter.STATE_TURNING_ON), AdapterState.POWERED_OFF),
        Triple("turning off", AndroidAdapterStateSource.adapterState(true, true, BluetoothAdapter.STATE_TURNING_OFF), AdapterState.POWERED_OFF),
        Triple("an unknown value", AndroidAdapterStateSource.adapterState(true, true, -1), AdapterState.UNKNOWN),
      )
    cases.forEach { (name, actual, expected) ->
      `when`("the radio reports $name") {
        then("maps every platform state to an adapter state") {
          actual shouldBe expected
        }
      }
    }
  }
})
