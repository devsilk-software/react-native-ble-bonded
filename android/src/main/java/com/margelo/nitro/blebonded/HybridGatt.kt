package com.margelo.nitro.blebonded

import com.margelo.nitro.blebonded.features.gatt.domain.CharacteristicAddress
import com.margelo.nitro.blebonded.shared.domain.BleError
import com.margelo.nitro.blebonded.shared.domain.CharacteristicUuid
import com.margelo.nitro.blebonded.shared.domain.DescriptorUuid
import com.margelo.nitro.blebonded.shared.domain.DeviceId
import com.margelo.nitro.blebonded.shared.domain.ServiceUuid
import com.margelo.nitro.blebonded.features.gatt.domain.SubscriptionEvent
import com.margelo.nitro.core.ArrayBuffer
import com.margelo.nitro.core.Callback
import com.margelo.nitro.core.Promise
import com.margelo.nitro.core.invoke
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The `Gatt` HybridObject: calls the `gatt` feature's use cases and maps between them and the generated Nitro types.
 * Nothing else lives here (decision A5).
 */
class HybridGatt : HybridGattSpec() {
  companion object : HybridGattSpec.Companion {
    override fun read(id: String, service: String, characteristic: String, timeoutMs: Double): Promise<ArrayBuffer> {
      val address = address(service, characteristic)
      return Promise.async {
        try {
          ArrayBuffer.copy(root().readCharacteristic(address, DeviceId(id), timeoutMs.toLong()))
        } catch (error: BleError) {
          throw Exception(error.jsMessage)
        }
      }
    }

    override fun write(
      id: String,
      service: String,
      characteristic: String,
      value: ArrayBuffer,
      withResponse: Boolean,
      timeoutMs: Double,
    ): Promise<Unit> {
      val address = address(service, characteristic)
      val buffer = value.getBuffer(copyIfNeeded = true)
      val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
      return Promise.async {
        try {
          root().writeCharacteristic(bytes, address, DeviceId(id), withResponse, timeoutMs.toLong())
        } catch (error: BleError) {
          throw Exception(error.jsMessage)
        }
      }
    }

    override fun writeChunked(
      id: String,
      service: String,
      characteristic: String,
      value: ArrayBuffer,
      withResponse: Boolean,
      timeoutMs: Double,
    ): Promise<Unit> {
      val address = address(service, characteristic)
      val buffer = value.getBuffer(copyIfNeeded = true)
      val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
      return Promise.async {
        try {
          root().writeChunked(bytes, address, DeviceId(id), withResponse, timeoutMs.toLong())
        } catch (error: BleError) {
          throw Exception(error.jsMessage)
        }
      }
    }

    override fun getMaxWriteLength(id: String): Promise<Double> =
      Promise.async {
        try {
          root().getMaxWriteLength(DeviceId(id)).toDouble()
        } catch (error: BleError) {
          throw Exception(error.jsMessage)
        }
      }

    override fun readRssi(id: String, timeoutMs: Double): Promise<Double> =
      Promise.async {
        try {
          root().readRssi(DeviceId(id), timeoutMs.toLong()).toDouble()
        } catch (error: BleError) {
          throw Exception(error.jsMessage)
        }
      }

    override fun readDescriptor(id: String, service: String, characteristic: String, descriptor: String, timeoutMs: Double): Promise<ArrayBuffer> {
      val address = address(service, characteristic)
      val uuid = descriptorUuid(descriptor)
      return Promise.async {
        try {
          ArrayBuffer.copy(root().readDescriptor(uuid, address, DeviceId(id), timeoutMs.toLong()))
        } catch (error: BleError) {
          throw Exception(error.jsMessage)
        }
      }
    }

    override fun writeDescriptor(
      id: String,
      service: String,
      characteristic: String,
      descriptor: String,
      value: ArrayBuffer,
      timeoutMs: Double,
    ): Promise<Unit> {
      val address = address(service, characteristic)
      val uuid = descriptorUuid(descriptor)
      val buffer = value.getBuffer(copyIfNeeded = true)
      val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
      return Promise.async {
        try {
          root().writeDescriptor(bytes, uuid, address, DeviceId(id), timeoutMs.toLong())
        } catch (error: BleError) {
          throw Exception(error.jsMessage)
        }
      }
    }

    /** From the UUID JavaScript passed, short or full; a malformed one rejects with a message naming it. */
    private fun descriptorUuid(text: String): DescriptorUuid =
      DescriptorUuid.parse(text) ?: throw Exception(BleError(BleError.Code.UNKNOWN, "Not a descriptor UUID: $text").jsMessage)

    override fun subscribe(
      id: String,
      service: String,
      characteristic: String,
      onValues: Callback<(values: Array<ArrayBuffer>) -> Unit>,
      onError: Callback<(message: String) -> Unit>,
      onEnd: Callback<() -> Unit>,
    ): Promise<Double> {
      val address = address(service, characteristic)
      return Promise.async {
        val subscription =
          try {
            root().subscribe(address, DeviceId(id))
          } catch (error: BleError) {
            throw Exception(error.jsMessage)
          }
        // The flow ends when the subscription is removed or the device is forgotten; so does this collection.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
          val batch = ValueBatch(scope) { values -> onValues(values.map { ArrayBuffer.copy(it) }.toTypedArray()) }
          subscription.events.collect { event ->
            when (event) {
              is SubscriptionEvent.Value -> batch.add(event.value)
              is SubscriptionEvent.Failed -> {
                batch.flush()
                onError(event.error.jsMessage)
              }
              SubscriptionEvent.Ended -> {
                batch.flush()
                onEnd()
              }
            }
          }
          batch.flush()
        }
        subscription.id.toDouble()
      }
    }

    override fun unsubscribe(subscription: Double): Promise<Unit> = Promise.async { root().unsubscribe(subscription.toInt()) }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** From the UUIDs JavaScript passed, short or full; a malformed one rejects with a message naming it. */
    private fun address(service: String, characteristic: String): CharacteristicAddress {
      val serviceUuid = ServiceUuid.parse(service) ?: throw Exception(BleError(BleError.Code.UNKNOWN, "Not a service UUID: $service").jsMessage)
      val characteristicUuid =
        CharacteristicUuid.parse(characteristic) ?: throw Exception(BleError(BleError.Code.UNKNOWN, "Not a characteristic UUID: $characteristic").jsMessage)
      return CharacteristicAddress(serviceUuid, characteristicUuid)
    }
  }
}

/**
 * Collects notified values and hands them to JavaScript in one call about every 16 ms (decision A4), in order, so a
 * fast device doesn't flood the bridge.
 */
internal class ValueBatch(private val scope: CoroutineScope, private val deliver: (List<ByteArray>) -> Unit) {
  private val pending = ArrayList<ByteArray>()
  private var scheduled: Job? = null

  @Synchronized
  fun add(value: ByteArray) {
    pending.add(value)
    if (scheduled != null) return
    scheduled =
      scope.launch {
        delay(BATCH_MS)
        flush()
      }
  }

  /** Delivers under the lock, so the timer's flush and the collector's can't hand batches over out of order. */
  @Synchronized
  fun flush() {
    scheduled = null
    if (pending.isEmpty()) return
    val values = ArrayList(pending)
    pending.clear()
    deliver(values)
  }

  private companion object {
    const val BATCH_MS = 16L
  }
}
