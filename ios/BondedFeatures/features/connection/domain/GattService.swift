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
  /// Its descriptors; `nil` unless the app asked for them.
  public let descriptors: [DescriptorUuid]?

  public init(uuid: CharacteristicUuid, properties: [CharacteristicProperty], descriptors: [DescriptorUuid]? = nil) {
    self.uuid = uuid
    self.properties = properties
    self.descriptors = descriptors
  }
}
