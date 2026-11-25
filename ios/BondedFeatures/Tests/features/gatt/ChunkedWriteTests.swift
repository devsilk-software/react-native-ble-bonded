import Testing
@testable import BondedFeatures

@Suite("Chunked writes, maximum write length and signal strength")
struct ChunkedWriteTests {
  private let device = DeviceId("A")

  private func source() -> FakeCharacteristicSource {
    FakeCharacteristicSource(characteristics: [
      testCharacteristic: [.read, .write, .writeWithoutResponse, .notify],
      readOnlyCharacteristic: [.read, .notify],
    ])
  }

  @Test("reports the most one write carries")
  func reportsTheMostOneWriteCarries() async throws {
    #expect(try await GetMaxWriteLength(source: source())(on: device) == 20)
  }

  @Test("cuts data to the maximum write length and sends the chunks in order")
  func cutsDataToTheMaximumWriteLengthAndSendsTheChunksInOrder() async throws {
    let source = source()
    let data = (0..<45).map { UInt8($0) }

    try await WriteChunked(source: source, bus: EventBus())(data, to: testCharacteristic, on: device, withResponse: false)

    #expect(await source.writes.map(\.value.count) == [20, 20, 5])
    #expect(await source.writes.flatMap(\.value) == data)
    #expect(await source.writes.allSatisfy { !$0.withResponse })
  }

  @Test("stops at the chunk that failed and says which one and how many bytes were sent before it")
  func stopsAtTheChunkThatFailed() async {
    let source = source()
    await source.failPiece(1, with: BleError(code: .gattError, message: "The device refused it", platformCode: 14, attError: "unlikelyError"))

    await #expect {
      try await WriteChunked(source: source, bus: EventBus())(Array(repeating: 0, count: 45), to: testCharacteristic, on: device, withResponse: true)
    } throws: {
      let error = $0 as? BleError
      return error?.code == .gattError && error?.chunkIndex == 1 && error?.chunkCount == 3 && error?.sentLength == 20
        && error?.platformCode == 14 && error?.attError == "unlikelyError"
        && error?.message.contains("Chunk 1 of 3") == true && error?.message.contains("The device refused it") == true
    }
    #expect(await source.writes.count == 1)
  }

  @Test("sends nothing for empty data")
  func sendsNothingForEmptyData() async throws {
    let source = source()

    try await WriteChunked(source: source, bus: EventBus())([], to: testCharacteristic, on: device, withResponse: true)

    #expect(await source.writes.isEmpty)
  }

  @Test("refuses a chunked write the characteristic doesn't allow, before sending anything")
  func refusesAChunkedWriteTheCharacteristicDoesntAllow() async {
    let source = source()

    await #expect {
      try await WriteChunked(source: source, bus: EventBus())([1, 2, 3], to: readOnlyCharacteristic, on: device, withResponse: false)
    } throws: { ($0 as? BleError)?.code == .unsupported }
    #expect(await source.writes.isEmpty)
  }

  @Test("reads the signal strength")
  func readsTheSignalStrength() async throws {
    let source = source()
    await source.set(rssi: -42)

    #expect(try await ReadRssi(source: source)(on: device) == -42)
  }

  @Test("gives up reading the signal strength after its timeout")
  func givesUpReadingTheSignalStrengthAfterItsTimeout() async {
    let source = source()
    await source.stopAnswering()

    await #expect { try await ReadRssi(source: source)(on: device, timeout: .milliseconds(50)) } throws: {
      ($0 as? BleError)?.code == .timeout
    }
  }
}
