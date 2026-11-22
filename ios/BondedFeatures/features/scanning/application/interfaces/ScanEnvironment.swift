/// Whether the app is in the background, which decides whether an unfiltered scan may run (decision R3).
public protocol ScanEnvironment: Sendable {
  func isInBackground() async -> Bool

  /// `true` each time the app goes to the background, `false` each time it returns, from the moment this
  /// returns.
  func backgroundChanges() async -> AsyncStream<Bool>
}
