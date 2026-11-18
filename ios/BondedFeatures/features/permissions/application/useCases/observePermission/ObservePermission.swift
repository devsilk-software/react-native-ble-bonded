/// Follows the app's Bluetooth permission: the current status at once, then each change, repeats suppressed.
public struct ObservePermission: Sendable {
  private let source: any PermissionSource

  public init(source: any PermissionSource) {
    self.source = source
  }

  public func callAsFunction() -> AsyncStream<PermissionStatus> {
    let source = self.source
    return currentThenChanges(current: { await source.currentStatus() }, changes: { source.statusChanges() })
  }
}
