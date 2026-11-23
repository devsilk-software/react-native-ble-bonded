package com.margelo.nitro.blebonded.features.connection.application.useCases.configureConnections

import com.margelo.nitro.blebonded.features.connection.application.services.ConnectionCoordinator
import com.margelo.nitro.blebonded.features.connection.domain.AndroidConnectionOverrides

/** Changes the connection workarounds every connection uses unless its own options say otherwise. */
class ConfigureConnections(private val coordinator: ConnectionCoordinator) {
  operator fun invoke(overrides: AndroidConnectionOverrides) = coordinator.configureAndroid(overrides)
}
