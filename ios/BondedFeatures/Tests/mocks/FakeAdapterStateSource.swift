@testable import BondedFeatures

/// An `AdapterStateSource` that reports whatever state the test sets, and lets the test push changes.
struct FakeAdapterStateSource: AdapterStateSource {
  let state: AdapterState
  let changes: AsyncStream<AdapterState>

  init(state: AdapterState, changes: AsyncStream<AdapterState> = AsyncStream { $0.finish() }) {
    self.state = state
    self.changes = changes
  }

  func currentState() async -> AdapterState {
    state
  }

  func stateChanges() -> AsyncStream<AdapterState> {
    changes
  }
}
