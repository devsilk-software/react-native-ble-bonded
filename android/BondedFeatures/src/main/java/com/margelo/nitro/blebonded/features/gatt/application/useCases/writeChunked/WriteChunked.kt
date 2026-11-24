package com.margelo.nitro.blebonded.features.gatt.application.useCases.writeChunked

import com.margelo.nitro.blebonded.features.gatt.application.interfaces.CharacteristicSource
import com.margelo.nitro.blebonded.features.gatt.application.interfaces.PieceFailure
import com.margelo.nitro.blebonded.features.gatt.domain.CharacteristicAddress
import com.margelo.nitro.blebonded.features.gatt.domain.CharacteristicOperation
import com.margelo.nitro.blebonded.features.gatt.domain.Chunks
import com.margelo.nitro.blebonded.features.gatt.domain.OperationCheck
import com.margelo.nitro.blebonded.shared.application.EventBus
import com.margelo.nitro.blebonded.shared.domain.BleError
import com.margelo.nitro.blebonded.shared.domain.DeviceId
import com.margelo.nitro.blebonded.shared.domain.DomainEvent

/**
 * Writes data longer than one write carries: cuts it to the device's maximum write length and sends the chunks in
 * order as one operation, so nothing else on the device runs between them. Stops at the first chunk that fails and
 * says which one and how many bytes the device took before it. Each chunk gets the timeout. Publishes the write for
 * diagnostics, which records it only when asked to record GATT operations.
 */
class WriteChunked(private val source: CharacteristicSource, private val bus: EventBus) {
  suspend operator fun invoke(data: ByteArray, address: CharacteristicAddress, id: DeviceId, withResponse: Boolean, timeoutMs: Long = 10_000) {
    val operation = if (withResponse) CharacteristicOperation.WRITE else CharacteristicOperation.WRITE_WITHOUT_RESPONSE
    val started = System.currentTimeMillis()
    try {
      OperationCheck.failure(operation, address, id, source.properties(address, id))?.let { throw it }
      if (data.isEmpty()) return
      val size = source.maxWriteLength(id)
      val chunks = Chunks.split(data, size)
      try {
        source.write(chunks, address, id, withResponse, timeoutMs)
      } catch (failure: PieceFailure) {
        throw Chunks.failure(failure.error, failure.index, chunks.size, size, id)
      }
      bus.publish(
        DomainEvent(
          DomainEvent.Kind.GATT_OPERATION,
          id,
          detail =
            "chunked ${operation.publicName} ${address.characteristic}: ${data.size} bytes in ${chunks.size} chunks in " +
              "${System.currentTimeMillis() - started} ms",
        ),
      )
    } catch (error: BleError) {
      bus.publish(
        DomainEvent(
          DomainEvent.Kind.GATT_OPERATION,
          id,
          detail = "chunked ${operation.publicName} ${address.characteristic} failed",
          errorCode = error.code,
          platformCode = error.platformCode,
        ),
      )
      throw error
    }
  }
}
