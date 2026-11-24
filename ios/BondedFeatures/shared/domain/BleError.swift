/// Every failure Bonded reports, with a code that means the same on iOS and Android
/// and the platform's own code for whoever is debugging (decision 5).
public struct BleError: Error, Equatable, Sendable {
  public enum Code: String, Sendable, CaseIterable {
    case bluetoothOff
    case unauthorized
    case deviceNotFound
    case notConnected
    case connectionFailed
    case disconnected
    case timeout
    case pairingRequired
    case gattError
    case cancelled
    case unsupported
    case unknown
    /// A value longer than the operation allows; `length` and `maxLength` say by how much.
    case valueTooLong
    /// The device has no such service or characteristic.
    case characteristicNotFound
    /// The characteristic already has a subscription; there's one at a time.
    case alreadySubscribed
  }

  public let code: Code
  public let message: String
  /// `CBError.Code` or `CBATTError.Code` raw value, when Core Bluetooth gave one.
  public let platformCode: Int?
  public let deviceId: DeviceId?
  /// The operation that failed, such as `connect` or `read`.
  public let operation: String?
  /// The Bluetooth error the device answered with, by name, such as `invalidAttributeValueLength`.
  public let attError: String?
  /// For `valueTooLong`: the value's length and the most the operation allows, in bytes.
  public let length: Int?
  public let maxLength: Int?

  public init(
    code: Code,
    message: String,
    platformCode: Int? = nil,
    deviceId: DeviceId? = nil,
    operation: String? = nil,
    attError: String? = nil,
    length: Int? = nil,
    maxLength: Int? = nil
  ) {
    self.code = code
    self.message = message
    self.platformCode = platformCode
    self.deviceId = deviceId
    self.operation = operation
    self.attError = attError
    self.length = length
    self.maxLength = maxLength
  }
}
