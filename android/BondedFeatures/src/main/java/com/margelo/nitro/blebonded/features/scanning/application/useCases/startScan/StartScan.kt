package com.margelo.nitro.blebonded.features.scanning.application.useCases.startScan

import com.margelo.nitro.blebonded.features.scanning.application.services.ScanCoordinator
import com.margelo.nitro.blebonded.features.scanning.application.services.ScanSession
import com.margelo.nitro.blebonded.features.scanning.domain.ScanRequest

/** Starts a scan, replacing the running one. Throws a `BleError` when the phone can't scan. */
class StartScan(private val coordinator: ScanCoordinator) {
  suspend operator fun invoke(request: ScanRequest): ScanSession = coordinator.start(request)
}
