@testable import BondedFeatures

/// An `AdapterStateSource` that reports whatever state the test sets.
struct FakeAdapterStateSource: AdapterStateSource {
  let state: AdapterState

  func currentState() async -> AdapterState {
    state
  }
}
