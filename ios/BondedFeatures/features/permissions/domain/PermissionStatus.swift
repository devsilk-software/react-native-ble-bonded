/// Whether the app may use Bluetooth, with the meanings react-native-permissions gives its statuses.
public enum PermissionStatus: Sendable, Equatable, CaseIterable {
  /// The phone has no Bluetooth Low Energy.
  case unavailable
  /// Not granted, but the app can ask (including when it never has).
  case denied
  case granted
  /// Not granted, and only the system settings can change that.
  case blocked
}

extension PermissionStatus {
  /// The status's public name, as JavaScript sees it.
  public var name: String {
    switch self {
    case .unavailable: "unavailable"
    case .denied: "denied"
    case .granted: "granted"
    case .blocked: "blocked"
    }
  }
}
