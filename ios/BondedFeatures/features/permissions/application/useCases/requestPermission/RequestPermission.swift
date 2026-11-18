/// Asks for Bluetooth permission where the platform allows it, and returns the status the user chose.
public struct RequestPermission: Sendable {
  private let source: any PermissionSource

  public init(source: any PermissionSource) {
    self.source = source
  }

  public func callAsFunction() async -> PermissionStatus {
    await source.request()
  }
}
