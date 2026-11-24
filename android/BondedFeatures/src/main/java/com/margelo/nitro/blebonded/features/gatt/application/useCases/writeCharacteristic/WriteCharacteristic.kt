package com.margelo.nitro.blebonded.features.gatt.application.useCases.writeCharacteristic

import com.margelo.nitro.blebonded.features.gatt.application.interfaces.CharacteristicSource
import com.margelo.nitro.blebonded.features.gatt.domain.CharacteristicAddress
import com.margelo.nitro.blebonded.features.gatt.domain.CharacteristicOperation
import com.margelo.nitro.blebonded.features.gatt.domain.MAX_ATTRIBUTE_LENGTH
import com.margelo.nitro.blebonded.features.gatt.domain.OperationCheck
import com.margelo.nitro.blebonded.shared.domain.BleError
import com.margelo.nitro.blebonded.shared.domain.DeviceId
import com.margelo.nitro.blebonded.shared.domain.DomainEvent
import com.margelo.nitro.blebonded.shared.domain.EventBus
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * Writes a value, after checking the device has the characteristic, it allows this kind of write and the value fits.
 * Without response it never splits a value. Gives up after the timeout. Publishes the write for diagnostics, which
 * records it only when asked to record GATT operations.
 */
class WriteCharacteristic(private val source: CharacteristicSource, private val bus: EventBus) {
  suspend operator fun invoke(value: ByteArray, address: CharacteristicAddress, id: DeviceId, withResponse: Boolean, timeoutMs: Long = 10_000) {
    val operation = if (withResponse) CharacteristicOperation.WRITE else CharacteristicOperation.WRITE_WITHOUT_RESPONSE
    val started = System.currentTimeMillis()
    try {
      val properties = source.properties(address, id)
      val maxWriteLength = if (withResponse) MAX_ATTRIBUTE_LENGTH else source.maxWriteLength(id)
      OperationCheck.failure(operation, address, id, properties, value.size, maxWriteLength)?.let { throw it }
      try {
        withTimeout(timeoutMs) { source.write(value, address, id, withResponse) }
      } catch (expired: TimeoutCancellationException) {
        throw BleError(
          BleError.Code.TIMEOUT,
          "The device didn't take the ${operation.publicName} of characteristic ${address.characteristic} within $timeoutMs ms",
          deviceId = id,
          operation = operation.publicName,
        )
      }
      bus.publish(
        DomainEvent(
          DomainEvent.Kind.GATT_OPERATION,
          id,
          detail = "${operation.publicName} ${address.characteristic}: ${value.size} bytes in ${System.currentTimeMillis() - started} ms",
        ),
      )
    } catch (error: BleError) {
      bus.publish(
        DomainEvent(DomainEvent.Kind.GATT_OPERATION, id, detail = "${operation.publicName} ${address.characteristic} failed", errorCode = error.code, platformCode = error.platformCode),
      )
      throw error
    }
  }
}
