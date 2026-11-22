import BondedFeatures
import NitroModules

/// The `BluetoothState` HybridObject: calls the `bluetoothState` feature's use cases and maps their results to
/// the generated Nitro types. Nothing else lives here (decision A5).
final class HybridBluetoothState: HybridBluetoothStateSpec {
  private static let listeners = ListenerRegistry()

  static func getState() throws -> any Promise<BluetoothAdapterState> {
    Promise___NitroBleBonded__BluetoothAdapterState.async {
      BluetoothAdapterState(await CompositionRoot.shared.getAdapterState())
    }
  }

  static func addStateListener(onChange: any Callback<(BluetoothAdapterState), Void>) throws -> Double {
    listeners.add(CompositionRoot.shared.observeAdapterState()) { state in
      onChange.callAndForget(BluetoothAdapterState(state))
    }
  }

  static func removeStateListener(id: Double) throws {
    listeners.remove(id)
  }
}

extension BluetoothAdapterState {
  init(_ state: AdapterState) {
    switch state {
    case .unknown: self = .unknown
    case .resetting: self = .resetting
    case .unsupported: self = .unsupported
    case .unauthorized: self = .unauthorized
    case .poweredOff: self = .poweredoff
    case .poweredOn: self = .poweredon
    }
  }
}
