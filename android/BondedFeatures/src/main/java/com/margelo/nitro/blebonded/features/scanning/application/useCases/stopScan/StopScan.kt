package com.margelo.nitro.blebonded.features.scanning.application.useCases.stopScan

import com.margelo.nitro.blebonded.features.scanning.application.services.ScanCoordinator

/** Stops a scan; does nothing when it has already ended. */
class StopScan(private val coordinator: ScanCoordinator) {
  suspend operator fun invoke(id: Int) = coordinator.stop(id)
}
