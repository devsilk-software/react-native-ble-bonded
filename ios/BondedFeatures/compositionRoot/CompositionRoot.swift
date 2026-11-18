/// Creates the shared Bluetooth client and the adapters, and builds each use case with them. The Nitro
/// controllers get their use cases from here.
public final class CompositionRoot: Sendable {
  public static let shared = CompositionRoot()

  public let getAdapterState: GetAdapterState
  public let observeAdapterState: ObserveAdapterState
  public let getPermissionStatus: GetPermissionStatus
  public let requestPermission: RequestPermission
  public let observePermission: ObservePermission

  private init() {
    let central = BluetoothCentral()

    let adapterStateSource = CoreBluetoothAdapterStateSource(central: central)
    getAdapterState = GetAdapterState(source: adapterStateSource)
    observeAdapterState = ObserveAdapterState(source: adapterStateSource)

    let permissionSource = CoreBluetoothPermissionSource(central: central)
    getPermissionStatus = GetPermissionStatus(source: permissionSource)
    requestPermission = RequestPermission(source: permissionSource)
    observePermission = ObservePermission(source: permissionSource)
  }
}
