/// Creates the shared Bluetooth client and the adapters, and builds each use case with them. The Nitro
/// controllers get their use cases from here.
public final class CompositionRoot: Sendable {
  public static let shared = CompositionRoot()

  public let getAdapterState: GetAdapterState

  private init() {
    let central = BluetoothCentral()
    getAdapterState = GetAdapterState(source: CoreBluetoothAdapterStateSource(central: central))
  }
}
