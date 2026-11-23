/// Where the `bluetoothState` feature learns the radio's state. Implemented by an adapter on the platform's
/// Bluetooth client, and by a fake in tests. Never shows the permission prompt.
public protocol AdapterStateSource: Sendable {
  /// The radio's state. Waits for the system's first report if there hasn't been one yet.
  func currentState() async -> AdapterState

  /// Asks the user to turn Bluetooth on where the platform allows it, and returns the resulting state.
  func requestEnable() async -> AdapterState

  /// Every state the system reports from now on, repeats included.
  func stateChanges() -> AsyncStream<AdapterState>
}
