/// A device the app connected to, kept across launches until the app forgets it, so it can connect again without
/// storing anything itself.
public struct RememberedDevice: Sendable, Equatable, Codable {
  public let id: DeviceId
  public let name: String?
  public let identity: DeviceIdentity
  /// Milliseconds since the Unix epoch.
  public let lastConnectedAt: Int64

  public init(id: DeviceId, name: String?, identity: DeviceIdentity, lastConnectedAt: Int64) {
    self.id = id
    self.name = name
    self.identity = identity
    self.lastConnectedAt = lastConnectedAt
  }
}

/// How a device was found, to find it again when its ID changes: a rotated address on Android, an identifier iOS
/// changed after a network reset.
public struct DeviceIdentity: Sendable, Equatable, Codable {
  public let name: String?
  public let services: [String]
  public let companyId: Int?

  public init(name: String? = nil, services: [String] = [], companyId: Int? = nil) {
    self.name = name
    self.services = services
    self.companyId = companyId
  }

  /// This identity, with what `newer` knows filled in over it.
  public func updated(with newer: DeviceIdentity?) -> DeviceIdentity {
    guard let newer else { return self }
    return DeviceIdentity(
      name: newer.name ?? name,
      services: newer.services.isEmpty ? services : newer.services,
      companyId: newer.companyId ?? companyId
    )
  }
}

extension DeviceId: Codable {
  public init(from decoder: any Decoder) throws {
    self.init(try decoder.singleValueContainer().decode(String.self))
  }

  public func encode(to encoder: any Encoder) throws {
    var container = encoder.singleValueContainer()
    try container.encode(rawValue)
  }
}
