/// A device a scan found, with what it advertised. The common fields always; the rest only when the scan asked
/// for the full advertisement.
public struct ScannedDevice: Sendable, Equatable {
  public let id: DeviceId
  public let name: String?
  public let rssi: Int
  public let serviceUuids: [ServiceUuid]
  public let manufacturerData: ManufacturerData?
  public let full: FullAdvertisement?

  public init(
    id: DeviceId,
    name: String?,
    rssi: Int,
    serviceUuids: [ServiceUuid] = [],
    manufacturerData: ManufacturerData? = nil,
    full: FullAdvertisement? = nil
  ) {
    self.id = id
    self.name = name
    self.rssi = rssi
    self.serviceUuids = serviceUuids
    self.manufacturerData = manufacturerData
    self.full = full
  }

  /// The device as a scan reports it: the full advertisement only when asked for.
  public init(_ advertisement: Advertisement, isFull: Bool) {
    self.init(
      id: advertisement.id,
      name: advertisement.name,
      rssi: advertisement.rssi,
      serviceUuids: advertisement.serviceUuids,
      manufacturerData: advertisement.rawManufacturerData.flatMap(ManufacturerData.init(raw:)),
      full: isFull ? FullAdvertisement(advertisement) : nil
    )
  }
}

/// Manufacturer-specific data with its company ID split off, as almost every app filters on it.
public struct ManufacturerData: Sendable, Equatable {
  /// The Bluetooth SIG company identifier, such as `0x0059` for Nordic Semiconductor.
  public let companyId: Int
  public let data: [UInt8]

  public init(companyId: Int, data: [UInt8]) {
    self.companyId = companyId
    self.data = data
  }

  /// Splits the little-endian company ID off the raw bytes; `nil` when there are fewer than two.
  public init?(raw: [UInt8]) {
    guard raw.count >= 2 else { return nil }
    self.init(companyId: Int(raw[0]) | Int(raw[1]) << 8, data: Array(raw.dropFirst(2)))
  }
}

/// The advertisement fields beyond the common ones. A field the platform doesn't report is `nil`.
public struct FullAdvertisement: Sendable, Equatable {
  public let serviceData: [ServiceUuid: [UInt8]]
  public let txPowerLevel: Int?
  public let isConnectable: Bool?
  public let rawManufacturerData: [UInt8]?
  public let solicitedServiceUuids: [ServiceUuid]
  /// iOS only: services advertised in the overflow area by an app in the background.
  public let overflowServiceUuids: [ServiceUuid]?
  /// Milliseconds since the Unix epoch.
  public let timestamp: Int64

  public init(_ advertisement: Advertisement) {
    serviceData = advertisement.serviceData
    txPowerLevel = advertisement.txPowerLevel
    isConnectable = advertisement.isConnectable
    rawManufacturerData = advertisement.rawManufacturerData
    solicitedServiceUuids = advertisement.solicitedServiceUuids
    overflowServiceUuids = advertisement.overflowServiceUuids
    timestamp = advertisement.timestamp
  }
}

/// Everything the platform reported about one advertisement.
public struct Advertisement: Sendable, Equatable {
  public let id: DeviceId
  public let name: String?
  public let rssi: Int
  public let serviceUuids: [ServiceUuid]
  public let rawManufacturerData: [UInt8]?
  public let serviceData: [ServiceUuid: [UInt8]]
  public let txPowerLevel: Int?
  public let isConnectable: Bool?
  public let solicitedServiceUuids: [ServiceUuid]
  public let overflowServiceUuids: [ServiceUuid]?
  public let timestamp: Int64

  public init(
    id: DeviceId,
    name: String?,
    rssi: Int,
    serviceUuids: [ServiceUuid] = [],
    rawManufacturerData: [UInt8]? = nil,
    serviceData: [ServiceUuid: [UInt8]] = [:],
    txPowerLevel: Int? = nil,
    isConnectable: Bool? = nil,
    solicitedServiceUuids: [ServiceUuid] = [],
    overflowServiceUuids: [ServiceUuid]? = nil,
    timestamp: Int64 = 0
  ) {
    self.id = id
    self.name = name
    self.rssi = rssi
    self.serviceUuids = serviceUuids
    self.rawManufacturerData = rawManufacturerData
    self.serviceData = serviceData
    self.txPowerLevel = txPowerLevel
    self.isConnectable = isConnectable
    self.solicitedServiceUuids = solicitedServiceUuids
    self.overflowServiceUuids = overflowServiceUuids
    self.timestamp = timestamp
  }
}
