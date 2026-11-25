import BondedFeatures
import NitroModules

/// The `Bonding` HybridObject: calls the `bonding` feature's use case and maps between it and the generated Nitro
/// types. Nothing else lives here (decision A5). iOS has no API for an app to see or remove a bond, so the
/// Android-only calls reject with `unsupported`.
final class HybridBonding: HybridBondingSpec {
  static func createBond(id: String, service: String, characteristic: String) throws -> any Promise<Void> {
    guard let serviceUuid = ServiceUuid(service) else {
      throw RuntimeError(BleError(code: .unknown, message: "Not a service UUID: \(service)", operation: "createBond").jsMessage)
    }
    guard let characteristicUuid = CharacteristicUuid(characteristic) else {
      throw RuntimeError(BleError(code: .unknown, message: "Not a characteristic UUID: \(characteristic)", operation: "createBond").jsMessage)
    }
    let trigger = BondTrigger(service: serviceUuid, characteristic: characteristicUuid)
    return Promise_void.async {
      do {
        try await CompositionRoot.shared.createBond(trigger, on: DeviceId(id))
      } catch let error as BleError {
        throw RuntimeError(error.jsMessage)
      }
    }
  }

  static func getBondState(id: String) throws -> any Promise<BondState> {
    throw androidOnly("getBondState", id: id, instead: "iOS doesn't let apps see bonds.")
  }

  static func removeBond(id: String) throws -> any Promise<Void> {
    throw androidOnly("removeBond", id: id, instead: "On iOS the user removes the device in Settings › Bluetooth.")
  }

  static func addBondStateListener(id: String, onChange: any Callback<(BondState), Void>) throws -> any Promise<Double> {
    throw androidOnly("onBondStateChange", id: id, instead: "iOS doesn't let apps see bonds.")
  }

  static func removeBondStateListener(listener: Double) throws {}

  private static func androidOnly(_ operation: String, id: String, instead: String) -> RuntimeError {
    RuntimeError(BleError(
      code: .unsupported,
      message: "device.android.\(operation)() works on Android only. \(instead)",
      deviceId: DeviceId(id),
      operation: operation
    ).jsMessage)
  }
}
