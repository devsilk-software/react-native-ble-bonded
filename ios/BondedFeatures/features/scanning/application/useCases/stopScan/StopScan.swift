/// Stops a scan; does nothing when it has already ended.
public struct StopScan: Sendable {
  private let coordinator: ScanCoordinator

  public init(coordinator: ScanCoordinator) {
    self.coordinator = coordinator
  }

  public func callAsFunction(_ id: Int) async {
    await coordinator.stop(id)
  }
}
