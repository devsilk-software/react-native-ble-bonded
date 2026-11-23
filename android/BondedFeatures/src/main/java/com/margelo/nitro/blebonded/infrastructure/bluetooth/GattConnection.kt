package com.margelo.nitro.blebonded.infrastructure.bluetooth

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattService
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
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withTimeoutOrNull
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
        val requested = disconnected.getAndSet(null)
        requested?.complete(Unit)
        if (wasConnected && requested == null) onDisconnected(status)
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

/** A GATT operation failed; [status] is Android's GATT status, such as 133. */
class GattStatusException(val status: Int) : Exception("GATT status $status")
