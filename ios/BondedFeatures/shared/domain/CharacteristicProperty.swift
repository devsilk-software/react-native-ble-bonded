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
