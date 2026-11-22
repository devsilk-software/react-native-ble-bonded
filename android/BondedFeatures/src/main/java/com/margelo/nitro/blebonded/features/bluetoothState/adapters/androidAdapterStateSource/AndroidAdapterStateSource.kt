package com.margelo.nitro.blebonded.features.bluetoothState.adapters.androidAdapterStateSource

import android.bluetooth.BluetoothAdapter
import com.margelo.nitro.blebonded.features.bluetoothState.application.interfaces.AdapterStateSource
import com.margelo.nitro.blebonded.features.bluetoothState.domain.AdapterState
import com.margelo.nitro.blebonded.infrastructure.bluetooth.BluetoothEnablePrompt
import com.margelo.nitro.blebonded.infrastructure.bluetooth.BluetoothRadio
import com.margelo.nitro.blebonded.infrastructure.lifecycle.AppForeground
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withTimeoutOrNull

/** Answers [AdapterStateSource] with Android's Bluetooth adapter, through the shared Bluetooth client. */
class AndroidAdapterStateSource(
  private val radio: BluetoothRadio,
  private val foreground: AppForeground,
  private val enablePrompt: BluetoothEnablePrompt,
) : AdapterStateSource {
  override suspend fun currentState(): AdapterState = readState()

  /**
   * Shows the system's "Turn on Bluetooth?" dialog when Bluetooth is off, and waits briefly for the radio to
   * finish turning on after the user agrees. Showing the dialog needs the connect permission from Android 12, so
   * without it this reports `UNAUTHORIZED` without asking.
   */
  override suspend fun requestEnable(): AdapterState {
    if (readState() != AdapterState.POWERED_OFF) return readState()
    if (enablePrompt.requestEnable() && radio.radioState() != BluetoothAdapter.STATE_ON) {
      withTimeoutOrNull(ENABLE_TIMEOUT_MS) { radio.radioStateChanges().first { it == BluetoothAdapter.STATE_ON } }
    }
    return readState()
  }

  /**
   * The radio's broadcasts, plus a check each time the app returns to the foreground: the user may have granted
   * the connect permission in Settings, which ends `UNAUTHORIZED` without any broadcast.
   */
  override fun stateChanges(): Flow<AdapterState> = merge(radio.radioStateChanges(), foreground.resumes()).map { readState() }

  private fun readState(): AdapterState =
    adapterState(hasLowEnergy = radio.hasLowEnergy(), mayConnect = radio.mayConnect(), radioState = radio.radioState())

  companion object {
    private const val ENABLE_TIMEOUT_MS = 5_000L

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
