@testable import BondedFeatures

/// A `PermissionSource` that reports whatever status the test sets, answers a request with `afterRequest`,
/// and lets the test push changes.
struct FakePermissionSource: PermissionSource {
  let status: PermissionStatus
  let afterRequest: PermissionStatus
  let changes: AsyncStream<PermissionStatus>

  init(
    status: PermissionStatus,
    afterRequest: PermissionStatus = .granted,
    changes: AsyncStream<PermissionStatus> = AsyncStream { $0.finish() }
  ) {
    self.status = status
    self.afterRequest = afterRequest
    self.changes = changes
  }

  func currentStatus() async -> PermissionStatus {
    status
  }

  func request() async -> PermissionStatus {
    afterRequest
  }

  func statusChanges() -> AsyncStream<PermissionStatus> {
    changes
  }
}
