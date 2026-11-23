import CoreBluetooth

/// Answers `AdapterStateSource` with Core Bluetooth, through the shared Bluetooth client.
public struct CoreBluetoothAdapterStateSource: AdapterStateSource {
  private let central: BluetoothCentral

  public init(central: BluetoothCentral) {
    self.central = central
  }

  public func currentState() async -> AdapterState {
    Self.adapterState(from: await central.managerState())
  }

  /// iOS has no public way to turn Bluetooth on or to open its settings, so this only reports the state.
  public func requestEnable() async -> AdapterState {
    await currentState()
  }

  public func stateChanges() -> AsyncStream<AdapterState> {
    let platformChanges = central.stateUpdates()
    let (stream, continuation) = AsyncStream<AdapterState>.makeStream()
    let task = Task {
      for await state in platformChanges {
        continuation.yield(Self.adapterState(from: state))
      }
      continuation.finish()
    }
    continuation.onTermination = { _ in task.cancel() }
    return stream
  }

  /// Core Bluetooth's state in Bonded's words.
  static func adapterState(from state: CBManagerState) -> AdapterState {
    switch state {
    case .unknown: .unknown
    case .resetting: .resetting
    case .unsupported: .unsupported
    case .unauthorized: .unauthorized
    case .poweredOff: .poweredOff
    case .poweredOn: .poweredOn
    @unknown default: .unknown
    }
  }
}
