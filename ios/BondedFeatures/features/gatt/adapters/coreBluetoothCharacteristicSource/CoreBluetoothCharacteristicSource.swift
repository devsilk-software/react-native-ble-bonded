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
    }
  }

  private static func identifier(_ id: DeviceId) throws -> UUID {
    guard let identifier = UUID(uuidString: id.rawValue) else { throw notConnected(id) }
    return identifier
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
