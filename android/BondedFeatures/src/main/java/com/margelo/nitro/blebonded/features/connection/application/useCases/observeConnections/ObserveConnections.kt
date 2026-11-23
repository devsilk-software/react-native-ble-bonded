package com.margelo.nitro.blebonded.features.connection.application.useCases.observeConnections

import com.margelo.nitro.blebonded.features.connection.application.services.ConnectionCoordinator
import com.margelo.nitro.blebonded.features.connection.domain.ConnectionChange
import kotlinx.coroutines.flow.Flow

/** Every known device's current connection, then every change. */
class ObserveConnections(private val coordinator: ConnectionCoordinator) {
  operator fun invoke(): Flow<ConnectionChange> = coordinator.changes()
}
