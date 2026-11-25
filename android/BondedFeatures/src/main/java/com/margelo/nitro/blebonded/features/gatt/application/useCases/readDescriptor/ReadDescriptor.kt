package com.margelo.nitro.blebonded.features.gatt.application.useCases.readDescriptor

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
 * Reads a descriptor's value, after checking the device has the characteristic and the descriptor. Gives up after the
 * timeout. Publishes the read for diagnostics, which records it only when asked to record GATT operations.
 */
class ReadDescriptor(private val source: CharacteristicSource, private val bus: EventBus) {
  suspend operator fun invoke(descriptor: DescriptorUuid, address: CharacteristicAddress, id: DeviceId, timeoutMs: Long = 10_000): ByteArray {
    val operation = CharacteristicOperation.READ_DESCRIPTOR
    val started = System.currentTimeMillis()
    try {
      DescriptorCheck.failure(operation, descriptor, address, id, source.descriptors(address, id))?.let { throw it }
      val value =
        try {
          withTimeout(timeoutMs) { source.readDescriptor(descriptor, address, id) }
        } catch (expired: TimeoutCancellationException) {
          throw BleError(BleError.Code.TIMEOUT, "The device didn't answer the read of descriptor $descriptor within $timeoutMs ms", deviceId = id, operation = operation.publicName)
        }
      bus.publish(DomainEvent(DomainEvent.Kind.GATT_OPERATION, id, detail = "descriptor read $descriptor: ${value.size} bytes in ${System.currentTimeMillis() - started} ms"))
      return value
    } catch (error: BleError) {
      bus.publish(DomainEvent(DomainEvent.Kind.GATT_OPERATION, id, detail = "descriptor read $descriptor failed", errorCode = error.code, platformCode = error.platformCode))
      throw error
    }
  }
}
