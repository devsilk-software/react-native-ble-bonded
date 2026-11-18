/// The phone's Bluetooth radio, in Bonded's words.
public enum AdapterState: Sendable, Equatable, CaseIterable {
  /// Not known yet: the system hasn't reported a state.
  case unknown
  /// The system is restarting Bluetooth.
  case resetting
  /// The phone has no Bluetooth Low Energy.
  case unsupported
  /// The user hasn't granted the app Bluetooth permission.
  case unauthorized
  case poweredOff
  case poweredOn
}
