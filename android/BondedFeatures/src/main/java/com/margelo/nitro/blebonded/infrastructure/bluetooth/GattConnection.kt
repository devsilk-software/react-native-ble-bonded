package com.margelo.nitro.blebonded.infrastructure.bluetooth

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * One device's GATT client. It knows Android's GATT API and nothing about Bonded's features (decision A2).
 * Android calls [BluetoothGattCallback] on a binder thread; each callback completes the operation waiting for it.
 * The client is single use: after [close], connect with a new one.
 */
@Suppress("MissingPermission") // Bonded checks BLUETOOTH_CONNECT before connecting; a SecurityException is reported.
class GattConnection(private val context: Context, val device: BluetoothDevice, private val onDisconnected: (status: Int) -> Unit) {
  private val gatt = AtomicReference<BluetoothGatt?>(null)
  private val connected = AtomicReference<CompletableDeferred<Unit>?>(null)
  private val discovered = AtomicReference<CompletableDeferred<List<BluetoothGattService>>?>(null)
  private val disconnected = AtomicReference<CompletableDeferred<Unit>?>(null)
  private val readValue = AtomicReference<CompletableDeferred<ByteArray>?>(null)
  private val written = AtomicReference<CompletableDeferred<Unit>?>(null)

  /** Android allows one GATT operation at a time per device; every read and write holds this (decision A4). */
  private val operations = Mutex()

  /** The negotiated MTU; 23 until a larger one is requested. A write without response carries MTU - 3 bytes. */
  @Volatile var mtu = DEFAULT_MTU
    private set

  val isReady: Boolean get() = isConnected && !isClosed

  @Volatile private var isConnected = false

  @Volatile private var isClosed = false

