package com.margelo.nitro.blebonded.features.gatt.application.useCases.readCharacteristic

import com.margelo.nitro.blebonded.features.gatt.application.interfaces.CharacteristicSource
import com.margelo.nitro.blebonded.features.gatt.domain.CharacteristicAddress
import com.margelo.nitro.blebonded.features.gatt.domain.CharacteristicOperation
import com.margelo.nitro.blebonded.features.gatt.domain.OperationCheck
import com.margelo.nitro.blebonded.shared.application.EventBus
import com.margelo.nitro.blebonded.shared.domain.BleError
import com.margelo.nitro.blebonded.shared.domain.DeviceId
import com.margelo.nitro.blebonded.shared.domain.DomainEvent
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * Reads a characteristic's value, after checking the device has it and it allows reading. Gives up after the timeout.
 * Publishes the read for diagnostics, which records it only when asked to record GATT operations.
 */
class ReadCharacteristic(private val source: CharacteristicSource, private val bus: EventBus) {
  suspend operator fun invoke(address: CharacteristicAddress, id: DeviceId, timeoutMs: Long = 10_000): ByteArray {
    val started = System.currentTimeMillis()
    try {
      OperationCheck.failure(CharacteristicOperation.READ, address, id, source.properties(address, id))?.let { throw it }
      val value =
        try {
          withTimeout(timeoutMs) { source.read(address, id) }
        } catch (expired: TimeoutCancellationException) {
          throw BleError(
            BleError.Code.TIMEOUT,
            "The device didn't answer the read of characteristic ${address.characteristic} within $timeoutMs ms",
            deviceId = id,
            operation = "read",
          )
        }
      bus.publish(
        DomainEvent(DomainEvent.Kind.GATT_OPERATION, id, detail = "read ${address.characteristic}: ${value.size} bytes in ${System.currentTimeMillis() - started} ms"),
      )
      return value
    } catch (error: BleError) {
      bus.publish(DomainEvent(DomainEvent.Kind.GATT_OPERATION, id, detail = "read ${address.characteristic} failed", errorCode = error.code, platformCode = error.platformCode))
      throw error
    }
  }
}
