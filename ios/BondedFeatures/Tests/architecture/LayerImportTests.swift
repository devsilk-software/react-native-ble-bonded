import Foundation
import Testing

/// The dependency rule (docs/architecture.md), checked on every Swift file's imports. The same rules run on
/// Android as Konsist tests.
@Suite("Architecture")
struct LayerImportTests {
  /// What each layer may import. `nil` means anything.
  private static let allowedImports: [(layer: String, allowed: Set<String>?)] = [
    ("shared/domain", ["Foundation"]),
    ("shared/application", ["Foundation"]),
    ("/domain/", ["Foundation"]),
    ("/application/", ["Foundation"]),
    ("/adapters/", ["Foundation", "CoreBluetooth"]),
    ("infrastructure/", ["Foundation", "CoreBluetooth", "Dispatch"]),
  ]

  /// Modules no file in the package may import: they belong to the Nitro controllers.
  private static let forbiddenEverywhere: Set<String> = ["NitroModules", "React", "UIKit", "SwiftUI"]

  @Test("the rules see the production sources")
  func theRulesSeeTheProductionSources() throws {
    #expect(try Self.sourceFiles().contains { $0.feature != nil })
  }

  @Test("no layer imports more than it may")
  func noLayerImportsMoreThanItMay() throws {
    var violations: [String] = []
    for file in try Self.sourceFiles() {
      let imports = try Self.imports(of: file)
      for module in imports where Self.forbiddenEverywhere.contains(module) {
        violations.append("\(file.relative) imports \(module)")
      }
      guard let rule = Self.allowedImports.first(where: { file.relative.contains($0.layer) }),
            let allowed = rule.allowed else { continue }
      for module in imports where !allowed.contains(module) {
        violations.append("\(file.relative) (\(rule.layer)) imports \(module)")
      }
    }
    #expect(violations.isEmpty, "\(violations.joined(separator: "\n"))")
  }

  @Test("no feature uses another feature's code")
  func noFeatureUsesAnotherFeaturesCode() throws {
    let files = try Self.sourceFiles()
    var typesByFeature: [String: Set<String>] = [:]
    for file in files {
      guard let feature = file.feature else { continue }
      typesByFeature[feature, default: []].formUnion(try Self.declaredTypes(in: file))
    }
    var violations: [String] = []
    for file in files {
      guard let feature = file.feature else { continue }
      let source = try String(contentsOf: file.url, encoding: .utf8)
      for (other, types) in typesByFeature where other != feature {
        for type in types where source.range(of: "\\b\(type)\\b", options: .regularExpression) != nil {
          violations.append("\(file.relative) (\(feature)) uses \(type) from \(other)")
        }
      }
    }
    #expect(violations.isEmpty, "\(violations.joined(separator: "\n"))")
  }

  // MARK: - Reading the sources

  private struct SourceFile {
    let url: URL
    let relative: String
    var feature: String? {
      let parts = relative.split(separator: "/")
      guard parts.count > 1, parts[0] == "features" else { return nil }
      return String(parts[1])
    }
  }

  private static func packageRoot() -> URL {
    // Tests/architecture/LayerImportTests.swift → the package root.
    URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
  }

  private static func sourceFiles() throws -> [SourceFile] {
    let root = packageRoot()
    var files: [SourceFile] = []
    for folder in ["shared", "features", "infrastructure", "compositionRoot"] {
      let url = root.appendingPathComponent(folder)
      guard let enumerator = FileManager.default.enumerator(at: url, includingPropertiesForKeys: nil) else { continue }
      for case let file as URL in enumerator where file.pathExtension == "swift" {
        let relative = String(file.path.dropFirst(root.path.count + 1))
        files.append(SourceFile(url: file, relative: relative))
      }
    }
    return files
  }

  private static func imports(of file: SourceFile) throws -> [String] {
    try String(contentsOf: file.url, encoding: .utf8)
      .split(separator: "\n")
      .compactMap { line -> String? in
        let words = line.trimmingCharacters(in: .whitespaces).split(separator: " ")
        guard let index = words.firstIndex(of: "import"), index + 1 < words.count else { return nil }
        guard words[..<index].allSatisfy({ $0.hasPrefix("@") || ["public", "internal", "private"].contains($0) })
        else { return nil }
        return String(words[index + 1])
      }
  }

  private static func declaredTypes(in file: SourceFile) throws -> Set<String> {
    let source = try String(contentsOf: file.url, encoding: .utf8)
    let pattern = #"(?:struct|class|enum|protocol|actor)\s+([A-Z][A-Za-z0-9]*)"#
    let regex = try NSRegularExpression(pattern: pattern)
    let range = NSRange(source.startIndex..., in: source)
    return Set(regex.matches(in: source, range: range).compactMap { match in
      Range(match.range(at: 1), in: source).map { String(source[$0]) }
    })
  }
}
