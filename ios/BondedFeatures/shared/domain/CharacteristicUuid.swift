/// A GATT characteristic UUID, always in its full 128-bit lowercase form, like `ServiceUuid`.
public struct CharacteristicUuid: Hashable, Sendable, CustomStringConvertible {
  public let rawValue: String

  /// Accepts a 16-bit (`2A37`), 32-bit or 128-bit UUID; `nil` for anything else.
  public init?(_ string: String) {
    guard let full = ServiceUuid(string) else { return nil }
    rawValue = full.rawValue
  }

  public var description: String { rawValue }
}
