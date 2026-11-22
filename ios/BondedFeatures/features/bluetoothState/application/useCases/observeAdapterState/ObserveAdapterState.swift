/// Follows the phone's Bluetooth radio: the current state at once, then each change, repeats suppressed.
public struct ObserveAdapterState: Sendable {
  private let source: any AdapterStateSource

  public init(source: any AdapterStateSource) {
    self.source = source
  }

  public func callAsFunction() -> AsyncStream<AdapterState> {
    let source = self.source
    return currentThenChanges(current: { await source.currentState() }, changes: { source.stateChanges() })
  }
}
