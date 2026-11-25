/// A characteristic on a device: the service it belongs to and its own UUID.
public struct CharacteristicAddress: Sendable, Equatable, Hashable {
  public let service: ServiceUuid
  public let characteristic: CharacteristicUuid

  public init(service: ServiceUuid, characteristic: CharacteristicUuid) {
    self.service = service
    self.characteristic = characteristic
  }
}

/// What the app asks of a characteristic.
public enum CharacteristicOperation: Sendable, Equatable {
  case read
  case write
  case writeWithoutResponse
  case subscribe
  case readDescriptor
  case writeDescriptor

  /// The properties of which the characteristic needs one for this operation.
  public var properties: [CharacteristicProperty] {
    switch self {
    case .read: [.read]
    case .write: [.write]
    case .writeWithoutResponse: [.writeWithoutResponse]
    case .subscribe: [.notify, .indicate]
    case .readDescriptor, .writeDescriptor: []
    }
  }

  public var name: String {
    switch self {
    case .read: "read"
    case .write: "write"
    case .writeWithoutResponse: "write without response"
    case .subscribe: "subscribe"
    case .readDescriptor: "descriptor read"
    case .writeDescriptor: "descriptor write"
    }
  }
}

/// The longest value a write with response may carry: Bluetooth's limit for a characteristic's value.
public let maxAttributeLength = 512

/// The checks before anything is sent. Each failure names the problem, the values involved and what to do.
public enum OperationCheck {
  /// `properties` is `nil` when the device has no such characteristic.
  public static func failure(
    of operation: CharacteristicOperation,
    on address: CharacteristicAddress,
    of id: DeviceId,
    properties: [CharacteristicProperty]?,
    length: Int = 0,
    maxWriteLength: Int = maxAttributeLength
  ) -> BleError? {
    let what = "characteristic \(address.characteristic) in service \(address.service)"
    guard let properties else {
      return BleError(
        code: .characteristicNotFound,
        message: "The device has no \(what). device.getServices() lists the services and characteristics it has.",
        deviceId: id,
        operation: operation.name
      )
    }
    guard operation.properties.contains(where: properties.contains) else {
      let allowed = properties.isEmpty ? "nothing" : properties.map(\.rawValue).joined(separator: ", ")
      return BleError(
        code: .unsupported,
        message: "The \(what) doesn't allow \(operation.name); it allows \(allowed).",
        deviceId: id,
        operation: operation.name
      )
    }
    switch operation {
    case .read, .subscribe:
      return nil
    case .write where length > maxAttributeLength:
      return BleError(
        code: .valueTooLong,
        message: "Value is \(length) bytes; a write with response allows at most \(maxAttributeLength), Bluetooth's limit for a characteristic. Split it into writes your firmware puts back together.",
        deviceId: id,
        operation: operation.name,
        length: length,
        maxLength: maxAttributeLength
      )
    case .writeWithoutResponse where length > maxWriteLength:
      return BleError(
        code: .valueTooLong,
        message: "Value is \(length) bytes; a write without response to this device allows at most \(maxWriteLength). Split it into pieces your firmware puts back together, or write with response (up to \(maxAttributeLength) bytes).",
        deviceId: id,
        operation: operation.name,
        length: length,
        maxLength: maxWriteLength
      )
    default:
      return nil
    }
  }
}
