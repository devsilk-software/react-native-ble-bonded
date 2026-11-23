/// A service the device offers, with its characteristics.
public struct GattService: Sendable, Equatable {
  public let uuid: ServiceUuid
  public let characteristics: [GattCharacteristic]

  public init(uuid: ServiceUuid, characteristics: [GattCharacteristic]) {
    self.uuid = uuid
    self.characteristics = characteristics
  }
}

public struct GattCharacteristic: Sendable, Equatable {
  public let uuid: CharacteristicUuid
  public let properties: [CharacteristicProperty]

  public init(uuid: CharacteristicUuid, properties: [CharacteristicProperty]) {
    self.uuid = uuid
    self.properties = properties
  }
}

/// What a characteristic allows.
public enum CharacteristicProperty: String, Sendable, Equatable, CaseIterable {
  case broadcast
  case read
  case writeWithoutResponse
  case write
  case notify
  case indicate
  case authenticatedSignedWrites
  case extendedProperties
}
