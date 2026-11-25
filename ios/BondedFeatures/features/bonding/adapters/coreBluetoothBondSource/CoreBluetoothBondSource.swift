import CoreBluetooth
import Foundation

/// Answers `BondSource` with Core Bluetooth, through the shared Bluetooth client.
public struct CoreBluetoothBondSource: BondSource {
  private let central: BluetoothCentral

  public init(central: BluetoothCentral) {
    self.central = central
  }

  public func isReadable(_ trigger: BondTrigger, on id: DeviceId) async throws -> Bool? {
    do {
      let bits = try await central.characteristicProperties(
        try Self.identifier(id),
        service: trigger.service.rawValue,
        characteristic: trigger.characteristic.rawValue
      )
      return bits.map { CBCharacteristicProperties(rawValue: $0).contains(.read) }
    } catch {
      throw Self.notConnected(id)
    }
  }

  public func readTrigger(_ trigger: BondTrigger, on id: DeviceId) async throws {
    do {
      _ = try await central.read(try Self.identifier(id), service: trigger.service.rawValue, characteristic: trigger.characteristic.rawValue)
    } catch let error as CentralError {
      throw Self.bleError(error, trigger: trigger, id: id)
    } catch is CancellationError {
      throw BleError(code: .cancelled, message: "Pairing was cancelled", deviceId: id, operation: "createBond")
    }
  }

  private static func identifier(_ id: DeviceId) throws -> UUID {
    guard let identifier = UUID(uuidString: id.rawValue) else { throw notConnected(id) }
    return identifier
  }

  private static func notConnected(_ id: DeviceId) -> BleError {
    BleError(code: .notConnected, message: "The device isn't connected; connect it first", deviceId: id, operation: "createBond")
  }

  /// ATT errors that mean the device still wants encryption: pairing was declined or failed.
  private static let encryptionRefusals: Set<Int> = [
    CBATTError.insufficientAuthentication.rawValue,
    CBATTError.insufficientEncryptionKeySize.rawValue,
    CBATTError.insufficientEncryption.rawValue,
  ]

  /// Core Bluetooth's error during pairing in Bonded's words.
  static func bleError(_ error: CentralError, trigger: BondTrigger, id: DeviceId) -> BleError {
    switch error {
    case let .platform(info) where info.domain == CBATTErrorDomain && encryptionRefusals.contains(info.code):
      return BleError(
        code: .pairingRequired,
        message: "Pairing was declined or failed: the device still refuses the encrypted read of characteristic \(trigger.characteristic).",
        platformCode: info.code,
        deviceId: id,
        operation: "createBond"
      )
    case let .platform(info) where info.domain == CBErrorDomain && info.code == CBError.peerRemovedPairingInformation.rawValue:
      return BleError(
        code: .pairingRequired,
        message: "The device no longer has its bond with this phone. Remove the device in Settings › Bluetooth, then pair again.",
        platformCode: info.code,
        deviceId: id,
        operation: "createBond"
      )
    case let .platform(info) where info.domain == CBErrorDomain && info.code == CBError.encryptionTimedOut.rawValue:
      return BleError(code: .pairingRequired, message: "Pairing timed out.", platformCode: info.code, deviceId: id, operation: "createBond")
    case let .platform(info):
      return BleError(code: .gattError, message: info.message, platformCode: info.code, deviceId: id, operation: "createBond")
    case .noAnswer:
      return BleError(code: .timeout, message: "The device didn't answer during pairing within Bluetooth's 30 seconds", deviceId: id, operation: "createBond")
    case .notConnected, .notReady, .unknownPeripheral, .notFound:
      return BleError(code: .notConnected, message: "The device disconnected during pairing", deviceId: id, operation: "createBond")
    }
  }
}