  private val callback =
    object : BluetoothGattCallback() {
      override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
          isConnected = true
          connected.get()?.complete(Unit)
          return
        }
        if (newState != BluetoothProfile.STATE_DISCONNECTED) return
        val wasConnected = isConnected
        isConnected = false
        connected.getAndSet(null)?.completeExceptionally(GattStatusException(status))
        discovered.getAndSet(null)?.completeExceptionally(GattStatusException(status))
        readValue.getAndSet(null)?.completeExceptionally(GattStatusException(status))
        written.getAndSet(null)?.completeExceptionally(GattStatusException(status))
        val requested = disconnected.getAndSet(null)
        requested?.complete(Unit)
        if (wasConnected && requested == null) onDisconnected(status)
      }

      override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
        val waiting = readValue.getAndSet(null) ?: return
        if (status == BluetoothGatt.GATT_SUCCESS) waiting.complete(value) else waiting.completeExceptionally(GattStatusException(status))
      }

      @Deprecated("Before Android 13")
      override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
        @Suppress("DEPRECATION")
        onCharacteristicRead(gatt, characteristic, characteristic.value ?: ByteArray(0), status)
      }

      override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
        val waiting = written.getAndSet(null) ?: return
        if (status == BluetoothGatt.GATT_SUCCESS) waiting.complete(Unit) else waiting.completeExceptionally(GattStatusException(status))
      }

      override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
        if (status == BluetoothGatt.GATT_SUCCESS) this@GattConnection.mtu = mtu
      }

      override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        val waiting = discovered.getAndSet(null) ?: return
        if (status == BluetoothGatt.GATT_SUCCESS) waiting.complete(gatt.services.orEmpty()) else waiting.completeExceptionally(GattStatusException(status))
      }
    }

  /**
   * Connects over Low Energy and returns once connected. [autoConnect] false tries directly and fails fast when
   * the device isn't there; true waits for it to advertise. Cancelling closes the client.
   */
  suspend fun connect(autoConnect: Boolean) {
    val waiting = CompletableDeferred<Unit>()
    connected.set(waiting)
    try {
      val client =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
          device.connectGatt(context, autoConnect, callback, BluetoothDevice.TRANSPORT_LE)
        } else {
          device.connectGatt(context, autoConnect, callback)
        } ?: throw GattStatusException(BluetoothGatt.GATT_FAILURE)
      gatt.set(client)
      waiting.await()
    } catch (failure: Throwable) {
      close()
      throw failure
    }
  }

  /** Discovers every service and returns them. */
  suspend fun discoverServices(): List<BluetoothGattService> {
    val client = gatt.get() ?: throw GattStatusException(BluetoothGatt.GATT_FAILURE)
    val waiting = CompletableDeferred<List<BluetoothGattService>>()
    discovered.set(waiting)
    if (!client.discoverServices()) {
      discovered.set(null)
      throw GattStatusException(BluetoothGatt.GATT_FAILURE)
    }
    return waiting.await()
  }

  /** The characteristic, from the services discovered at connect; null when the device has none such. */
  fun characteristic(service: String, characteristic: String): BluetoothGattCharacteristic? =
    gatt.get()?.getService(UUID.fromString(service))?.getCharacteristic(UUID.fromString(characteristic))

  /** Reads the characteristic's value. */
  suspend fun read(characteristic: BluetoothGattCharacteristic): ByteArray =
    operations.withLock {
      val client = gatt.get() ?: throw GattStatusException(NOT_CONNECTED)
      val waiting = CompletableDeferred<ByteArray>()
      readValue.set(waiting)
      if (!client.readCharacteristic(characteristic)) {
        readValue.set(null)
        throw GattStatusException(BluetoothGatt.GATT_FAILURE)
      }
      waiting.awaitOrClear(readValue)
    }

  /**
   * Writes [value]. With response, returns once the device confirmed; without, once Android has taken it into its
   * buffer, which is Android's flow control: the next write waits for room. Android splits a long value with
   * response itself.
   */
  suspend fun write(characteristic: BluetoothGattCharacteristic, value: ByteArray, withResponse: Boolean) {
    operations.withLock {
      val client = gatt.get() ?: throw GattStatusException(NOT_CONNECTED)
      val type = if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
      val waiting = CompletableDeferred<Unit>()
      written.set(waiting)
      repeat(BUSY_RETRIES) { attempt ->
        val result =
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            client.writeCharacteristic(characteristic, value, type)
          } else {
            @Suppress("DEPRECATION")
            characteristic.writeType = type
            @Suppress("DEPRECATION")
            characteristic.value = value
            @Suppress("DEPRECATION")
            if (client.writeCharacteristic(characteristic)) BluetoothStatusCodes.SUCCESS else BluetoothGatt.GATT_FAILURE
          }
        when {
          result == BluetoothStatusCodes.SUCCESS -> return@withLock waiting.awaitOrClear(written)
          result == BUSY && attempt < BUSY_RETRIES - 1 -> delay(BUSY_DELAY_MS)
          else -> {
            written.set(null)
            throw GattStatusException(if (result == BUSY) BluetoothGatt.GATT_FAILURE else result)
          }
        }
      }
    }
  }

  /** Waits for [this]; when the caller stops waiting (a timeout), clears it so a late callback finds nothing. */
  private suspend fun <T> CompletableDeferred<T>.awaitOrClear(slot: AtomicReference<CompletableDeferred<T>?>): T =
    try {
      await()
    } finally {
      slot.compareAndSet(this, null)
    }

  /** High priority speeds up setup at the cost of battery; balanced is Android's default. */
  fun requestPriority(isHigh: Boolean) {
    gatt.get()?.requestConnectionPriority(if (isHigh) BluetoothGatt.CONNECTION_PRIORITY_HIGH else BluetoothGatt.CONNECTION_PRIORITY_BALANCED)
  }

  /**
   * Asks Android to forget its cached services for this device, so the next discovery reads them fresh. A hidden
   * API, called by reflection; returns false where it isn't available.
   */
  fun refreshServiceCache(): Boolean =
    runCatching {
      val client = gatt.get() ?: return false
      client.javaClass.getMethod("refresh").invoke(client) as Boolean
    }.getOrDefault(false)

  /** Waits while the device is pairing, for at most [timeoutMs]. */
  suspend fun awaitBondingFinished(timeoutMs: Long) {
    if (device.bondState != BluetoothDevice.BOND_BONDING) return
    withTimeoutOrNull(timeoutMs) { bondStates().first { it != BluetoothDevice.BOND_BONDING } }
  }

  /** Disconnects and returns once Android confirms or after two seconds; closes the client unless told not to. */
  suspend fun disconnect(close: Boolean = true) {
    val client = gatt.get()
    if (client != null && isConnected) {
      val waiting = CompletableDeferred<Unit>()
      disconnected.set(waiting)
      client.disconnect()
      withTimeoutOrNull(2_000) { waiting.await() }
    }
    if (close) close()
  }

  /** Releases the client. Android allows only a few at a time; a leaked one causes later failures (status 133). */
  fun close() {
    if (isClosed) return
    isClosed = true
    isConnected = false
    gatt.getAndSet(null)?.let { client ->
      runCatching { client.disconnect() }
      runCatching { client.close() }
    }
    val cancelled = CancellationException("closed")
    connected.getAndSet(null)?.completeExceptionally(cancelled)
    discovered.getAndSet(null)?.completeExceptionally(cancelled)
    readValue.getAndSet(null)?.completeExceptionally(GattStatusException(NOT_CONNECTED))
    written.getAndSet(null)?.completeExceptionally(GattStatusException(NOT_CONNECTED))
    disconnected.getAndSet(null)?.complete(Unit)
  }

  private fun bondStates() =
    callbackFlow {
      val receiver =
        object : BroadcastReceiver() {
          override fun onReceive(context: Context, intent: Intent) {
            val changed: BluetoothDevice? =
              if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
              } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
              }
            if (changed?.address == device.address) trySend(intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR))
          }
        }
      val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
      } else {
        context.registerReceiver(receiver, filter)
      }
      awaitClose { context.unregisterReceiver(receiver) }
    }.onStart { emit(device.bondState) }
}

/** A GATT operation failed; [status] is Android's GATT status, such as 133, or [NOT_CONNECTED]. */
class GattStatusException(val status: Int) : Exception("GATT status $status")

private const val DEFAULT_MTU = 23

/** Not a GATT status: the client was closed or the device disconnected during the operation. */
const val NOT_CONNECTED = -1

/** `BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY`, from Android 13. */
private const val BUSY = 201
private const val BUSY_RETRIES = 3
private const val BUSY_DELAY_MS = 20L
