import Testing
@testable import BondedFeatures

@Suite("Read and write")
struct GattTests {
  private let device = DeviceId("A")

  private func source() -> FakeCharacteristicSource {
    FakeCharacteristicSource(characteristics: [
      testCharacteristic: [.read, .write, .writeWithoutResponse, .notify],
      readOnlyCharacteristic: [.read, .notify],
    ])
  }

  @Test("reads a characteristic's value")
  func readsACharacteristicsValue() async throws {
    let source = source()
    await source.set([1, 2, 3], at: testCharacteristic)

    let value = try await ReadCharacteristic(source: source, bus: EventBus())(testCharacteristic, on: device)

    #expect(value == [1, 2, 3])
  }

  @Test("writes a value with and without response")
  func writesAValueWithAndWithoutResponse() async throws {
    let source = source()
    let write = WriteCharacteristic(source: source, bus: EventBus())

    try await write([1], to: testCharacteristic, on: device, withResponse: true)
    try await write([2], to: testCharacteristic, on: device, withResponse: false)

    #expect(await source.writes.map(\.value) == [[1], [2]])
    #expect(await source.writes.map(\.withResponse) == [true, false])
  }

  @Test("refuses an operation on a characteristic the device doesn't have")
  func refusesAnOperationOnACharacteristicTheDeviceDoesntHave() async {
    let missing = CharacteristicAddress(service: ServiceUuid("180F")!, characteristic: CharacteristicUuid("2A19")!)
    let read = ReadCharacteristic(source: source(), bus: EventBus())

    await #expect { try await read(missing, on: device) } throws: { error in
      guard let error = error as? BleError else { return false }
      return error.code == .characteristicNotFound && error.message.contains("2a19") && error.message.contains("getServices()")
    }
  }

  @Test("refuses an operation the characteristic doesn't allow")
  func refusesAnOperationTheCharacteristicDoesntAllow() async {
    let source = source()
    let write = WriteCharacteristic(source: source, bus: EventBus())

    await #expect { try await write([1], to: readOnlyCharacteristic, on: device, withResponse: true) } throws: { error in
      guard let error = error as? BleError else { return false }
      return error.code == .unsupported && error.message.contains("it allows read, notify")
    }
    #expect(await source.writes.isEmpty)
  }

  @Test("refuses a value longer than the write allows")
  func refusesAValueLongerThanTheWriteAllows() async {
    let source = source()
    let write = WriteCharacteristic(source: source, bus: EventBus())

    await #expect { try await write(Array(repeating: 0, count: 21), to: testCharacteristic, on: device, withResponse: false) } throws: {
      let error = $0 as? BleError
      return error?.code == .valueTooLong && error?.length == 21 && error?.maxLength == 20
        && error?.message.contains("Value is 21 bytes") == true && error?.message.contains("at most 20") == true
    }
    await #expect { try await write(Array(repeating: 0, count: 513), to: testCharacteristic, on: device, withResponse: true) } throws: {
      ($0 as? BleError)?.maxLength == 512
    }
    try? await write(Array(repeating: 0, count: 512), to: testCharacteristic, on: device, withResponse: true)
    #expect(await source.writes.map(\.value.count) == [512])
  }

  @Test("gives up on an operation after its timeout")
  func givesUpOnAnOperationAfterItsTimeout() async {
    let source = source()
    await source.stopAnswering()
    let read = ReadCharacteristic(source: source, bus: EventBus())

    await #expect { try await read(testCharacteristic, on: device, timeout: .milliseconds(30)) } throws: {
      ($0 as? BleError)?.code == .timeout
    }
  }

  @Test("names the error the device answered with")
  func namesTheErrorTheDeviceAnsweredWith() {
    let length = DeviceRefusal.error(code: 0x0D, operation: .write, address: testCharacteristic, id: device)
    #expect(length.code == .gattError)
    #expect(length.attError == "invalidAttributeValueLength")
    #expect(length.platformCode == 0x0D)
    #expect(length.message.contains("refused the value's length"))

    let firmware = DeviceRefusal.error(code: 0x81, operation: .read, address: testCharacteristic, id: device)
    #expect(firmware.attError == "applicationError")
    #expect(firmware.message.contains("0x81"))
  }

  @Test("asks for pairing when the characteristic needs it")
  func asksForPairingWhenTheCharacteristicNeedsIt() {
    for code in [0x05, 0x0C, 0x0F] {
      #expect(DeviceRefusal.error(code: code, operation: .read, address: testCharacteristic, id: device).code == .pairingRequired)
    }
  }

  @Test("publishes each operation for diagnostics")
  func publishesEachOperationForDiagnostics() async throws {
    let bus = EventBus()
    let published = await bus.events()
    let source = source()

    _ = try await ReadCharacteristic(source: source, bus: bus)(testCharacteristic, on: device)
    _ = try? await ReadCharacteristic(source: source, bus: bus)(readOnlyCharacteristic, on: device, timeout: .seconds(1))
    try? await WriteCharacteristic(source: source, bus: bus)([1], to: readOnlyCharacteristic, on: device, withResponse: true)

    var events: [DomainEvent] = []
    for await event in published {
      events.append(event)
      if events.count == 3 { break }
    }
    #expect(events.allSatisfy { $0.kind == .gattOperation && $0.deviceId == device })
    #expect(events[0].detail?.hasPrefix("read 00002a37") == true)
    #expect(events[2].errorCode == .unsupported)
  }
}
