package com.margelo.nitro.blebonded.features.bluetoothState.adapters.androidAdapterStateSource

import android.bluetooth.BluetoothAdapter
import com.margelo.nitro.blebonded.features.bluetoothState.application.interfaces.AdapterStateSource
import com.margelo.nitro.blebonded.features.bluetoothState.domain.AdapterState
import com.margelo.nitro.blebonded.infrastructure.bluetooth.BluetoothRadio

/** Answers [AdapterStateSource] with Android's Bluetooth adapter, through the shared Bluetooth client. */
class AndroidAdapterStateSource(private val radio: BluetoothRadio) : AdapterStateSource {
  override suspend fun currentState(): AdapterState =
    adapterState(
      hasLowEnergy = radio.hasLowEnergy(),
      mayConnect = radio.mayConnect(),
      radioState = radio.radioState(),
    )

  companion object {
    /**
     * Android's view of the radio in Bonded's words. [radioState] is a `BluetoothAdapter.STATE_*` value, or
     * null when the phone has no Bluetooth adapter. A radio that is turning on or off isn't ready yet, so it
     * counts as off.
     */
    fun adapterState(hasLowEnergy: Boolean, mayConnect: Boolean, radioState: Int?): AdapterState {
      if (!hasLowEnergy || radioState == null) return AdapterState.UNSUPPORTED
      if (!mayConnect) return AdapterState.UNAUTHORIZED
      return when (radioState) {
        BluetoothAdapter.STATE_ON -> AdapterState.POWERED_ON
        BluetoothAdapter.STATE_OFF,
        BluetoothAdapter.STATE_TURNING_ON,
        BluetoothAdapter.STATE_TURNING_OFF -> AdapterState.POWERED_OFF
        else -> AdapterState.UNKNOWN
      }
    }
  }
}
