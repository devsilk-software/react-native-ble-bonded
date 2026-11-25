/// A GATT descriptor UUID, always in its full 128-bit lowercase form, like `ServiceUuid`.
public struct DescriptorUuid: Hashable, Sendable, CustomStringConvertible {
  public let rawValue: String

  /// Accepts a 16-bit (`2901`), 32-bit or 128-bit UUID; `nil` for anything else.
  public init?(_ string: String) {
    guard let full = ServiceUuid(string) else { return nil }
    rawValue = full.rawValue
  }

  /// The Client Characteristic Configuration descriptor, which switches a characteristic's notifications.
  public static let clientConfiguration = DescriptorUuid("2902")!

  public var description: String { rawValue }
}
