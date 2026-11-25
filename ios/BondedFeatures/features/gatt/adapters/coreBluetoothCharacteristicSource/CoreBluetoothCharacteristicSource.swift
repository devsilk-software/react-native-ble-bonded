import CoreBluetooth
import Foundation

/// Answers `CharacteristicSource` with Core Bluetooth, through the shared Bluetooth client.
public struct CoreBluetoothCharacteristicSource: CharacteristicSource {
  private let central: BluetoothCentral

  public init(central: BluetoothCentral) {
    self.central = central
  }

  public func properties(of address: CharacteristicAddress, on id: DeviceId) async throws -> [CharacteristicProperty]? {
    let identifier = try Self.identifier(id)
    do {
      let bits = try await central.characteristicProperties(
        identifier,
        service: address.service.rawValue,
        characteristic: address.characteristic.rawValue
      )
      return bits.map { Self.properties(CBCharacteristicProperties(rawValue: $0)) }
    } catch {
      throw Self.notConnected(id)
    }
  }

  public func maxWriteLength(on id: DeviceId) async throws -> Int {
    do {
      return try await central.maximumWriteLength(try Self.identifier(id))
    } catch {
      throw Self.notConnected(id)
    }
  }

  public func read(_ address: CharacteristicAddress, on id: DeviceId) async throws -> [UInt8] {
    do {
      let data = try await central.read(
        try Self.identifier(id),
        service: address.service.rawValue,
        characteristic: address.characteristic.rawValue
      )
      return Array(data)
    } catch let error as CentralError {
      throw Self.bleError(error, operation: .read, address: address, id: id)
    } catch is CancellationError {
      throw Self.cancelled(.read, id: id)
    }
  }

  public func write(_ value: [UInt8], to address: CharacteristicAddress, on id: DeviceId, withResponse: Bool) async throws {
    do {
      try await central.write(
        Data(value),
        to: try Self.identifier(id),
        service: address.service.rawValue,
        characteristic: address.characteristic.rawValue,
        withResponse: withResponse
      )
    } catch let error as CentralError {
      throw Self.bleError(error, operation: withResponse ? .write : .writeWithoutResponse, address: address, id: id)
    } catch is CancellationError {
      throw Self.cancelled(withResponse ? .write : .writeWithoutResponse, id: id)
    }
  }

  public func write(
    pieces: [[UInt8]],
    to address: CharacteristicAddress,
    on id: DeviceId,
    withResponse: Bool,
    timeout: Duration
  ) async throws {
    let operation: CharacteristicOperation = withResponse ? .write : .writeWithoutResponse
    do {
      try await central.write(
        pieces: pieces.map { Data($0) },
        to: try Self.identifier(id),
        service: address.service.rawValue,
        characteristic: address.characteristic.rawValue,
        withResponse: withResponse,
        limit: timeout
      )
    } catch let failure as PieceError {
      let error =
        failure.error.map { Self.bleError($0, operation: operation, address: address, id: id) }
          ?? BleError(
            code: .timeout,
            message: "The device didn't take the \(operation.name) of characteristic \(address.characteristic) within \(timeout)",
            deviceId: id,
            operation: operation.name
          )
      throw PieceFailure(index: failure.index, error: error)
    } catch let error as CentralError {
      // Failed before the write got its turn, so no chunk went out.
      throw PieceFailure(index: 0, error: Self.bleError(error, operation: operation, address: address, id: id))
    } catch is CancellationError {
      throw Self.cancelled(operation, id: id)
    }
  }

  public func descriptors(of address: CharacteristicAddress, on id: DeviceId) async throws -> [DescriptorUuid]? {
    do {
      let uuids = try await central.descriptorUuids(
        try Self.identifier(id),
        service: address.service.rawValue,
        characteristic: address.characteristic.rawValue
      )
      return uuids?.compactMap(DescriptorUuid.init)
    } catch let error as CentralError {
      throw Self.bleError(error, operation: .readDescriptor, address: address, id: id)
    } catch is CancellationError {
      throw Self.cancelled(.readDescriptor, id: id)
    }
  }

  public func readDescriptor(_ descriptor: DescriptorUuid, of address: CharacteristicAddress, on id: DeviceId) async throws -> [UInt8] {
    do {
      let data = try await central.readDescriptor(
        try Self.identifier(id),
        service: address.service.rawValue,
        characteristic: address.characteristic.rawValue,
        descriptor: descriptor.rawValue
      )
      return Array(data)
    } catch let error as CentralError {
      throw Self.bleError(error, operation: .readDescriptor, address: address, id: id)
    } catch is CancellationError {
      throw Self.cancelled(.readDescriptor, id: id)
    }
  }

