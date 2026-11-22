/// Something that happened in one feature that another may react to (decision A2). Features publish these on
/// the `EventBus` without knowing who listens.
public struct DomainEvent: Sendable, Equatable {
  public enum Kind: String, Sendable, CaseIterable {
    case stateChanged
    case permissionChanged
  }

  public let kind: Kind
  public let deviceId: DeviceId?
  /// The new value or other short detail, in Bonded's public words (`poweredOff`, `blocked`).
  public let detail: String?
  public let errorCode: BleError.Code?
  public let platformCode: Int?

  public init(
    kind: Kind,
    deviceId: DeviceId? = nil,
    detail: String? = nil,
    errorCode: BleError.Code? = nil,
    platformCode: Int? = nil
  ) {
    self.kind = kind
    self.deviceId = deviceId
    self.detail = detail
    self.errorCode = errorCode
    self.platformCode = platformCode
  }
}
