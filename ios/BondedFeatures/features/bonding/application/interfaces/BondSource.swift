/// The platform's pairing, in Bonded's words. Implemented with Core Bluetooth, and by a fake in tests.
public protocol BondSource: Sendable {
  /// Whether the trigger characteristic can be read; `nil` when the device has no such characteristic. Throws
  /// `notConnected` while the device isn't connected.
  func isReadable(_ trigger: BondTrigger, on id: DeviceId) async throws -> Bool?

  /// Reads the trigger. When it needs encryption and the phone isn't paired, the platform shows its pairing dialog
  /// first, and the read succeeds once paired. Throws `pairingRequired` when pairing is declined or fails.
  func readTrigger(_ trigger: BondTrigger, on id: DeviceId) async throws
}
