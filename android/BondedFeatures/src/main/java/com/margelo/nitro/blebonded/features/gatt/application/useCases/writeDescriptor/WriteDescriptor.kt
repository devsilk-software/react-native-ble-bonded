package com.margelo.nitro.blebonded.features.gatt.application.useCases.writeDescriptor

import com.margelo.nitro.blebonded.features.gatt.application.interfaces.CharacteristicSource
import com.margelo.nitro.blebonded.features.gatt.domain.CharacteristicAddress
import com.margelo.nitro.blebonded.features.gatt.domain.CharacteristicOperation
import com.margelo.nitro.blebonded.features.gatt.domain.DescriptorCheck
import com.margelo.nitro.blebonded.shared.application.EventBus
import com.margelo.nitro.blebonded.shared.domain.BleError
import com.margelo.nitro.blebonded.shared.domain.DescriptorUuid
import com.margelo.nitro.blebonded.shared.domain.DeviceId
import com.margelo.nitro.blebonded.shared.domain.DomainEvent
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * Writes a descriptor's value, after checking the device has the characteristic and the descriptor, the descriptor
 * isn't the notification descriptor Bonded manages, and the value fits. Gives up after the timeout. Publishes the
 * write for diagnostics, which records it only when asked to record GATT operations.
 */
class WriteDescriptor(private val source: CharacteristicSource, private val bus: EventBus) {
  suspend operator fun invoke(value: ByteArray, descriptor: DescriptorUuid, address: CharacteristicAddress, id: DeviceId, timeoutMs: Long = 10_000) {
    val operation = CharacteristicOperation.WRITE_DESCRIPTOR
    val started = System.currentTimeMillis()
    try {
      DescriptorCheck.failure(operation, descriptor, address, id, source.descriptors(address, id), value.size)?.let { throw it }
      try {
        withTimeout(timeoutMs) { source.writeDescriptor(value, descriptor, address, id) }
      } catch (expired: TimeoutCancellationException) {
        throw BleError(BleError.Code.TIMEOUT, "The device didn't confirm the write of descriptor $descriptor within $timeoutMs ms", deviceId = id, operation = operation.publicName)
      }
      bus.publish(DomainEvent(DomainEvent.Kind.GATT_OPERATION, id, detail = "descriptor write $descriptor: ${value.size} bytes in ${System.currentTimeMillis() - started} ms"))
    } catch (error: BleError) {
      bus.publish(DomainEvent(DomainEvent.Kind.GATT_OPERATION, id, detail = "descriptor write $descriptor failed", errorCode = error.code, platformCode = error.platformCode))
      throw error
    }
  }
}
