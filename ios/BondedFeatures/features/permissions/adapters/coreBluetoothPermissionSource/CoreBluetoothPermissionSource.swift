import CoreBluetooth

/// Answers `PermissionSource` with Core Bluetooth's authorization, through the shared Bluetooth client.
/// Reading never shows the prompt; only `request()` does, when the user hasn't decided yet.
public struct CoreBluetoothPermissionSource: PermissionSource {
  private let central: BluetoothCentral

  public init(central: BluetoothCentral) {
    self.central = central
  }

  public func currentStatus() async -> PermissionStatus {
    await status(for: central.authorization)
  }

  public func request() async -> PermissionStatus {
    await status(for: central.requestAuthorization())
  }

  public func statusChanges() -> AsyncStream<PermissionStatus> {
    let platformChanges = central.authorizationUpdates()
    let (stream, continuation) = AsyncStream<PermissionStatus>.makeStream()
    let task = Task {
      for await authorization in platformChanges {
        continuation.yield(await status(for: authorization))
      }
      continuation.finish()
    }
    continuation.onTermination = { _ in task.cancel() }
    return stream
  }

  /// A phone without Bluetooth Low Energy is `unavailable`, whatever the authorization. The check never
  /// prompts: the client only creates the manager once permission is granted or the app has asked.
  private func status(for authorization: CBManagerAuthorization) async -> PermissionStatus {
    if await central.managerState() == .unsupported {
      return .unavailable
    }
    return Self.permissionStatus(from: authorization)
  }

  /// Core Bluetooth's authorization in Bonded's words. iOS asks only once: after "Don't Allow", only Settings
  /// can grant it, so `denied` means `blocked`.
  static func permissionStatus(from authorization: CBManagerAuthorization) -> PermissionStatus {
    switch authorization {
    case .notDetermined: .denied
    case .allowedAlways: .granted
    case .denied, .restricted: .blocked
    @unknown default: .denied
    }
  }
}
