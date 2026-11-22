/// The time and the app's foreground or background state, which every recorded event carries.
public protocol DiagnosticsEnvironment: Sendable {
  /// Milliseconds since the Unix epoch.
  func now() -> Int64
  func appActivity() async -> DiagnosticEvent.AppActivity
}
