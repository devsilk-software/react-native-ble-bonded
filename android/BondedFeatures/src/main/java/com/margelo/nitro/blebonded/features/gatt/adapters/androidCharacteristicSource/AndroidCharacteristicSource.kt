package com.margelo.nitro.blebonded.features.gatt.adapters.androidCharacteristicSource

import android.bluetooth.BluetoothGattCharacteristic
import com.margelo.nitro.blebonded.features.gatt.application.interfaces.CharacteristicSource
import com.margelo.nitro.blebonded.features.gatt.domain.CharacteristicAddress
import com.margelo.nitro.blebonded.features.gatt.domain.CharacteristicOperation
import com.margelo.nitro.blebonded.features.gatt.domain.DeviceRefusal
import com.margelo.nitro.blebonded.infrastructure.bluetooth.GattClients
import com.margelo.nitro.blebonded.infrastructure.bluetooth.GattConnection
import com.margelo.nitro.blebonded.infrastructure.bluetooth.GattRequestRefusedException
import com.margelo.nitro.blebonded.infrastructure.bluetooth.GattStatusException
import com.margelo.nitro.blebonded.infrastructure.bluetooth.NOT_CONNECTED
import com.margelo.nitro.blebonded.infrastructure.bluetooth.NO_ANSWER
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
    } catch (failure: GattRequestRefusedException) {
      throw refused(failure.code, CharacteristicOperation.READ, id)
    } catch (failure: SecurityException) {
      throw refused(MISSING_PERMISSION, CharacteristicOperation.READ, id)
    }
  }

  override suspend fun write(value: ByteArray, address: CharacteristicAddress, id: DeviceId, withResponse: Boolean) {
    val characteristic = find(address, id) ?: throw notConnected(id)
    val operation = if (withResponse) CharacteristicOperation.WRITE else CharacteristicOperation.WRITE_WITHOUT_RESPONSE
    try {
      connection(id).write(characteristic, value, withResponse)
    } catch (failure: GattStatusException) {
      throw bleError(failure.status, operation, address, id)
    } catch (failure: GattRequestRefusedException) {
      throw refused(failure.code, operation, id)
    } catch (failure: SecurityException) {
      throw refused(MISSING_PERMISSION, operation, id)
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

    /**
     * Android's own GATT statuses, 0x80 to 0x8F, which share their numbers with the start of the firmware range: on
     * Android those numbers mean Android, not the device.
     */
    private val androidStatuses =
      mapOf(
        0x80 to "noResources",
        0x81 to "internalError",
        0x82 to "wrongState",
        0x83 to "databaseFull",
        0x84 to "busy",
        0x85 to "error",
        0x86 to "commandStarted",
        0x87 to "illegalParameter",
        0x88 to "pending",
        0x89 to "authenticationFailed",
        0x8A to "more",
        0x8B to "invalidConfiguration",
        0x8C to "serviceStarted",
        0x8D to "encryptedWithoutProtection",
        0x8E to "notEncrypted",
        0x8F to "congested",
      )

    /** Android statuses that mean the device wants a paired, encrypted connection first. */
    private val androidPairing = setOf(0x89, 0x8D, 0x8E)

    /** `BluetoothStatusCodes` values Android 13+ returns when it refuses to send a request. */
    private const val BLUETOOTH_NOT_ENABLED = 1
    private const val MISSING_PERMISSION = 6

    /**
     * Android's GATT status in Bonded's words: Bluetooth's named errors by name, Android's own statuses (0x80–0x8F)
     * by theirs, the firmware range 0x90–0x9F as the device's own errors, a disconnect as `notConnected`.
     */
    fun bleError(status: Int, operation: CharacteristicOperation, address: CharacteristicAddress, id: DeviceId): BleError {
      val what = "the ${operation.publicName} of characteristic ${address.characteristic}"
      return when {
        status == NOT_CONNECTED ->
          BleError(BleError.Code.NOT_CONNECTED, "The device disconnected during ${what}", deviceId = id, operation = operation.publicName)
        status == NO_ANSWER ->
          BleError(BleError.Code.TIMEOUT, "The device didn't answer ${what} within Bluetooth's 30 seconds", deviceId = id, operation = operation.publicName)
        DeviceRefusal.isNamed(status) || status in 0x90..0x9F -> DeviceRefusal.error(status, operation, address, id)
        status in androidStatuses ->
          BleError(
            if (status in androidPairing) BleError.Code.PAIRING_REQUIRED else BleError.Code.GATT_ERROR,
            "Android failed ${what}: ${androidStatuses.getValue(status)} (GATT status $status)" +
              if (status in androidPairing) "; the characteristic needs a paired, encrypted connection." else ".",
            status,
            id,
            operation.publicName,
            attError = androidStatuses.getValue(status),
          )
        else -> BleError(BleError.Code.GATT_ERROR, "Android failed ${what} (GATT status $status).", status, id, operation.publicName)
      }
    }

    /** Android 13+ refused to send the request at all, with a `BluetoothStatusCodes` value. */
    fun refused(code: Int, operation: CharacteristicOperation, id: DeviceId): BleError =
      when (code) {
        MISSING_PERMISSION ->
          BleError(BleError.Code.UNAUTHORIZED, "Bluetooth permission is not granted, so Android refused the ${operation.publicName}", code, id, operation.publicName)
        BLUETOOTH_NOT_ENABLED -> BleError(BleError.Code.BLUETOOTH_OFF, "Bluetooth is off, so Android refused the ${operation.publicName}", code, id, operation.publicName)
        else -> BleError(BleError.Code.GATT_ERROR, "Android refused to send the ${operation.publicName} (status code $code).", code, id, operation.publicName)
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
