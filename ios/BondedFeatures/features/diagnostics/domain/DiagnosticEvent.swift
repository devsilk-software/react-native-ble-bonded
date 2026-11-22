/// One recorded event: metadata only, never characteristic values.
public struct DiagnosticEvent: Sendable, Equatable, Codable {
  public enum Kind: String, Sendable, Codable, CaseIterable {
    case stateChanged
    case permissionChanged
    case scanStarted
    case scanStopped
  }

  /// Whether the app was in the foreground or the background when the event happened.
  public enum AppActivity: String, Sendable, Codable {
    case foreground
    case background
  }

  public let kind: Kind
  /// Milliseconds since the Unix epoch.
  public let at: Int64
  public let appActivity: AppActivity
  public let deviceId: String?
  public let code: String?
  public let platformCode: Int?
  public let detail: String?

  public init(
    kind: Kind,
    at: Int64,
    appActivity: AppActivity,
    deviceId: String? = nil,
    code: String? = nil,
    platformCode: Int? = nil,
    detail: String? = nil
  ) {
    self.kind = kind
    self.at = at
    self.appActivity = appActivity
    self.deviceId = deviceId
    self.code = code
    self.platformCode = platformCode
    self.detail = detail
  }
}
