/// The checks before a descriptor is read or written. Each failure names the problem and what to do.
public enum DescriptorCheck {
  /// `descriptors` is `nil` when the device has no such characteristic.
  public static func failure(
    of operation: CharacteristicOperation,
    _ descriptor: DescriptorUuid,
    of address: CharacteristicAddress,
    on id: DeviceId,
    descriptors: [DescriptorUuid]?,
    length: Int = 0
  ) -> BleError? {
    guard let descriptors else {
      return BleError(
        code: .characteristicNotFound,
        message: "The device has no characteristic \(address.characteristic) in service \(address.service). device.getServices() lists the services and characteristics it has.",
        deviceId: id,
        operation: operation.name
      )
    }
    guard descriptors.contains(descriptor) else {
      let present = descriptors.isEmpty ? "none" : descriptors.map(\.rawValue).joined(separator: ", ")
      return BleError(
        code: .descriptorNotFound,
        message: "Characteristic \(address.characteristic) has no descriptor \(descriptor); it has \(present). device.getServices({ descriptors: true }) lists them.",
        deviceId: id,
        operation: operation.name
      )
    }
    if operation == .writeDescriptor, descriptor == .clientConfiguration {
      return BleError(
        code: .unsupported,
        message: "The notification descriptor (2902) is Bonded's to write: device.subscribe() switches notifications on, and its remove() off.",
        deviceId: id,
        operation: operation.name
      )
    }
    if operation == .writeDescriptor, length > maxAttributeLength {
      return BleError(
        code: .valueTooLong,
        message: "Value is \(length) bytes; a descriptor allows at most \(maxAttributeLength), Bluetooth's limit for a value.",
        deviceId: id,
        operation: operation.name,
        length: length,
        maxLength: maxAttributeLength
      )
    }
    return nil
  }
}
