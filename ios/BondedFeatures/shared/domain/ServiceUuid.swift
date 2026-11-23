/// A GATT service UUID, always in its full 128-bit lowercase form, so the same service compares equal however
/// the platform or the app wrote it (`180D`, `0000180d-0000-1000-8000-00805f9b34fb`).
public struct ServiceUuid: Hashable, Sendable, CustomStringConvertible {
  public let rawValue: String

  /// Accepts a 16-bit (`180D`), 32-bit or 128-bit UUID; `nil` for anything else.
  public init?(_ string: String) {
    let hex = string.lowercased()
    let isHex = { (text: Substring) in !text.isEmpty && text.allSatisfy { $0.isHexDigit } }
    switch hex.count {
    case 4 where isHex(Substring(hex)):
      rawValue = "0000\(hex)-0000-1000-8000-00805f9b34fb"
    case 8 where isHex(Substring(hex)):
      rawValue = "\(hex)-0000-1000-8000-00805f9b34fb"
    case 36:
      let groups = hex.split(separator: "-", omittingEmptySubsequences: false)
      guard groups.map(\.count) == [8, 4, 4, 4, 12], groups.allSatisfy(isHex) else { return nil }
      rawValue = hex
    default:
      return nil
    }
  }

  public var description: String { rawValue }
}
