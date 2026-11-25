/// The characteristic whose read starts pairing: one that needs an encrypted connection.
public struct BondTrigger: Sendable, Equatable {
  public let service: ServiceUuid
  public let characteristic: CharacteristicUuid

  public init(service: ServiceUuid, characteristic: CharacteristicUuid) {
    self.service = service
    self.characteristic = characteristic
  }
}
