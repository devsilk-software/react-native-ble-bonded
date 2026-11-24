/// The platform's characteristics, in Bonded's words. Implemented with Core Bluetooth, and by a fake in tests.
public protocol CharacteristicSource: Sendable {
  /// What the characteristic allows, from the services discovered at connect; `nil` when the device has no such
  /// characteristic. Throws `notConnected` while the device isn't connected.
  func properties(of address: CharacteristicAddress, on id: DeviceId) async throws -> [CharacteristicProperty]?

  /// The longest value one write without response may carry.
  func maxWriteLength(on id: DeviceId) async throws -> Int

  /// Reads the characteristic's value. Throws a `BleError`; cancelling stops waiting.
  func read(_ address: CharacteristicAddress, on id: DeviceId) async throws -> [UInt8]

  /// Writes `value`. With response, returns once the device confirmed; without, once the platform accepted it,
  /// waiting for room when its buffer is full.
  func write(_ value: [UInt8], to address: CharacteristicAddress, on id: DeviceId, withResponse: Bool) async throws

  /// Switches notifications (or indications) on or off and returns once the device confirmed.
  func setNotifications(_ isOn: Bool, indicate: Bool, on address: CharacteristicAddress, of id: DeviceId) async throws

  /// Every notified value, from the moment this returns.
  func notifications() async -> AsyncStream<CharacteristicNotification>
}

/// A value a device notified.
public struct CharacteristicNotification: Sendable, Equatable {
  public let id: DeviceId
  public let address: CharacteristicAddress
  public let value: [UInt8]

  public init(id: DeviceId, address: CharacteristicAddress, value: [UInt8]) {
    self.id = id
    self.address = address
    self.value = value
  }
}
