/// What a scan looks for and how it reports it.
public struct ScanRequest: Sendable, Equatable {
  /// Only devices advertising one of these services; empty for every device.
  public let services: [ServiceUuid]
  public let name: NameFilter?
  /// Report every advertisement, not just each device's first.
  public let allowDuplicates: Bool
  /// Include every advertisement field the platform reports, not just the common ones.
  public let fullAdvertisement: Bool
  /// Stop after this long; `nil` for never.
  public let timeout: Duration?

  public init(
    services: [ServiceUuid] = [],
    name: NameFilter? = nil,
    allowDuplicates: Bool = false,
    fullAdvertisement: Bool = false,
    timeout: Duration? = .seconds(30)
  ) {
    self.services = services
    self.name = name
    self.allowDuplicates = allowDuplicates
    self.fullAdvertisement = fullAdvertisement
    self.timeout = timeout
  }

  /// Scans without a service filter can't run in the background (decision R3).
  public var isFiltered: Bool { !services.isEmpty }
}

/// Which device names a scan reports. A pattern's parts must all match.
public enum NameFilter: Sendable, Equatable {
  case exact(String)
  case pattern(startsWith: String?, endsWith: String?, includes: String?)

  public func matches(_ name: String?) -> Bool {
    guard let name else { return false }
    switch self {
    case let .exact(expected):
      return name == expected
    case let .pattern(startsWith, endsWith, includes):
      return (startsWith.map(name.hasPrefix) ?? true)
        && (endsWith.map(name.hasSuffix) ?? true)
        && (includes.map(name.contains) ?? true)
    }
  }
}
