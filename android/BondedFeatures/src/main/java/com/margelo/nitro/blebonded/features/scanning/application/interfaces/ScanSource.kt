package com.margelo.nitro.blebonded.features.scanning.application.interfaces

import com.margelo.nitro.blebonded.features.scanning.domain.Advertisement
import com.margelo.nitro.blebonded.features.scanning.domain.ScanReadiness
import com.margelo.nitro.blebonded.shared.domain.BleError
import com.margelo.nitro.blebonded.shared.domain.ServiceUuid
import kotlinx.coroutines.flow.Flow

/** The platform's scanner, in Bonded's words. Implemented with Android's scanner, and by a fake in tests. */
interface ScanSource {
  fun readiness(): ScanReadiness

  /**
   * Scans while collected: collecting starts the platform's scan, cancelling stops it. Completes when the
   * platform stops by itself (Bluetooth off, permission revoked), and fails with a [BleError] when Android
   * refuses to scan.
   */
  fun advertisements(services: List<ServiceUuid>, allowDuplicates: Boolean): Flow<Advertisement>
}
