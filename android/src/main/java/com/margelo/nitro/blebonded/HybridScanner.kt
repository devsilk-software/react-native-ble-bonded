package com.margelo.nitro.blebonded

import com.margelo.nitro.blebonded.features.scanning.domain.NameFilter
import com.margelo.nitro.blebonded.features.scanning.domain.ScanEvent
import com.margelo.nitro.blebonded.features.scanning.domain.ScanRequest
import com.margelo.nitro.blebonded.features.scanning.domain.ScannedDevice
import com.margelo.nitro.blebonded.shared.domain.BleError
import com.margelo.nitro.blebonded.shared.domain.ServiceUuid
import com.margelo.nitro.core.ArrayBuffer
import com.margelo.nitro.core.Callback
import com.margelo.nitro.core.Promise
import com.margelo.nitro.core.invoke
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.margelo.nitro.blebonded.features.scanning.domain.ScanStopReason as StopReason

/**
 * The `Scanner` HybridObject: calls the `scanning` feature's use cases and maps between them and the generated
 * Nitro types. Nothing else lives here (decision A5).
 */
class HybridScanner : HybridScannerSpec() {
  companion object : HybridScannerSpec.Companion {
    private val listeners = ListenerRegistry()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun start(
      options: NativeScanOptions,
      onDevice: Callback<(device: NativeScannedDevice) -> Unit>,
      onStop: Callback<(stop: NativeScanStop) -> Unit>,
    ): Promise<Double> {
      val request = options.toRequest()
      return Promise.async {
        val session =
          try {
            root().startScan(request)
          } catch (error: BleError) {
            throw Exception(error.jsMessage)
          }
        listeners.add(session.events) { event ->
          when (event) {
            is ScanEvent.Device -> onDevice(event.device.toSpec())
            is ScanEvent.Stopped -> onStop(event.toSpec())
          }
        }
        session.id.toDouble()
      }
    }

    override fun stop(id: Double) {
      scope.launch { root().stopScan(id.toInt()) }
    }

    private fun NativeScanOptions.toRequest(): ScanRequest {
      val name =
        when {
          nameEquals != null -> NameFilter.Exact(nameEquals)
          nameStartsWith != null || nameEndsWith != null || nameIncludes != null ->
            NameFilter.Pattern(nameStartsWith, nameEndsWith, nameIncludes)
          else -> null
        }
      return ScanRequest(
        services =
          services.map {
            ServiceUuid.parse(it) ?: throw Exception(BleError(BleError.Code.UNKNOWN, "Not a service UUID: $it", operation = "scan").jsMessage)
          },
        name = name,
        allowDuplicates = allowDuplicates,
        fullAdvertisement = fullAdvertisement,
        timeoutMs = if (timeoutMs > 0) timeoutMs.toLong() else null,
      )
    }

    private fun List<Byte>.toBuffer(): ArrayBuffer = ArrayBuffer.copy(toByteArray())

    private fun ScannedDevice.toSpec(): NativeScannedDevice =
      NativeScannedDevice(
        id = id.rawValue,
        name = name,
        rssi = rssi.toDouble(),
        serviceUuids = serviceUuids.map { it.rawValue }.toTypedArray(),
        manufacturerData = manufacturerData?.let { ManufacturerData(it.companyId.toDouble(), it.data.toBuffer()) },
        serviceData = full?.serviceData?.map { (uuid, data) -> NativeServiceData(uuid.rawValue, data.toBuffer()) }?.toTypedArray(),
        txPowerLevel = full?.txPowerLevel?.toDouble(),
        isConnectable = full?.isConnectable,
        rawManufacturerData = full?.rawManufacturerData?.toBuffer(),
        solicitedServiceUuids = full?.solicitedServiceUuids?.map { it.rawValue }?.toTypedArray(),
        overflowServiceUuids = null,
        rawScanRecord = full?.rawScanRecord?.toBuffer(),
        primaryPhy = full?.primaryPhy?.toDouble(),
        secondaryPhy = full?.secondaryPhy?.toDouble(),
        timestamp = full?.timestamp?.toDouble(),
      )

    private fun ScanEvent.Stopped.toSpec(): NativeScanStop =
      NativeScanStop(
        reason =
          when (reason) {
            StopReason.STOPPED -> ScanStopReason.STOPPED
            StopReason.REPLACED -> ScanStopReason.REPLACED
            StopReason.TIMEOUT -> ScanStopReason.TIMEOUT
            StopReason.FAILED -> ScanStopReason.FAILED
          },
        errorCode = error?.code?.publicName,
        errorMessage = error?.message,
        platformCode = error?.platformCode?.toDouble(),
      )
  }
}

/**
 * How a [BleError] crosses to JavaScript: `[code] message` or `[code:platformCode] message`, which `src/api`
 * turns back into a `BleError`.
 */
internal val BleError.jsMessage: String
  get() = "[${code.publicName}${platformCode?.let { ":$it" } ?: ""}] $message"
