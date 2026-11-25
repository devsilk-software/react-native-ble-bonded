/// The Bluetooth (ATT) errors a device can answer an operation with, by name, so an app reads what went wrong
/// without a table of numbers.
public enum DeviceRefusal {
  private static let names: [Int: (name: String, description: String)] = [
    0x01: ("invalidHandle", "the characteristic's handle isn't valid"),
    0x02: ("readNotPermitted", "the characteristic can't be read"),
    0x03: ("writeNotPermitted", "the characteristic can't be written"),
    0x04: ("invalidPdu", "the request was malformed"),
    0x05: ("insufficientAuthentication", "the characteristic needs a paired device"),
    0x06: ("requestNotSupported", "the device doesn't support this request"),
    0x07: ("invalidOffset", "the offset is past the value's end"),
    0x08: ("insufficientAuthorization", "the device didn't authorize this request"),
    0x09: ("prepareQueueFull", "too many long writes are queued on the device"),
    0x0A: ("attributeNotFound", "the device has no such attribute"),
    0x0B: ("attributeNotLong", "the value can't be read or written in parts"),
    0x0C: ("insufficientEncryptionKeySize", "the encryption key is too short"),
    0x0D: ("invalidAttributeValueLength", "the device refused the value's length"),
    0x0E: ("unlikelyError", "the device failed in an unlikely way"),
    0x0F: ("insufficientEncryption", "the characteristic needs a paired, encrypted connection"),
    0x10: ("unsupportedGroupType", "the device doesn't support this grouping"),
    0x11: ("insufficientResources", "the device ran out of resources"),
  ]

  /// Codes that mean the device wants a paired, encrypted connection first.
  private static let pairing: Set<Int> = [0x05, 0x0C, 0x0F]

  /// The `BleError` for an ATT error code the device answered `operation` with.
  /// For a descriptor operation, `descriptor` is the one the device refused, and the message names it.
  public static func error(
    code: Int,
    operation: CharacteristicOperation,
    address: CharacteristicAddress,
    id: DeviceId,
    descriptor: DescriptorUuid? = nil
  ) -> BleError {
    let what = what(operation, address, descriptor)
    if (0x80...0x9F).contains(code) {
      let hex = "0x" + String(code, radix: 16)
      return BleError(
        code: .gattError,
        message: "The device refused \(what) with its own error \(hex); look it up in the firmware's error list.",
        platformCode: code,
        deviceId: id,
        operation: operation.name,
        attError: "applicationError"
      )
    }
    let known = names[code] ?? ("unknown", "the device refused it with an unknown error \(code)")
    // The descriptions speak of the characteristic; for a descriptor, the descriptor is what the device refused.
    let description = descriptor == nil ? known.description : known.description.replacingOccurrences(of: "the characteristic", with: "the descriptor")
    return BleError(
      code: pairing.contains(code) ? .pairingRequired : .gattError,
      message: "The device refused \(what): \(description).",
      platformCode: code,
      deviceId: id,
      operation: operation.name,
      attError: known.name
    )
  }

  /// What an error message calls the operation: "the read of characteristic 2a37", or for a descriptor "the write of
  /// descriptor 2901 on characteristic 2a37".
  public static func what(_ operation: CharacteristicOperation, _ address: CharacteristicAddress, _ descriptor: DescriptorUuid?) -> String {
    guard let descriptor else { return "the \(operation.name) of characteristic \(address.characteristic)" }
    let verb = operation == .writeDescriptor ? "write" : "read"
    return "the \(verb) of descriptor \(descriptor) on characteristic \(address.characteristic)"
  }
}
