/// Asks the user to turn Bluetooth on where the platform allows it, and returns the resulting state.
public struct RequestBluetoothOn: Sendable {
  private let source: any AdapterStateSource

  public init(source: any AdapterStateSource) {
    self.source = source
  }

  public func callAsFunction() async -> AdapterState {
    await source.requestEnable()
  }
}
