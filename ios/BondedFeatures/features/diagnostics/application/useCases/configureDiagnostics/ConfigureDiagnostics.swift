/// Turns diagnostics on or off and sets how many events to keep. Turning off deletes the stored events.
public struct ConfigureDiagnostics: Sendable {
  private let recorder: DiagnosticsRecorder

  public init(recorder: DiagnosticsRecorder) {
    self.recorder = recorder
  }

  public func callAsFunction(enabled: Bool?, capacity: Int?) async {
    await recorder.configure(enabled: enabled, capacity: capacity)
  }
}
