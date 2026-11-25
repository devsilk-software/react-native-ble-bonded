package com.margelo.nitro.blebonded.features.gatt.application.useCases.readRssi

import com.margelo.nitro.blebonded.features.gatt.application.interfaces.CharacteristicSource
import com.margelo.nitro.blebonded.shared.domain.BleError
import com.margelo.nitro.blebonded.shared.domain.DeviceId
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/** Reads the connected device's signal strength in dBm. Gives up after the timeout. */
class ReadRssi(private val source: CharacteristicSource) {
  suspend operator fun invoke(id: DeviceId, timeoutMs: Long = 10_000): Int =
    try {
      withTimeout(timeoutMs) { source.readRssi(id) }
    } catch (expired: TimeoutCancellationException) {
      throw BleError(BleError.Code.TIMEOUT, "The device's signal strength didn't come within $timeoutMs ms", deviceId = id, operation = "readRssi")
    }
}
