package com.margelo.nitro.blebonded.features.connection.application.useCases.getServices

import com.margelo.nitro.blebonded.features.connection.application.services.ConnectionCoordinator
import com.margelo.nitro.blebonded.features.connection.domain.GattService
import com.margelo.nitro.blebonded.shared.domain.DeviceId

/** The services discovered when the device connected. Throws `notConnected` while it isn't connected. */
class GetServices(private val coordinator: ConnectionCoordinator) {
  suspend operator fun invoke(id: DeviceId): List<GattService> = coordinator.services(id)
}
