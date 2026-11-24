import BondedFeatures
import Foundation
import NitroModules

/// The `Gatt` HybridObject: calls the `gatt` feature's use cases and maps between them and the generated Nitro types.
/// Nothing else lives here (decision A5).
final class HybridGatt: HybridGattSpec {
  static func read(id: String, service: String, characteristic: String, timeoutMs: Double) throws -> any Promise<ArrayBuffer> {
    let address = try CharacteristicAddress(service, characteristic)
    return Promise_ArrayBuffer.async {
      do {
        let value = try await CompositionRoot.shared.readCharacteristic(address, on: DeviceId(id), timeout: .milliseconds(Int64(timeoutMs)))
        return try ArrayBuffer.copy(data: Data(value))
      } catch let error as BleError {
        throw RuntimeError(error.jsMessage)
      }
    }
  }

  static func write(
    id: String,
    service: String,
    characteristic: String,
    value: ArrayBuffer,
    withResponse: Bool,
    timeoutMs: Double
  ) throws -> any Promise<Void> {
    let address = try CharacteristicAddress(service, characteristic)
    let bytes = [UInt8](value.toData(copyIfNeeded: true))
    return Promise_void.async {
      do {
        try await CompositionRoot.shared.writeCharacteristic(
          bytes,
          to: address,
          on: DeviceId(id),
          withResponse: withResponse,
          timeout: .milliseconds(Int64(timeoutMs))
        )
      } catch let error as BleError {
        throw RuntimeError(error.jsMessage)
      }
    }
  }
}

extension CharacteristicAddress {
  /// From the UUIDs JavaScript passed, short or full; a malformed one rejects with a message naming it.
  init(_ service: String, _ characteristic: String) throws {
    guard let serviceUuid = ServiceUuid(service) else {
      throw RuntimeError(BleError(code: .unknown, message: "Not a service UUID: \(service)").jsMessage)
    }
    guard let characteristicUuid = CharacteristicUuid(characteristic) else {
      throw RuntimeError(BleError(code: .unknown, message: "Not a characteristic UUID: \(characteristic)").jsMessage)
    }
    self.init(service: serviceUuid, characteristic: characteristicUuid)
  }
}
