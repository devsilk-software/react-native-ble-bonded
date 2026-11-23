package com.margelo.nitro.blebonded.features.connection.application.useCases.forgetDevice

import com.margelo.nitro.blebonded.features.connection.application.services.ConnectionCoordinator
import com.margelo.nitro.blebonded.shared.domain.DeviceId

/** Disconnects a device and forgets it; the system's pairing stays. */
class ForgetDevice(private val coordinator: ConnectionCoordinator) {
  suspend operator fun invoke(id: DeviceId) = coordinator.forget(id)
}
