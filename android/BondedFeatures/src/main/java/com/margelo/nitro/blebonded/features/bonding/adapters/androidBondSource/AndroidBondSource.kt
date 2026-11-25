package com.margelo.nitro.blebonded.features.bonding.adapters.androidBondSource

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import com.margelo.nitro.blebonded.features.bonding.application.interfaces.BondSource
import com.margelo.nitro.blebonded.features.bonding.domain.BondState
import com.margelo.nitro.blebonded.features.bonding.domain.BondTrigger
import com.margelo.nitro.blebonded.infrastructure.bluetooth.BluetoothRadio
import com.margelo.nitro.blebonded.infrastructure.bluetooth.GattClients
import com.margelo.nitro.blebonded.infrastructure.bluetooth.GattConnection
import com.margelo.nitro.blebonded.infrastructure.bluetooth.GattStatusException
import com.margelo.nitro.blebonded.infrastructure.bluetooth.NOT_CONNECTED
import com.margelo.nitro.blebonded.infrastructure.bluetooth.NO_ANSWER
import com.margelo.nitro.blebonded.shared.domain.BleError
import com.margelo.nitro.blebonded.shared.domain.DeviceId
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull

/** Answers [BondSource] with Android's Bluetooth APIs, through the shared radio and GATT clients. */
class AndroidBondSource(private val radio: BluetoothRadio, private val clients: GattClients) : BondSource {
  override suspend fun isReadable(trigger: BondTrigger, id: DeviceId): Boolean? =
    connection(id).characteristic(trigger.service.rawValue, trigger.characteristic.rawValue)?.let {
      it.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0
    }

  override fun bondState(id: DeviceId): BondState =
    try {
      state(radio.bondState(id.rawValue))
    } catch (failure: SecurityException) {
      throw unauthorized(id)
    }

  override suspend fun bond(id: DeviceId): BondState =
    try {
      coroutineScope {
        // Listening before asking, so the end of a quick pairing isn't missed.
        val ended = async(start = CoroutineStart.UNDISPATCHED) { radio.bondStates(id.rawValue).drop(1).first { it != BluetoothDevice.BOND_BONDING } }
        when (radio.bondState(id.rawValue)) {
          BluetoothDevice.BOND_BONDED -> {
            ended.cancel()
            return@coroutineScope BondState.BONDED
          }
          BluetoothDevice.BOND_BONDING -> Unit
          else ->
            if (!radio.createBond(id.rawValue)) {
              ended.cancel()
              throw BleError(BleError.Code.GATT_ERROR, "Android refused to start pairing.", deviceId = id, operation = "createBond")
            }
        }
        // Android ends pairing itself when the user doesn't answer; this only catches a pairing that never reports.
        val state = withTimeoutOrNull(BOND_BACKSTOP_MS) { ended.await() }
        if (state == null) {
          ended.cancel()
          throw BleError(BleError.Code.TIMEOUT, "Pairing didn't finish within ${BOND_BACKSTOP_MS / 1000} seconds.", deviceId = id, operation = "createBond")
        }
        state(state)
      }
    } catch (failure: SecurityException) {
      throw unauthorized(id)
    }

  override suspend fun readTrigger(trigger: BondTrigger, id: DeviceId) {
    val connection = connection(id)
    val characteristic = connection.characteristic(trigger.service.rawValue, trigger.characteristic.rawValue) ?: throw notConnected(id)
    try {
      connection.read(characteristic)
    } catch (failure: GattStatusException) {
      throw when (failure.status) {
        NOT_CONNECTED -> BleError(BleError.Code.NOT_CONNECTED, "The device disconnected during pairing", deviceId = id, operation = "createBond")
        NO_ANSWER -> BleError(BleError.Code.TIMEOUT, "The device didn't answer during pairing within Bluetooth's 30 seconds", deviceId = id, operation = "createBond")
        in ENCRYPTION_REFUSALS ->
          BleError(
            BleError.Code.PAIRING_REQUIRED,
            "Android reports a bond, but the device still refuses the encrypted read of characteristic ${trigger.characteristic} (GATT status ${failure.status}). Remove the bond and pair again.",
            failure.status,
            id,
            "createBond",
          )
        else -> BleError(BleError.Code.GATT_ERROR, "Reading characteristic ${trigger.characteristic} after pairing failed (GATT status ${failure.status}).", failure.status, id, "createBond")
      }
    } catch (failure: SecurityException) {
      throw unauthorized(id)
    }
  }

  override fun bondStates(id: DeviceId): Flow<BondState> = radio.bondStates(id.rawValue).map(::state)

  override suspend fun removeBond(id: DeviceId) {
    val isRefused =
      try {
        !radio.removeBond(id.rawValue)
      } catch (failure: SecurityException) {
        throw unauthorized(id)
      } catch (failure: ReflectiveOperationException) {
        throw BleError(
          BleError.Code.UNSUPPORTED,
          "This Android version doesn't let apps remove a bond. The user can remove the device in Settings › Bluetooth.",
          deviceId = id,
          operation = "removeBond",
        )
      }
    if (isRefused) throw BleError(BleError.Code.GATT_ERROR, "Android refused to remove the bond.", deviceId = id, operation = "removeBond")
    withTimeoutOrNull(REMOVE_WAIT_MS) { radio.bondStates(id.rawValue).first { it == BluetoothDevice.BOND_NONE } }
  }

  private fun connection(id: DeviceId): GattConnection = clients[id.rawValue]?.takeIf { it.isReady } ?: throw notConnected(id)

  private fun notConnected(id: DeviceId) = BleError(BleError.Code.NOT_CONNECTED, "The device isn't connected; connect it first", deviceId = id, operation = "createBond")

  private fun unauthorized(id: DeviceId) = BleError(BleError.Code.UNAUTHORIZED, "Bluetooth permission is not granted, so Android refused", deviceId = id)

  private fun state(state: Int): BondState =
    when (state) {
      BluetoothDevice.BOND_BONDED -> BondState.BONDED
      BluetoothDevice.BOND_BONDING -> BondState.BONDING
      else -> BondState.NONE
    }

  companion object {
    private const val BOND_BACKSTOP_MS = 120_000L
    private const val REMOVE_WAIT_MS = 5_000L

    /** GATT statuses that mean the device still wants encryption: Bluetooth's 5, 12 and 15, and Android's 0x89, 0x8D, 0x8E. */
    private val ENCRYPTION_REFUSALS = setOf(0x05, 0x0C, 0x0F, 0x89, 0x8D, 0x8E)
  }
}
