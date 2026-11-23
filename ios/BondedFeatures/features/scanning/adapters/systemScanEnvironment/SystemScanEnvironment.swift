/// Answers `ScanEnvironment` with the app's foreground and background state.
public struct SystemScanEnvironment: ScanEnvironment {
  private let activity: AppLifecycle

  public init(activity: AppLifecycle) {
    self.activity = activity
  }

  public func isInBackground() async -> Bool {
    await activity.isInBackground()
  }

  public func backgroundChanges() async -> AsyncStream<Bool> {
    await activity.backgroundChanges()
  }
}
