package com.margelo.nitro.blebonded.features.gatt.application.useCases.getMaxWriteLength

import com.margelo.nitro.blebonded.features.gatt.application.interfaces.CharacteristicSource
import com.margelo.nitro.blebonded.shared.domain.DeviceId

/**
 * The longest value one write carries without being split: the connection's MTU minus 3, agreed per connection. Throws
 * `notConnected` while the device isn't connected.
 */
class GetMaxWriteLength(private val source: CharacteristicSource) {
  suspend operator fun invoke(id: DeviceId): Int = source.maxWriteLength(id)
}
