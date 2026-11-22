/// Starts a scan, replacing the running one. Throws a `BleError` when the phone can't scan.
public struct StartScan: Sendable {
  private let coordinator: ScanCoordinator

  public init(coordinator: ScanCoordinator) {
    self.coordinator = coordinator
  }

  public func callAsFunction(_ request: ScanRequest) async throws -> ScanSession {
    try await coordinator.start(request)
  }
}
