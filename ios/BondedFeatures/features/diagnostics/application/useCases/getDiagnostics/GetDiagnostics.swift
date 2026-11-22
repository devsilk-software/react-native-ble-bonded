/// The recorded events, oldest first; only those at or after `since` (milliseconds) when given.
public struct GetDiagnostics: Sendable {
  private let recorder: DiagnosticsRecorder

  public init(recorder: DiagnosticsRecorder) {
    self.recorder = recorder
  }

  public func callAsFunction(since: Int64?) async -> [DiagnosticEvent] {
    await recorder.events(since: since)
  }
}
