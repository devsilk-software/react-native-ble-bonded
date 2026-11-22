/// Reads whether the app may use Bluetooth, without asking the user.
public struct GetPermissionStatus: Sendable {
  private let source: any PermissionSource

  public init(source: any PermissionSource) {
    self.source = source
  }

  public func callAsFunction() async -> PermissionStatus {
    await source.currentStatus()
  }
}
