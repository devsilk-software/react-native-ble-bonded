/// Deletes the recorded events, in memory and in storage.
public struct ClearDiagnostics: Sendable {
  private let recorder: DiagnosticsRecorder

  public init(recorder: DiagnosticsRecorder) {
    self.recorder = recorder
  }

  public func callAsFunction() async {
    await recorder.clear()
  }
}
