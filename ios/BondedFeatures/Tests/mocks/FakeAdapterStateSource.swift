@testable import BondedFeatures

/// An `AdapterStateSource` that reports whatever state the test sets, and lets the test push changes.
struct FakeAdapterStateSource: AdapterStateSource {
  let state: AdapterState
  let changes: AsyncStream<AdapterState>
  /// The state after the user answers the request to turn Bluetooth on.
  let afterEnableRequest: AdapterState

  init(state: AdapterState, changes: AsyncStream<AdapterState> = AsyncStream { $0.finish() }, afterEnableRequest: AdapterState? = nil) {
    self.state = state
    self.changes = changes
    self.afterEnableRequest = afterEnableRequest ?? state
  }

  func currentState() async -> AdapterState {
    state
  }

  func requestEnable() async -> AdapterState {
    afterEnableRequest
  }

  func stateChanges() -> AsyncStream<AdapterState> {
    changes
  }
}
