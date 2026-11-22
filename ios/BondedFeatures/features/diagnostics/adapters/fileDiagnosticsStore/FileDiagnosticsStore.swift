import Foundation

/// Answers `DiagnosticsStore` with a JSON file in the app's private Application Support folder.
public struct FileDiagnosticsStore: DiagnosticsStore {
  private let file: URL

  public init(directory: URL = FileDiagnosticsStore.defaultDirectory) {
    file = directory.appendingPathComponent("diagnostics.json")
  }

  public static var defaultDirectory: URL {
    let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
    return base.appendingPathComponent("com.devsilk.bonded", isDirectory: true)
  }

  public func load() async -> [DiagnosticEvent] {
    guard let data = try? Data(contentsOf: file) else { return [] }
    return (try? JSONDecoder().decode([DiagnosticEvent].self, from: data)) ?? []
  }

  public func save(_ events: [DiagnosticEvent]) async {
    guard let data = try? JSONEncoder().encode(events) else { return }
    try? FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
    try? data.write(to: file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
  }

  public func delete() async {
    try? FileManager.default.removeItem(at: file)
  }
}
