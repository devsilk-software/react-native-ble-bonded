import Foundation

/// Answers `RememberedDeviceStore` with the app's `UserDefaults`, as JSON under one key.
public struct UserDefaultsRememberedDeviceStore: RememberedDeviceStore {
  private static let key = "com.devsilk.bonded.rememberedDevices"

  public init() {}

  public func load() async -> [RememberedDevice] {
    guard let data = UserDefaults.standard.data(forKey: Self.key) else { return [] }
    return (try? JSONDecoder().decode([RememberedDevice].self, from: data)) ?? []
  }

  public func save(_ devices: [RememberedDevice]) async {
    guard let data = try? JSONEncoder().encode(devices) else { return }
    UserDefaults.standard.set(data, forKey: Self.key)
  }
}
