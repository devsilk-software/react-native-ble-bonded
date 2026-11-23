import CoreBluetooth

/// Answers `ScanSource` with Core Bluetooth, through the shared Bluetooth client.
public struct CoreBluetoothScanSource: ScanSource {
  private let central: BluetoothCentral

  public init(central: BluetoothCentral) {
    self.central = central
  }

  public func readiness() async -> ScanReadiness {
    // Reading the state without permission never prompts; Bonded asks in `requestPermissions()`.
    guard central.authorization == .allowedAlways else { return .unauthorized }
    return switch await central.managerState() {
    case .poweredOn: .ready
    case .unsupported: .unsupported
    case .unauthorized: .unauthorized
    default: .bluetoothOff
    }
  }

  public func start(services: [ServiceUuid], allowDuplicates: Bool) async -> AsyncStream<Advertisement> {
    let discoveries = await central.startScan(
      services: services.isEmpty ? nil : services.map { CBUUID(string: $0.rawValue) },
      allowDuplicates: allowDuplicates
    )
    let (stream, continuation) = AsyncStream<Advertisement>.makeStream()
    let task = Task {
      for await discovery in discoveries {
        continuation.yield(Self.advertisement(from: discovery))
      }
      continuation.finish()
    }
    continuation.onTermination = { _ in task.cancel() }
    return stream
  }

  public func stop() async {
    await central.stopScan()
  }

  /// Core Bluetooth's discovery in Bonded's words. The advertised local name wins over the cached GATT name,
  /// because it's what the device says about itself right now.
  static func advertisement(from discovery: Discovery) -> Advertisement {
    let uuids = { (strings: [String]) in strings.compactMap(ServiceUuid.init) }
    return Advertisement(
      id: DeviceId(discovery.identifier.uuidString),
      name: discovery.localName ?? discovery.peripheralName,
      rssi: discovery.rssi,
      serviceUuids: uuids(discovery.serviceUuids),
      rawManufacturerData: discovery.manufacturerData.map { Array($0) },
      serviceData: discovery.serviceData.reduce(into: [:]) { result, entry in
        if let uuid = ServiceUuid(entry.key) { result[uuid] = Array(entry.value) }
      },
      txPowerLevel: discovery.txPowerLevel,
      isConnectable: discovery.isConnectable,
      solicitedServiceUuids: uuids(discovery.solicitedServiceUuids),
      overflowServiceUuids: uuids(discovery.overflowServiceUuids),
      timestamp: Int64(discovery.receivedAt.timeIntervalSince1970 * 1000)
    )
  }
}
