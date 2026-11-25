/// The platform's operations on a connected device, in Bonded's words: its characteristics and its signal strength.
/// Implemented with Core Bluetooth, and by a fake in tests.
public protocol CharacteristicSource: Sendable {
  /// What the characteristic allows, from the services discovered at connect; `nil` when the device has no such
  /// characteristic. Throws `notConnected` while the device isn't connected.
  func properties(of address: CharacteristicAddress, on id: DeviceId) async throws -> [CharacteristicProperty]?

  /// The longest value one write may carry without being split: the connection's MTU minus 3. Throws `notConnected`.
  func maxWriteLength(on id: DeviceId) async throws -> Int

  /// Reads the characteristic's value. Throws a `BleError`; cancelling stops waiting.
  func read(_ address: CharacteristicAddress, on id: DeviceId) async throws -> [UInt8]

  /// Writes `value`. With response, returns once the device confirmed; without, once the platform accepted it,
  /// waiting for room when its buffer is full.
  func write(_ value: [UInt8], to address: CharacteristicAddress, on id: DeviceId, withResponse: Bool) async throws

  /// Writes `pieces` in order as one operation, so nothing else on the device runs between them. Each piece that takes
  /// longer than `timeout` fails. Throws `PieceFailure` naming the piece that failed; the ones after it aren't sent.
  func write(pieces: [[UInt8]], to address: CharacteristicAddress, on id: DeviceId, withResponse: Bool, timeout: Duration) async throws

  /// The characteristic's descriptors, discovered the first time they're asked for and kept for the connection;
  /// `nil` when the device has no such characteristic. Throws a `BleError`.
  func descriptors(of address: CharacteristicAddress, on id: DeviceId) async throws -> [DescriptorUuid]?

  /// Reads a descriptor's value. Throws a `BleError`; cancelling stops waiting.
  func readDescriptor(_ descriptor: DescriptorUuid, of address: CharacteristicAddress, on id: DeviceId) async throws -> [UInt8]

  /// Writes a descriptor's value and returns once the device confirmed. Throws a `BleError`.
  func writeDescriptor(_ value: [UInt8], to descriptor: DescriptorUuid, of address: CharacteristicAddress, on id: DeviceId) async throws

  /// The connected device's signal strength in dBm. Throws a `BleError`; cancelling stops waiting.
  func readRssi(on id: DeviceId) async throws -> Int

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

/// A piece of a chunked write failed: which one, counting from 0, and why.
public struct PieceFailure: Error, Sendable {
  public let index: Int
  public let error: BleError

  public init(index: Int, error: BleError) {
    self.index = index
    self.error = error
  }
}
