/// A peripheral's identity, as the platform reports it: a Core Bluetooth UUID string on iOS.
/// Stable across app launches, so it survives background restoration.
public struct DeviceId: Hashable, Sendable, CustomStringConvertible {
  public let rawValue: String

  public init(_ rawValue: String) {
    self.rawValue = rawValue
  }

  public var description: String { rawValue }
}
