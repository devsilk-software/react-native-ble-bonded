/// Where the `bluetoothState` feature learns the radio's state. Implemented by an adapter on the platform's
/// Bluetooth client, and by a fake in tests.
public protocol AdapterStateSource: Sendable {
  /// The radio's state. Waits for the system's first report if there hasn't been one yet.
  func currentState() async -> AdapterState
}
