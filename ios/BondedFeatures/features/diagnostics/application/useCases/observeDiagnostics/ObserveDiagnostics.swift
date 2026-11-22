/// Every event recorded from now on.
public struct ObserveDiagnostics: Sendable {
  private let recorder: DiagnosticsRecorder

  public init(recorder: DiagnosticsRecorder) {
    self.recorder = recorder
  }

  public func callAsFunction() -> AsyncStream<DiagnosticEvent> {
    recorder.liveEvents()
  }
}
