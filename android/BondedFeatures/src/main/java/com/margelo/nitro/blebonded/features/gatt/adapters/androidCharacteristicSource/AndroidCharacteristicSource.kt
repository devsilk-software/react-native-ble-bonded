package com.margelo.nitro.blebonded.features.gatt.adapters.androidCharacteristicSource

import android.bluetooth.BluetoothGattCharacteristic
import com.margelo.nitro.blebonded.features.gatt.application.interfaces.CharacteristicSource
import com.margelo.nitro.blebonded.features.gatt.domain.CharacteristicAddress
import com.margelo.nitro.blebonded.features.gatt.domain.CharacteristicOperation
import com.margelo.nitro.blebonded.features.gatt.domain.DeviceRefusal
import com.margelo.nitro.blebonded.infrastructure.bluetooth.GattClients
import com.margelo.nitro.blebonded.infrastructure.bluetooth.GattConnection
import com.margelo.nitro.blebonded.infrastructure.bluetooth.GattStatusException
import com.margelo.nitro.blebonded.infrastructure.bluetooth.NOT_CONNECTED
import com.margelo.nitro.blebonded.shared.domain.BleError
import com.margelo.nitro.blebonded.shared.domain.CharacteristicProperty
import com.margelo.nitro.blebonded.shared.domain.DeviceId

/** Answers [CharacteristicSource] with Android's GATT client, through the shared Bluetooth client. */
class AndroidCharacteristicSource(private val clients: GattClients) : CharacteristicSource {
  override suspend fun properties(address: CharacteristicAddress, id: DeviceId): List<CharacteristicProperty>? =
    find(address, id)?.let { properties(it.properties) }

  override suspend fun maxWriteLength(id: DeviceId): Int = connection(id).mtu - ATT_HEADER

  override suspend fun read(address: CharacteristicAddress, id: DeviceId): ByteArray {
    val characteristic = find(address, id) ?: throw notConnected(id)
    return try {
      connection(id).read(characteristic)
    } catch (failure: GattStatusException) {
      throw bleError(failure.status, CharacteristicOperation.READ, address, id)
    }
  }

  override suspend fun write(value: ByteArray, address: CharacteristicAddress, id: DeviceId, withResponse: Boolean) {
    val characteristic = find(address, id) ?: throw notConnected(id)
    val operation = if (withResponse) CharacteristicOperation.WRITE else CharacteristicOperation.WRITE_WITHOUT_RESPONSE
    try {
      connection(id).write(characteristic, value, withResponse)
    } catch (failure: GattStatusException) {
      throw bleError(failure.status, operation, address, id)
    }
  }

  private fun connection(id: DeviceId): GattConnection =
    clients[id.rawValue]?.takeIf { it.isReady } ?: throw notConnected(id)

  private fun find(address: CharacteristicAddress, id: DeviceId): BluetoothGattCharacteristic? =
    connection(id).characteristic(address.service.rawValue, address.characteristic.rawValue)

  private fun notConnected(id: DeviceId) = BleError(BleError.Code.NOT_CONNECTED, "The device isn't connected; connect it first", deviceId = id)

  companion object {
    /** ATT's header in a write: a write without response carries MTU - 3 bytes. */
    private const val ATT_HEADER = 3

    /** Android's GATT status in Bonded's words: the device's own ATT errors by name, Android's statuses as GATT errors. */
    fun bleError(status: Int, operation: CharacteristicOperation, address: CharacteristicAddress, id: DeviceId): BleError =
      when {
        status == NOT_CONNECTED ->
          BleError(BleError.Code.NOT_CONNECTED, "The device disconnected during the ${operation.publicName}", deviceId = id, operation = operation.publicName)
        DeviceRefusal.isDeviceCode(status) -> DeviceRefusal.error(status, operation, address, id)
        else ->
          BleError(
            BleError.Code.GATT_ERROR,
            "Android failed the ${operation.publicName} of characteristic ${address.characteristic} (GATT status $status)",
            status,
            id,
            operation.publicName,
          )
      }

    fun properties(bits: Int): List<CharacteristicProperty> =
      listOf(
        BluetoothGattCharacteristic.PROPERTY_BROADCAST to CharacteristicProperty.BROADCAST,
        BluetoothGattCharacteristic.PROPERTY_READ to CharacteristicProperty.READ,
        BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE to CharacteristicProperty.WRITE_WITHOUT_RESPONSE,
        BluetoothGattCharacteristic.PROPERTY_WRITE to CharacteristicProperty.WRITE,
        BluetoothGattCharacteristic.PROPERTY_NOTIFY to CharacteristicProperty.NOTIFY,
        BluetoothGattCharacteristic.PROPERTY_INDICATE to CharacteristicProperty.INDICATE,
        BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE to CharacteristicProperty.AUTHENTICATED_SIGNED_WRITES,
        BluetoothGattCharacteristic.PROPERTY_EXTENDED_PROPS to CharacteristicProperty.EXTENDED_PROPERTIES,
      ).filter { (bit, _) -> bits and bit != 0 }.map { it.second }
  }
}
