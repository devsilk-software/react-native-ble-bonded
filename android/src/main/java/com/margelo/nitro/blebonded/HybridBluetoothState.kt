package com.margelo.nitro.blebonded

import com.margelo.nitro.NitroModules
import com.margelo.nitro.blebonded.compositionRoot.CompositionRoot
import com.margelo.nitro.blebonded.features.bluetoothState.domain.AdapterState
import com.margelo.nitro.core.Promise

/**
 * The `BluetoothState` HybridObject: calls the `bluetoothState` feature's use cases and maps their results to
 * the generated Nitro types. Nothing else lives here (decision A5).
 */
class HybridBluetoothState : HybridBluetoothStateSpec() {
  companion object : HybridBluetoothStateSpec.Companion {
    override fun getState(): Promise<BluetoothAdapterState> =
      Promise.async { root().getAdapterState().toSpec() }

    private fun root(): CompositionRoot {
      val context = NitroModules.applicationContext ?: error("Bonded used before React Native started")
      return CompositionRoot.get(context)
    }

    private fun AdapterState.toSpec(): BluetoothAdapterState =
      when (this) {
        AdapterState.UNKNOWN -> BluetoothAdapterState.UNKNOWN
        AdapterState.RESETTING -> BluetoothAdapterState.RESETTING
        AdapterState.UNSUPPORTED -> BluetoothAdapterState.UNSUPPORTED
        AdapterState.UNAUTHORIZED -> BluetoothAdapterState.UNAUTHORIZED
        AdapterState.POWERED_OFF -> BluetoothAdapterState.POWEREDOFF
        AdapterState.POWERED_ON -> BluetoothAdapterState.POWEREDON
      }
  }
}
