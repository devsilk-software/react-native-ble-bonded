package com.margelo.nitro.blebonded.features.connection.adapters.androidConnectionSource

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.content.Context
import com.margelo.nitro.blebonded.features.connection.application.interfaces.ConnectionSource
import com.margelo.nitro.blebonded.features.connection.application.interfaces.PlatformDisconnect
import com.margelo.nitro.blebonded.features.connection.domain.AndroidConnectionOptions
import com.margelo.nitro.blebonded.features.connection.domain.CharacteristicProperty
import com.margelo.nitro.blebonded.features.connection.domain.ConnectMode
import com.margelo.nitro.blebonded.features.connection.domain.ConnectionReadiness
import com.margelo.nitro.blebonded.features.connection.domain.Disconnect
import com.margelo.nitro.blebonded.features.connection.domain.GattCharacteristic
import com.margelo.nitro.blebonded.features.connection.domain.GattService
import com.margelo.nitro.blebonded.features.connection.domain.ServiceCacheRefresh
import com.margelo.nitro.blebonded.features.connection.domain.SetupStep
import com.margelo.nitro.blebonded.features.connection.domain.disconnectReason
import com.margelo.nitro.blebonded.features.connection.domain.isTransientStatus
import com.margelo.nitro.blebonded.features.connection.domain.setupSteps
import com.margelo.nitro.blebonded.infrastructure.bluetooth.BluetoothRadio
import com.margelo.nitro.blebonded.infrastructure.bluetooth.GattConnection
import com.margelo.nitro.blebonded.infrastructure.bluetooth.GattStatusException
import com.margelo.nitro.blebonded.shared.domain.BleError
import com.margelo.nitro.blebonded.shared.domain.CharacteristicUuid
import com.margelo.nitro.blebonded.shared.domain.DeviceId
import com.margelo.nitro.blebonded.shared.domain.ServiceUuid
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Answers [ConnectionSource] with Android's GATT client, through the shared Bluetooth client, and applies the
 * connection workarounds: closing the client on every exit, waiting for pairing, pausing before discovery on
 * bonded devices, high priority during setup, and refreshing the service cache.
 */
class AndroidConnectionSource(private val context: Context, private val radio: BluetoothRadio) : ConnectionSource {
  private val connections = ConcurrentHashMap<DeviceId, GattConnection>()
  private val drops = MutableSharedFlow<PlatformDisconnect>(extraBufferCapacity = 16)

  override fun readiness(): ConnectionReadiness {
    val state = radio.radioState()
    return when {
      !radio.hasLowEnergy() || state == null -> ConnectionReadiness.UNSUPPORTED
      !radio.mayConnect() -> ConnectionReadiness.UNAUTHORIZED
      state != BluetoothAdapter.STATE_ON -> ConnectionReadiness.BLUETOOTH_OFF
      else -> ConnectionReadiness.READY
    }
  }

  override suspend fun attempt(id: DeviceId, mode: ConnectMode, options: AndroidConnectionOptions): List<GattService> {
    val device =
      radio.remoteDevice(id.rawValue)
        ?: throw BleError(BleError.Code.DEVICE_NOT_FOUND, "Not a Bluetooth address: ${id.rawValue}", deviceId = id, operation = "connect")
    connections.remove(id)?.close()
    val connection = GattConnection(context, device) { status -> dropped(id, status, options) }
    connections[id] = connection
    try {
      connection.connect(autoConnect = mode == ConnectMode.WAITING)
      val isBonding = device.bondState == BluetoothDevice.BOND_BONDING
      val isBonded = device.bondState == BluetoothDevice.BOND_BONDED
      var services: List<BluetoothGattService> = emptyList()
      for (step in setupSteps(options, isBonded, isBonding)) {
        when (step) {
          SetupStep.HighPriority -> connection.requestPriority(isHigh = true)
          SetupStep.WaitForBonding -> connection.awaitBondingFinished(BONDING_TIMEOUT_MS)
          is SetupStep.Pause -> delay(step.ms)
          SetupStep.RefreshServiceCache -> connection.refreshServiceCache()
          SetupStep.DiscoverServices -> services = discover(connection, options)
          SetupStep.BalancedPriority -> connection.requestPriority(isHigh = false)
        }
      }
      return services.map(::service)
    } catch (failure: GattStatusException) {
      connections.remove(id, connection)
      connection.close()
      throw BleError(BleError.Code.CONNECTION_FAILED, "Couldn't connect (GATT status ${failure.status})", failure.status, id, "connect")
    } catch (failure: SecurityException) {
      connections.remove(id, connection)
      connection.close()
      throw BleError(BleError.Code.UNAUTHORIZED, "Bluetooth permission is not granted", deviceId = id, operation = "connect")
    } catch (failure: Throwable) {
      connections.remove(id, connection)
      connection.close()
      throw failure
    }
  }

  /** Discovers services; when that fails and the workarounds allow it, refreshes Android's cache and tries once more. */
  private suspend fun discover(connection: GattConnection, options: AndroidConnectionOptions): List<BluetoothGattService> =
    try {
      connection.discoverServices()
    } catch (failure: GattStatusException) {
      if (options.refreshServiceCache != ServiceCacheRefresh.ON_FAILURE || !connection.refreshServiceCache()) throw failure
      connection.discoverServices()
    }

  override suspend fun disconnect(id: DeviceId) {
    connections.remove(id)?.disconnect()
  }

  override fun disconnections(): Flow<PlatformDisconnect> = drops

  override fun isTransient(error: BleError, options: AndroidConnectionOptions): Boolean =
    options.retryTransientErrors && error.code == BleError.Code.CONNECTION_FAILED && error.platformCode?.let(::isTransientStatus) == true

  override fun name(id: DeviceId): String? =
    runCatching { connections[id]?.device?.name ?: radio.remoteDevice(id.rawValue)?.name }.getOrNull() // needs BLUETOOTH_CONNECT

  private fun dropped(id: DeviceId, status: Int, options: AndroidConnectionOptions) {
    val isBluetoothOff = radio.radioState() != BluetoothAdapter.STATE_ON
    if (options.closeGattOnDisconnect) connections.remove(id)?.close()
    val error = BleError(BleError.Code.DISCONNECTED, "Disconnected (GATT status $status)", status, id)
    drops.tryEmit(PlatformDisconnect(id, Disconnect(disconnectReason(status, isBluetoothOff), error)))
  }

  companion object {
    private const val BONDING_TIMEOUT_MS = 30_000L

    fun service(service: BluetoothGattService): GattService =
      GattService(
        ServiceUuid.parse(service.uuid.toString())!!,
        service.characteristics.mapNotNull { characteristic ->
          CharacteristicUuid.parse(characteristic.uuid.toString())?.let { GattCharacteristic(it, properties(characteristic.properties)) }
        },
      )

    /** Android's property bits in Bonded's words. */
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
