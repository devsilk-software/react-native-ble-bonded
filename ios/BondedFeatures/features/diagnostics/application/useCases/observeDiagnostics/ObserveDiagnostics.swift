/// Every event recorded from now on.
public struct ObserveDiagnostics: Sendable {
  private let recorder: DiagnosticsRecorder

  public init(recorder: DiagnosticsRecorder) {
    self.recorder = recorder
  }

  /// Returns once the observer is registered, so no event recorded after that is missed.
  public func callAsFunction() async -> AsyncStream<DiagnosticEvent> {
    await recorder.liveEvents()
  }
}
