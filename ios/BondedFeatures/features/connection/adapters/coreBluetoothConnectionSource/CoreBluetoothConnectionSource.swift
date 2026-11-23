import CoreBluetooth
import Foundation

/// Answers `ConnectionSource` with Core Bluetooth, through the shared Bluetooth client. Core Bluetooth waits for
/// a device without a timeout, so every attempt waits; nothing on iOS is worth retrying.
public struct CoreBluetoothConnectionSource: ConnectionSource {
  private let central: BluetoothCentral

  public init(central: BluetoothCentral) {
    self.central = central
  }

  public func readiness() async -> ConnectionReadiness {
    guard central.authorization == .allowedAlways else { return .unauthorized }
    return switch await central.managerState() {
    case .poweredOn: .ready
    case .unsupported: .unsupported
    case .unauthorized: .unauthorized
    default: .bluetoothOff
    }
  }

  public func attempt(_ id: DeviceId, mode: ConnectMode) async throws -> [GattService] {
    guard let identifier = UUID(uuidString: id.rawValue) else { throw Self.notFound(id) }
    do {
      try await central.connect(identifier)
      return try await central.discover(identifier).map(Self.service)
    } catch let error as CentralError {
      throw Self.bleError(error, id: id)
    }
  }

  public func disconnect(_ id: DeviceId) async {
    guard let identifier = UUID(uuidString: id.rawValue) else { return }
    await central.disconnect(identifier)
  }

  public func disconnections() async -> AsyncStream<PlatformDisconnect> {
    let platform = await central.disconnections()
    let (stream, continuation) = AsyncStream<PlatformDisconnect>.makeStream()
    let task = Task {
      for await disconnection in platform {
        let id = DeviceId(disconnection.identifier.uuidString)
        continuation.yield(PlatformDisconnect(id: id, disconnect: Self.disconnect(disconnection, id: id)))
      }
      continuation.finish()
    }
    continuation.onTermination = { _ in task.cancel() }
    return stream
  }

  public func isTransient(_ error: BleError) -> Bool { false }

  public func name(of id: DeviceId) async -> String? {
    guard let identifier = UUID(uuidString: id.rawValue) else { return nil }
    return await central.peripheralName(identifier)
  }

  // MARK: - Core Bluetooth in Bonded's words

  /// Why a peripheral disconnected, from Core Bluetooth's error.
  static func disconnect(_ disconnection: CentralDisconnect, id: DeviceId) -> Disconnect {
    if disconnection.isBluetoothOff {
      return Disconnect(reason: .bluetoothOff, error: BleError(code: .bluetoothOff, message: "Bluetooth was switched off", deviceId: id))
    }
    let error = disconnection.error
    let reason: DisconnectReason =
      switch error.flatMap({ $0.domain == CBErrorDomain ? CBError.Code(rawValue: $0.code) : nil }) {
      case .connectionTimeout?: .outOfRange
      case .peripheralDisconnected?: .deviceDisconnected
      default: .failed
      }
    return Disconnect(
      reason: reason,
      error: BleError(code: .disconnected, message: error?.message ?? "Disconnected", platformCode: error?.code, deviceId: id)
    )
  }

  static func bleError(_ error: CentralError, id: DeviceId) -> BleError {
    switch error {
    case .notReady: BleError(code: .bluetoothOff, message: "Bluetooth is off", deviceId: id, operation: "connect")
    case .unknownPeripheral: notFound(id)
    case .notConnected: BleError(code: .notConnected, message: "The device isn't connected", deviceId: id, operation: "connect")
    case let .platform(info):
      BleError(code: .connectionFailed, message: info.message, platformCode: info.code, deviceId: id, operation: "connect")
    }
  }

  private static func notFound(_ id: DeviceId) -> BleError {
    BleError(code: .deviceNotFound, message: "No device with this ID is known; scan for it first", deviceId: id, operation: "connect")
  }

  static func service(_ discovered: DiscoveredService) -> GattService {
    GattService(
      uuid: ServiceUuid(discovered.uuid) ?? ServiceUuid("0000")!,
      characteristics: discovered.characteristics.compactMap { characteristic in
        CharacteristicUuid(characteristic.uuid).map {
          GattCharacteristic(uuid: $0, properties: properties(CBCharacteristicProperties(rawValue: characteristic.properties)))
        }
      }
    )
  }

  static func properties(_ properties: CBCharacteristicProperties) -> [CharacteristicProperty] {
    let pairs: [(CBCharacteristicProperties, CharacteristicProperty)] = [
      (.broadcast, .broadcast),
      (.read, .read),
      (.writeWithoutResponse, .writeWithoutResponse),
      (.write, .write),
      (.notify, .notify),
      (.indicate, .indicate),
      (.authenticatedSignedWrites, .authenticatedSignedWrites),
      (.extendedProperties, .extendedProperties),
    ]
    return pairs.filter { properties.contains($0.0) }.map(\.1)
  }
}
