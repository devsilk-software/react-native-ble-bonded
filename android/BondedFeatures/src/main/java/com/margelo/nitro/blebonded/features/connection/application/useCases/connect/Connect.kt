package com.margelo.nitro.blebonded.features.connection.application.useCases.connect

import com.margelo.nitro.blebonded.features.connection.application.services.ConnectionCoordinator
import com.margelo.nitro.blebonded.features.connection.domain.ConnectOptions
import com.margelo.nitro.blebonded.shared.domain.DeviceId

/** Connects and discovers the device's services; returns once it's ready. Throws a `BleError`. */
class Connect(private val coordinator: ConnectionCoordinator) {
  suspend operator fun invoke(id: DeviceId, options: ConnectOptions) = coordinator.connect(id, options)
}
