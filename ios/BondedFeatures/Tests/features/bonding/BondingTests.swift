import Testing
@testable import BondedFeatures

@Suite("Bonding")
struct BondingTests {
  private let device = DeviceId("A")

  @Test("pairs by reading the trigger")
  func pairsByReadingTheTrigger() async throws {
    let source = FakeBondSource()

    try await CreateBond(source: source, bus: EventBus())(testTrigger, on: device)

    #expect(await source.triggerReads == 1)
  }

  @Test("reports a declined pairing as pairing required")
  func reportsADeclinedPairingAsPairingRequired() async {
    let source = FakeBondSource()
    await source.refuse(with: BleError(code: .pairingRequired, message: "Pairing was declined or failed", deviceId: device))

    await #expect { try await CreateBond(source: source, bus: EventBus())(testTrigger, on: device) } throws: {
      ($0 as? BleError)?.code == .pairingRequired
    }
  }

  @Test("refuses a trigger the device doesn't have")
  func refusesATriggerTheDeviceDoesntHave() async {
    let source = FakeBondSource()
    await source.describeTrigger(isReadable: nil)

    await #expect { try await CreateBond(source: source, bus: EventBus())(testTrigger, on: device) } throws: {
      let error = $0 as? BleError
      return error?.code == .characteristicNotFound && error?.message.contains("getServices()") == true
    }
    #expect(await source.triggerReads == 0)
  }

  @Test("refuses a trigger that can't be read")
  func refusesATriggerThatCantBeRead() async {
    let source = FakeBondSource()
    await source.describeTrigger(isReadable: false)

    await #expect { try await CreateBond(source: source, bus: EventBus())(testTrigger, on: device) } throws: {
      ($0 as? BleError)?.code == .unsupported
    }
    #expect(await source.triggerReads == 0)
  }
}
