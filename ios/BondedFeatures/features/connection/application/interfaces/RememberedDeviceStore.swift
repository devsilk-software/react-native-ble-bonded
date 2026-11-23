/// Where remembered devices survive the app being killed. Implemented with the app's preferences, and by a fake in
/// tests.
public protocol RememberedDeviceStore: Sendable {
  func load() async -> [RememberedDevice]
  func save(_ devices: [RememberedDevice]) async
}
