package com.margelo.nitro.blebonded.features.connection.application.useCases.disconnectDevice

import com.margelo.nitro.blebonded.features.connection.application.services.ConnectionCoordinator
import com.margelo.nitro.blebonded.shared.domain.DeviceId

/** Disconnects a device, or cancels its connection attempt; returns once it's disconnected. */
class DisconnectDevice(private val coordinator: ConnectionCoordinator) {
  suspend operator fun invoke(id: DeviceId) = coordinator.disconnect(id)
}
