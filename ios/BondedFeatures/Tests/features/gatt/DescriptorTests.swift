import Testing
@testable import BondedFeatures

@Suite("Descriptors")
struct DescriptorTests {
  private let device = DeviceId("A")
  private let userDescription = DescriptorUuid("2901")!
  private let custom = DescriptorUuid("B0ED0005-0000-4000-8000-00000000B0ED")!

  private func source() async -> FakeCharacteristicSource {
    let source = FakeCharacteristicSource(characteristics: [testCharacteristic: [.read, .write, .notify]])
    await source.set(descriptor: userDescription, Array("Counter".utf8), at: testCharacteristic)
    await source.set(descriptor: .clientConfiguration, [0, 0], at: testCharacteristic)
    await source.set(descriptor: custom, [], at: testCharacteristic)
    return source
  }

  @Test("reads a descriptor's value")
  func readsADescriptorsValue() async throws {
    let value = try await ReadDescriptor(source: await source(), bus: EventBus())(userDescription, of: testCharacteristic, on: device)

    #expect(value == Array("Counter".utf8))
  }

  @Test("writes a descriptor's value")
  func writesADescriptorsValue() async throws {
    let source = await source()

    try await WriteDescriptor(source: source, bus: EventBus())([1, 2], to: custom, of: testCharacteristic, on: device)

    #expect(await source.descriptorWrites.map(\.value) == [[1, 2]])
    #expect(await source.descriptorWrites.map(\.descriptor) == [custom])
  }

  @Test("refuses a descriptor the characteristic doesn't have, naming the ones it has")
  func refusesADescriptorTheCharacteristicDoesntHave() async {
    let read = ReadDescriptor(source: await source(), bus: EventBus())

    await #expect { try await read(DescriptorUuid("2904")!, of: testCharacteristic, on: device) } throws: {
      let error = $0 as? BleError
      return error?.code == .descriptorNotFound && error?.message.contains("00002901") == true
        && error?.message.contains("getServices({ descriptors: true })") == true
    }
    await #expect { try await read(userDescription, of: readOnlyCharacteristic, on: device) } throws: {
      ($0 as? BleError)?.code == .characteristicNotFound
    }
  }

  @Test("refuses to write the notification descriptor, pointing to subscribe")
  func refusesToWriteTheNotificationDescriptor() async throws {
    let source = await source()
    let write = WriteDescriptor(source: source, bus: EventBus())

    await #expect { try await write([1, 0], to: .clientConfiguration, of: testCharacteristic, on: device) } throws: {
      let error = $0 as? BleError
      return error?.code == .unsupported && error?.message.contains("device.subscribe()") == true
    }
    #expect(await source.descriptorWrites.isEmpty)
    #expect(try await ReadDescriptor(source: source, bus: EventBus())(.clientConfiguration, of: testCharacteristic, on: device) == [0, 0])
  }

  @Test("gives up on a descriptor operation after its timeout")
  func givesUpOnADescriptorOperationAfterItsTimeout() async {
    let source = await source()
    await source.stopAnswering()

    await #expect {
      try await ReadDescriptor(source: source, bus: EventBus())(userDescription, of: testCharacteristic, on: device, timeout: .milliseconds(50))
    } throws: { ($0 as? BleError)?.code == .timeout }
  }
}
