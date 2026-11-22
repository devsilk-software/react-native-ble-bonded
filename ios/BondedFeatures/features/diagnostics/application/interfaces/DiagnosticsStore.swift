/// Where recorded events survive the app being killed and relaunched. Implemented by a file in the app's
/// private storage, and by a fake in tests.
public protocol DiagnosticsStore: Sendable {
  func load() async -> [DiagnosticEvent]
  func save(_ events: [DiagnosticEvent]) async
  func delete() async
}