  public func writeDescriptor(_ value: [UInt8], to descriptor: DescriptorUuid, of address: CharacteristicAddress, on id: DeviceId) async throws {
    do {
      try await central.writeDescriptor(
        Data(value),
        to: try Self.identifier(id),
        service: address.service.rawValue,
        characteristic: address.characteristic.rawValue,
        descriptor: descriptor.rawValue
      )
    } catch let error as CentralError {
      throw Self.bleError(error, operation: .writeDescriptor, address: address, id: id)
    } catch is CancellationError {
      throw Self.cancelled(.writeDescriptor, id: id)
    }
  }

  public func readRssi(on id: DeviceId) async throws -> Int {
    do {
      return try await central.readRssi(try Self.identifier(id))
    } catch let error as CentralError {
      switch error {
      case let .platform(info):
        throw BleError(code: .gattError, message: info.message, platformCode: info.code, deviceId: id, operation: "readRssi")
      default:
        throw Self.notConnected(id)
      }
    } catch is CancellationError {
      throw BleError(code: .cancelled, message: "Reading the signal strength was cancelled", deviceId: id, operation: "readRssi")
    }
  }

  public func setNotifications(_ isOn: Bool, indicate: Bool, on address: CharacteristicAddress, of id: DeviceId) async throws {
    do {
      // Core Bluetooth picks notifications or indications itself.
      try await central.setNotify(
        isOn,
        try Self.identifier(id),
        service: address.service.rawValue,
        characteristic: address.characteristic.rawValue
      )
    } catch let error as CentralError {
      throw Self.bleError(error, operation: .subscribe, address: address, id: id)
    } catch is CancellationError {
      throw Self.cancelled(.subscribe, id: id)
    }
  }

  public func notifications() async -> AsyncStream<CharacteristicNotification> {
    let platform = await central.notifications()
    let (stream, continuation) = AsyncStream<CharacteristicNotification>.makeStream()
    let task = Task {
      for await notification in platform {
        let parts = notification.key.split(separator: "|").map(String.init)
        guard parts.count == 2, let service = ServiceUuid(parts[0]), let characteristic = CharacteristicUuid(parts[1]) else { continue }
        continuation.yield(CharacteristicNotification(
          id: DeviceId(notification.identifier.uuidString),
          address: CharacteristicAddress(service: service, characteristic: characteristic),
          value: Array(notification.value)
        ))
      }
      continuation.finish()
    }
    continuation.onTermination = { _ in task.cancel() }
    return stream
  }

  private static func identifier(_ id: DeviceId) throws -> UUID {
    guard let identifier = UUID(uuidString: id.rawValue) else { throw notConnected(id) }
    return identifier
  }

  private static func cancelled(_ operation: CharacteristicOperation, id: DeviceId) -> BleError {
    BleError(code: .cancelled, message: "The \(operation.name) was cancelled", deviceId: id, operation: operation.name)
  }

  private static func notConnected(_ id: DeviceId) -> BleError {
    BleError(code: .notConnected, message: "The device isn't connected; connect it first", deviceId: id)
  }

  /// Core Bluetooth's error in Bonded's words: the device's own ATT errors by name, the rest as GATT errors.
  static func bleError(_ error: CentralError, operation: CharacteristicOperation, address: CharacteristicAddress, id: DeviceId) -> BleError {
    switch error {
    case let .platform(info) where info.domain == CBATTErrorDomain:
      return DeviceRefusal.error(code: info.code, operation: operation, address: address, id: id)
    case let .platform(info):
      return BleError(code: .gattError, message: info.message, platformCode: info.code, deviceId: id, operation: operation.name)
    case .notConnected, .notReady, .unknownPeripheral:
      return BleError(code: .notConnected, message: "The device disconnected during the \(operation.name)", deviceId: id, operation: operation.name)
    case .noAnswer:
      return BleError(
        code: .timeout,
        message: "The device didn't answer the \(operation.name) of characteristic \(address.characteristic) within Bluetooth's 30 seconds",
        deviceId: id,
        operation: operation.name
      )
    }
  }

  static func properties(_ properties: CBCharacteristicProperties) -> [CharacteristicProperty] {
    let pairs: [(CBCharacteristicProperties, CharacteristicProperty)] = [
      (.broadcast, .broadcast), (.read, .read), (.writeWithoutResponse, .writeWithoutResponse), (.write, .write),
      (.notify, .notify), (.indicate, .indicate), (.authenticatedSignedWrites, .authenticatedSignedWrites),
      (.extendedProperties, .extendedProperties),
    ]
    return pairs.filter { properties.contains($0.0) }.map(\.1)
  }
}
