/// The platform's scanner, in Bonded's words. Implemented with Core Bluetooth, and by a fake in tests.
public protocol ScanSource: Sendable {
  func readiness() async -> ScanReadiness

  /// Starts scanning and returns every advertisement from now on. The stream finishes when scanning stops,
  /// whether through `stop()` or because Bluetooth went off or permission was revoked.
  func start(services: [ServiceUuid], allowDuplicates: Bool) async -> AsyncStream<Advertisement>

  func stop() async
}
