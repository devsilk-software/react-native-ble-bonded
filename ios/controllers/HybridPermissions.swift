import BondedFeatures
import NitroModules

/// The `Permissions` HybridObject: calls the `permissions` feature's use cases and maps their results to the
/// generated Nitro types. Nothing else lives here (decision A5).
final class HybridPermissions: HybridPermissionsSpec {
  private static let listeners = ListenerRegistry()

  static func getStatus() throws -> any Promise<BluetoothPermission> {
    Promise___NitroBleBonded__BluetoothPermission.async {
      BluetoothPermission(await CompositionRoot.shared.getPermissionStatus())
    }
  }

  static func request() throws -> any Promise<BluetoothPermission> {
    Promise___NitroBleBonded__BluetoothPermission.async {
      BluetoothPermission(await CompositionRoot.shared.requestPermission())
    }
  }

  static func addListener(onChange: any Callback<(BluetoothPermission), Void>) throws -> Double {
    listeners.add(CompositionRoot.shared.observePermission()) { status in
      onChange.callAndForget(BluetoothPermission(status))
    }
  }

  static func removeListener(id: Double) throws {
    listeners.remove(id)
  }
}

extension BluetoothPermission {
  init(_ status: PermissionStatus) {
    switch status {
    case .unavailable: self = .unavailable
    case .denied: self = .denied
    case .granted: self = .granted
    case .blocked: self = .blocked
    }
  }
}
