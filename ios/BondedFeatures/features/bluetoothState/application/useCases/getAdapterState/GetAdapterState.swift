/// Reads the phone's Bluetooth radio state.
public struct GetAdapterState: Sendable {
  private let source: any AdapterStateSource

  public init(source: any AdapterStateSource) {
    self.source = source
  }

  public func callAsFunction() async -> AdapterState {
    await source.currentState()
  }
}
