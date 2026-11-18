package com.margelo.nitro.blebonded

import com.margelo.nitro.NitroModules
import com.margelo.nitro.blebonded.compositionRoot.CompositionRoot
import com.margelo.nitro.blebonded.features.bluetoothState.domain.AdapterState
import com.margelo.nitro.core.Callback
import com.margelo.nitro.core.Promise
import com.margelo.nitro.core.invoke

/**
 * The `BluetoothState` HybridObject: calls the `bluetoothState` feature's use cases and maps their results to
 * the generated Nitro types. Nothing else lives here (decision A5).
 */
class HybridBluetoothState : HybridBluetoothStateSpec() {
  companion object : HybridBluetoothStateSpec.Companion {
    private val listeners = ListenerRegistry()

    override fun getState(): Promise<BluetoothAdapterState> = Promise.async { root().getAdapterState().toSpec() }

    override fun addStateListener(onChange: Callback<(state: BluetoothAdapterState) -> Unit>): Double =
      listeners.add(root().observeAdapterState()) { state -> onChange(state.toSpec()) }

    override fun removeStateListener(id: Double) = listeners.remove(id)

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

/** The composition root, created on first use with the application context and the permission dialog. */
internal fun root(): CompositionRoot {
  val context = NitroModules.applicationContext ?: error("Bonded used before React Native started")
  return CompositionRoot.get(context, ReactPermissionPrompt)
}
