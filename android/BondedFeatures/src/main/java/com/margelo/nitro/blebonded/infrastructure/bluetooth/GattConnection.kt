package com.margelo.nitro.blebonded.infrastructure.bluetooth

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
class GattConnection(
  private val context: Context,
  val device: BluetoothDevice,
  private val onNotification: (ClientNotification) -> Unit = {},
  private val onDisconnected: (status: Int) -> Unit,
) {
  private val gatt = AtomicReference<BluetoothGatt?>(null)
  private val connected = AtomicReference<CompletableDeferred<Unit>?>(null)
  private val discovered = AtomicReference<CompletableDeferred<List<BluetoothGattService>>?>(null)
  private val disconnected = AtomicReference<CompletableDeferred<Unit>?>(null)
  private val readValue = AtomicReference<Pending<ByteArray>?>(null)
  private val written = AtomicReference<Pending<Unit>?>(null)
  private val mtuAnswer = AtomicReference<CompletableDeferred<Int>?>(null)
  private val descriptorWritten = AtomicReference<Pending<Unit>?>(null)

  /** Android allows one GATT operation at a time per device; every operation holds this (decision A4). */
  private val operations = Mutex()

  /** Where operations run, so a caller that stops waiting doesn't free the device for the next one too early. */
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  /** An answer one operation waits for, from one characteristic. */
  private class Pending<T>(val uuid: UUID, val instanceId: Int) {
    val answer = CompletableDeferred<T>()

    fun isFor(characteristic: BluetoothGattCharacteristic) = characteristic.uuid == uuid && characteristic.instanceId == instanceId
  }

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
        // A disconnect isn't the device refusing the operation: the operation failed because the link went.
        readValue.getAndSet(null)?.answer?.completeExceptionally(GattStatusException(NOT_CONNECTED))
        written.getAndSet(null)?.answer?.completeExceptionally(GattStatusException(NOT_CONNECTED))
        descriptorWritten.getAndSet(null)?.answer?.completeExceptionally(GattStatusException(NOT_CONNECTED))
        mtuAnswer.getAndSet(null)?.cancel()
        val requested = disconnected.getAndSet(null)
        requested?.complete(Unit)
        if (wasConnected && requested == null) onDisconnected(status)
      }

      override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
        val pending = readValue.get()?.takeIf { it.isFor(characteristic) } ?: return
        if (!readValue.compareAndSet(pending, null)) return
        if (status == BluetoothGatt.GATT_SUCCESS) pending.answer.complete(value) else pending.answer.completeExceptionally(GattStatusException(status))
      }

      @Deprecated("Before Android 13")
      override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
        @Suppress("DEPRECATION")
        onCharacteristicRead(gatt, characteristic, characteristic.value ?: ByteArray(0), status)
      }

      override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
        val pending = written.get()?.takeIf { it.isFor(characteristic) } ?: return
        if (!written.compareAndSet(pending, null)) return
        if (status == BluetoothGatt.GATT_SUCCESS) pending.answer.complete(Unit) else pending.answer.completeExceptionally(GattStatusException(status))
      }

      override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        onNotification(
          ClientNotification(device.address, characteristic.service.uuid.toString().lowercase(), characteristic.uuid.toString().lowercase(), value),
        )
      }

      @Deprecated("Before Android 13")
      override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
        @Suppress("DEPRECATION")
        onCharacteristicChanged(gatt, characteristic, characteristic.value?.copyOf() ?: ByteArray(0))
      }

      override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
        val pending = descriptorWritten.get()?.takeIf { it.isFor(descriptor.characteristic) } ?: return
        if (!descriptorWritten.compareAndSet(pending, null)) return
        if (status == BluetoothGatt.GATT_SUCCESS) pending.answer.complete(Unit) else pending.answer.completeExceptionally(GattStatusException(status))
      }

      override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
        if (status == BluetoothGatt.GATT_SUCCESS) this@GattConnection.mtu = mtu
        mtuAnswer.getAndSet(null)?.complete(this@GattConnection.mtu)
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

  /**
   * Reads the characteristic's value. The caller can stop waiting (a timeout) at once; the device's next operation
   * still waits for this one's answer, so an answer never reaches the wrong caller.
   */
  suspend fun read(characteristic: BluetoothGattCharacteristic): ByteArray =
    perform { result ->
      val client = gatt.get() ?: throw GattStatusException(NOT_CONNECTED)
      val value =
        answer(readValue, characteristic) {
          if (!client.readCharacteristic(characteristic)) throw GattStatusException(BluetoothGatt.GATT_FAILURE)
        }
      result.complete(value)
    }

  /**
   * Writes [value]. With response, returns once the device confirmed; without, once Android has taken it into its
   * buffer, which is Android's flow control: the next write waits for room. Android splits a long value with
   * response itself.
   */
  suspend fun write(characteristic: BluetoothGattCharacteristic, value: ByteArray, withResponse: Boolean) {
    perform<Unit> { result ->
      val client = gatt.get() ?: throw GattStatusException(NOT_CONNECTED)
      val type = if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
      answer(written, characteristic) {
        var attempt = 0
        while (true) {
          val code =
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
            code == BluetoothStatusCodes.SUCCESS -> return@answer
            code == BUSY && ++attempt < BUSY_RETRIES -> Thread.sleep(BUSY_DELAY_MS)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> throw GattRequestRefusedException(code)
            else -> throw GattStatusException(BluetoothGatt.GATT_FAILURE)
          }
        }
      }
      result.complete(Unit)
    }
  }

  /**
   * Switches notifications, or with [indicate] indications, on or off: tells Android to deliver them and writes the
   * characteristic's configuration descriptor on the device. Returns once the device confirmed.
   */
  suspend fun setNotifications(characteristic: BluetoothGattCharacteristic, isOn: Boolean, indicate: Boolean) {
    perform<Unit> { result ->
      val client = gatt.get() ?: throw GattStatusException(NOT_CONNECTED)
      if (!client.setCharacteristicNotification(characteristic, isOn)) throw GattStatusException(BluetoothGatt.GATT_FAILURE)
      val descriptor = characteristic.getDescriptor(CLIENT_CONFIGURATION) ?: throw GattStatusException(BluetoothGatt.GATT_FAILURE)
      val value =
        when {
          !isOn -> BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
          indicate -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
          else -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }
      answer(descriptorWritten, characteristic) {
        val code =
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            client.writeDescriptor(descriptor, value)
          } else {
            @Suppress("DEPRECATION")
            descriptor.value = value
            @Suppress("DEPRECATION")
            if (client.writeDescriptor(descriptor)) BluetoothStatusCodes.SUCCESS else BluetoothGatt.GATT_FAILURE
          }
        if (code != BluetoothStatusCodes.SUCCESS) {
          throw if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) GattRequestRefusedException(code) else GattStatusException(code)
        }
      }
      result.complete(Unit)
    }
  }

  /** Asks for an MTU of [size] and returns the one agreed, or the current one when the device doesn't answer. */
  suspend fun requestMtu(size: Int): Int =
    perform { result ->
      val client = gatt.get() ?: throw GattStatusException(NOT_CONNECTED)
      val waiting = CompletableDeferred<Int>()
      mtuAnswer.set(waiting)
      if (!client.requestMtu(size)) {
        mtuAnswer.set(null)
        result.complete(mtu)
        return@perform
      }
      result.complete(withTimeoutOrNull(MTU_TIMEOUT_MS) { waiting.await() } ?: mtu)
      mtuAnswer.compareAndSet(waiting, null)
    }

  /**
   * Runs [operation] in the connection's own scope, one at a time. The caller waits for [operation]'s result and can
   * stop waiting at once; [operation] then still holds the turn until its answer, so the next one can't collide.
   */
  private suspend fun <T> perform(operation: suspend (CompletableDeferred<T>) -> Unit): T {
    val result = CompletableDeferred<T>()
    scope.launch {
      operations.withLock {
        if (result.isCompleted) return@withLock // the caller gave up while queued: never sent
        try {
          operation(result)
        } catch (failure: Throwable) {
          result.completeExceptionally(failure)
        }
      }
    }
    return try {
      result.await()
    } catch (cancelled: CancellationException) {
      result.cancel()
      throw cancelled
    }
  }

  /**
   * Sends a request and waits for the answer for [characteristic], at most Bluetooth's own 30 seconds, or until the
   * device disconnects. Answers for another characteristic don't end the wait.
   */
  private suspend fun <T> answer(
    slot: AtomicReference<Pending<T>?>,
    characteristic: BluetoothGattCharacteristic,
    send: () -> Unit,
  ): T {
    val pending = Pending<T>(characteristic.uuid, characteristic.instanceId)
    slot.set(pending)
    try {
      send()
      return withTimeoutOrNull(ATT_TIMEOUT_MS) { pending.answer.await() } ?: throw GattStatusException(NO_ANSWER)
    } finally {
      slot.compareAndSet(pending, null)
    }
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
    readValue.getAndSet(null)?.answer?.completeExceptionally(GattStatusException(NOT_CONNECTED))
    written.getAndSet(null)?.answer?.completeExceptionally(GattStatusException(NOT_CONNECTED))
    descriptorWritten.getAndSet(null)?.answer?.completeExceptionally(GattStatusException(NOT_CONNECTED))
    mtuAnswer.getAndSet(null)?.cancel()
    scope.cancel()
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

/** Not a GATT status: the device didn't answer within Bluetooth's 30 seconds. */
const val NO_ANSWER = -2

/** Android refused to send the request at all; [code] is a `BluetoothStatusCodes` value, from Android 13. */
class GattRequestRefusedException(val code: Int) : Exception("Android refused to send the request (code $code)")

private const val ATT_TIMEOUT_MS = 30_000L

/** The Client Characteristic Configuration descriptor, which switches a characteristic's notifications. */
private val CLIENT_CONFIGURATION: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
private const val MTU_TIMEOUT_MS = 5_000L

/** `BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY`, from Android 13. */
private const val BUSY = 201
private const val BUSY_RETRIES = 3
private const val BUSY_DELAY_MS = 20L
