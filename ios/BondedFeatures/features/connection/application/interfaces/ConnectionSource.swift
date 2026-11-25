/// The platform's connections, in Bonded's words. Implemented with Core Bluetooth, and by a fake in tests.
public protocol ConnectionSource: Sendable {
  func readiness() async -> ConnectionReadiness

  /// Connects and discovers the device's services; returns them once the device is ready. Throws a `BleError`.
  /// Cancelling the calling task cancels the attempt.
  func attempt(_ id: DeviceId, mode: ConnectMode) async throws -> [GattService]

  /// Disconnects a connected device and returns once it has.
  func disconnect(_ id: DeviceId) async

  /// Every disconnection the app didn't ask for, from the moment this returns.
  func disconnections() async -> AsyncStream<PlatformDisconnect>

  /// Whether an attempt failed for a reason that may pass on its own, so retrying is worth it.
  func isTransient(_ error: BleError) -> Bool

  /// `services` with each characteristic's descriptors, discovering them where the platform hasn't yet. Throws a
  /// `BleError`.
  func withDescriptors(_ services: [GattService], of id: DeviceId) async throws -> [GattService]

  /// The device's name, as the platform last saw it.
  func name(of id: DeviceId) async -> String?
}

public struct PlatformDisconnect: Sendable, Equatable {
  public let id: DeviceId
  public let disconnect: Disconnect

  public init(id: DeviceId, disconnect: Disconnect) {
    self.id = id
    self.disconnect = disconnect
  }
}
