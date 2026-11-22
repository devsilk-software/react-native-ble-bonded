import Foundation

/// Answers `DiagnosticsEnvironment` with the system clock and the app's foreground or background state.
public struct SystemDiagnosticsEnvironment: DiagnosticsEnvironment {
  private let activity: AppActivity

  public init(activity: AppActivity) {
    self.activity = activity
  }

  public func now() -> Int64 {
    Int64((Date().timeIntervalSince1970 * 1000).rounded())
  }

  public func appActivity() async -> DiagnosticEvent.AppActivity {
    await activity.isInBackground() ? .background : .foreground
  }
}
