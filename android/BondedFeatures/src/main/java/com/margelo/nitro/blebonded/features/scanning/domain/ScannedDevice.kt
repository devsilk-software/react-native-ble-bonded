package com.margelo.nitro.blebonded.features.scanning.domain

import com.margelo.nitro.blebonded.shared.domain.DeviceId
import com.margelo.nitro.blebonded.shared.domain.ServiceUuid

/**
 * A device a scan found, with what it advertised. The common fields always; the rest only when the scan asked
 * for the full advertisement.
 */
data class ScannedDevice(
  val id: DeviceId,
  val name: String?,
  val rssi: Int,
  val serviceUuids: List<ServiceUuid> = emptyList(),
  val manufacturerData: ManufacturerData? = null,
  val full: FullAdvertisement? = null,
) {
  companion object {
    /** The device as a scan reports it: the full advertisement only when asked for. */
    fun from(advertisement: Advertisement, isFull: Boolean): ScannedDevice =
      ScannedDevice(
        id = advertisement.id,
        name = advertisement.name,
        rssi = advertisement.rssi,
        serviceUuids = advertisement.serviceUuids,
        manufacturerData = advertisement.rawManufacturerData?.let(ManufacturerData::fromRaw),
        full = if (isFull) FullAdvertisement.from(advertisement) else null,
      )
  }
}

/** Manufacturer-specific data with its company ID split off, as almost every app filters on it. */
data class ManufacturerData(
  /** The Bluetooth SIG company identifier, such as `0x0059` for Nordic Semiconductor. */
  val companyId: Int,
  val data: List<Byte>,
) {
  companion object {
    /** Splits the little-endian company ID off the raw bytes; null when there are fewer than two. */
    fun fromRaw(raw: List<Byte>): ManufacturerData? =
      if (raw.size < 2) null else ManufacturerData((raw[0].toInt() and 0xFF) or ((raw[1].toInt() and 0xFF) shl 8), raw.drop(2))
  }
}

/** The advertisement fields beyond the common ones. A field the platform doesn't report is null. */
data class FullAdvertisement(
  val serviceData: Map<ServiceUuid, List<Byte>>,
  val txPowerLevel: Int?,
  val isConnectable: Boolean?,
  val rawManufacturerData: List<Byte>?,
  val solicitedServiceUuids: List<ServiceUuid>,
  /** Android only: the whole advertisement as received. */
  val rawScanRecord: List<Byte>?,
  /** Android only, from Android 8. */
  val primaryPhy: Int?,
  val secondaryPhy: Int?,
  /** Milliseconds since the Unix epoch. */
  val timestamp: Long,
) {
  companion object {
    fun from(advertisement: Advertisement): FullAdvertisement =
      FullAdvertisement(
        serviceData = advertisement.serviceData,
        txPowerLevel = advertisement.txPowerLevel,
        isConnectable = advertisement.isConnectable,
        rawManufacturerData = advertisement.rawManufacturerData,
        solicitedServiceUuids = advertisement.solicitedServiceUuids,
        rawScanRecord = advertisement.rawScanRecord,
        primaryPhy = advertisement.primaryPhy,
        secondaryPhy = advertisement.secondaryPhy,
        timestamp = advertisement.timestamp,
      )
  }
}

/** Everything the platform reported about one advertisement. */
data class Advertisement(
  val id: DeviceId,
  val name: String?,
  val rssi: Int,
  val serviceUuids: List<ServiceUuid> = emptyList(),
  val rawManufacturerData: List<Byte>? = null,
  val serviceData: Map<ServiceUuid, List<Byte>> = emptyMap(),
  val txPowerLevel: Int? = null,
  val isConnectable: Boolean? = null,
  val solicitedServiceUuids: List<ServiceUuid> = emptyList(),
  val rawScanRecord: List<Byte>? = null,
  val primaryPhy: Int? = null,
  val secondaryPhy: Int? = null,
  val timestamp: Long = 0,
)
