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
