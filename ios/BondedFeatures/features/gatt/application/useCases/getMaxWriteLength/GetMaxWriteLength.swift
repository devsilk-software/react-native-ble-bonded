/// The longest value one write carries without being split: the connection's MTU minus 3, agreed per connection.
/// Throws `notConnected` while the device isn't connected.
public struct GetMaxWriteLength: Sendable {
  private let source: any CharacteristicSource

  public init(source: any CharacteristicSource) {
    self.source = source
  }

  public func callAsFunction(on id: DeviceId) async throws -> Int {
    try await source.maxWriteLength(on: id)
  }
}
