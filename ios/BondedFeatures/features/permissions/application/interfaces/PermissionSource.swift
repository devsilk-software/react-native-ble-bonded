/// Where the `permissions` feature learns and asks for the app's Bluetooth permission. Implemented by an
/// adapter on the platform, and by a fake in tests.
public protocol PermissionSource: Sendable {
  /// The current status, without asking the user.
  func currentStatus() async -> PermissionStatus

  /// Asks the user where the platform allows it, and returns the resulting status.
  func request() async -> PermissionStatus

  /// Every status the platform reports from now on, repeats included.
  func statusChanges() -> AsyncStream<PermissionStatus>
}
