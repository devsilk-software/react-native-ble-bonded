package com.margelo.nitro.blebonded.features.scanning.adapters.androidScanSource

import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import com.margelo.nitro.blebonded.features.scanning.application.interfaces.ScanSource
import com.margelo.nitro.blebonded.features.scanning.domain.Advertisement
import com.margelo.nitro.blebonded.features.scanning.domain.ScanReadiness
import com.margelo.nitro.blebonded.infrastructure.bluetooth.BluetoothRadio
import com.margelo.nitro.blebonded.infrastructure.bluetooth.ScanFailedException
import com.margelo.nitro.blebonded.shared.domain.BleError
import com.margelo.nitro.blebonded.shared.domain.DeviceId
import com.margelo.nitro.blebonded.shared.domain.ServiceUuid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/** Answers [ScanSource] with Android's Bluetooth scanner, through the shared Bluetooth client. */
class AndroidScanSource(private val radio: BluetoothRadio) : ScanSource {
  override fun readiness(): ScanReadiness {
    val state = radio.radioState()
    return when {
      !radio.hasLowEnergy() || state == null -> ScanReadiness.UNSUPPORTED
      !radio.mayScan() -> ScanReadiness.UNAUTHORIZED
      state != BluetoothAdapter.STATE_ON -> ScanReadiness.BLUETOOTH_OFF
      else -> ScanReadiness.READY
    }
  }

  override fun advertisements(services: List<ServiceUuid>, allowDuplicates: Boolean): Flow<Advertisement> {
    val filters = services.map { ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString(it.rawValue)).build() }
    val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
    return radio
      .scan(filters, settings)
      .map(::advertisement)
      .catch { failure ->
        throw when (failure) {
          is ScanFailedException ->
            BleError(BleError.Code.UNKNOWN, failure.message ?: "Android refused to scan", failure.errorCode, operation = "scan")
          is SecurityException -> BleError(BleError.Code.UNAUTHORIZED, "Bluetooth permission is not granted", operation = "scan")
          else -> failure
        }
      }
  }

  companion object {
    /** Android's scan result in Bonded's words. The advertised name wins over the cached one. */
    fun advertisement(result: ScanResult): Advertisement {
      val record = result.scanRecord
      val manufacturer = record?.manufacturerSpecificData
      val rawManufacturer =
        if (manufacturer != null && manufacturer.size() > 0) {
          val companyId = manufacturer.keyAt(0)
          listOf((companyId and 0xFF).toByte(), ((companyId shr 8) and 0xFF).toByte()) + manufacturer.valueAt(0).toList()
        } else {
          null
        }
      val cachedName = runCatching { result.device.name }.getOrNull() // needs BLUETOOTH_CONNECT from Android 12
      val ageMs = (SystemClock.elapsedRealtimeNanos() - result.timestampNanos) / 1_000_000
      return Advertisement(
        id = DeviceId(result.device.address),
        name = record?.deviceName ?: cachedName,
        rssi = result.rssi,
        serviceUuids = record?.serviceUuids.orEmpty().mapNotNull { ServiceUuid.parse(it.toString()) },
        rawManufacturerData = rawManufacturer,
        serviceData =
          record?.serviceData.orEmpty().entries.mapNotNull { (uuid, data) ->
            ServiceUuid.parse(uuid.toString())?.let { it to data.toList() }
          }.toMap(),
        txPowerLevel = record?.txPowerLevel?.takeIf { it != Int.MIN_VALUE },
        isConnectable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) result.isConnectable else null,
        solicitedServiceUuids =
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            record?.serviceSolicitationUuids.orEmpty().mapNotNull { ServiceUuid.parse(it.toString()) }
          } else {
            emptyList()
          },
        rawScanRecord = record?.bytes?.toList(),
        primaryPhy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) result.primaryPhy else null,
        secondaryPhy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) result.secondaryPhy else null,
        timestamp = System.currentTimeMillis() - ageMs,
      )
    }
  }
}
