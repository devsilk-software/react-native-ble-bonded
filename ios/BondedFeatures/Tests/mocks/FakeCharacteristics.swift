@testable import BondedFeatures

/// A `CharacteristicSource` with the characteristics the test gives it, values it returns, and writes it records.
actor FakeCharacteristicSource: CharacteristicSource {
  var characteristics: [CharacteristicAddress: [CharacteristicProperty]]
  var values: [CharacteristicAddress: [UInt8]] = [:]
  var refusal: BleError?
  var isAnswering = true
  var maxLength = 20
  private(set) var writes: [(value: [UInt8], withResponse: Bool)] = []
  private(set) var notificationSwitches: [(isOn: Bool, indicate: Bool)] = []
  var switchFailure: BleError?
  /// While true, switching notifications on waits until `releaseSwitches()`; `held` lists what waits.
  private var isHoldingSwitches = false
  private(set) var held: [CharacteristicAddress] = []
  /// Every time notifications were switched, including the ones that failed.
  private(set) var switchAttempts = 0
  private var heldContinuations: [CheckedContinuation<Void, Never>] = []
  private var observers: [AsyncStream<CharacteristicNotification>.Continuation] = []

  init(characteristics: [CharacteristicAddress: [CharacteristicProperty]]) {
    self.characteristics = characteristics
  }

  func properties(of address: CharacteristicAddress, on id: DeviceId) async throws -> [CharacteristicProperty]? {
    characteristics[address]
  }

  func maxWriteLength(on id: DeviceId) async throws -> Int { maxLength }

  func read(_ address: CharacteristicAddress, on id: DeviceId) async throws -> [UInt8] {
    try await answer()
    return values[address] ?? []
  }

  func write(_ value: [UInt8], to address: CharacteristicAddress, on id: DeviceId, withResponse: Bool) async throws {
    try await answer()
    writes.append((value, withResponse))
  }

  func setNotifications(_ isOn: Bool, indicate: Bool, on address: CharacteristicAddress, of id: DeviceId) async throws {
    switchAttempts += 1
    if isOn, let switchFailure { throw switchFailure }
    if isOn, isHoldingSwitches {
      held.append(address)
      await withCheckedContinuation { heldContinuations.append($0) }
    }
    notificationSwitches.append((isOn, indicate))
  }

  func notifications() async -> AsyncStream<CharacteristicNotification> {
    let (stream, continuation) = AsyncStream<CharacteristicNotification>.makeStream()
    observers.append(continuation)
    return stream
  }

  func notify(_ value: [UInt8], at address: CharacteristicAddress, on id: DeviceId) {
    observers.forEach { $0.yield(CharacteristicNotification(id: id, address: address, value: value)) }
  }

  func failSwitchingOn(with error: BleError?) { switchFailure = error }
  func holdSwitches() { isHoldingSwitches = true }
  func releaseSwitches() {
    isHoldingSwitches = false
    heldContinuations.forEach { $0.resume() }
    heldContinuations = []
  }

  func refuse(with error: BleError) { refusal = error }
  func stopAnswering() { isAnswering = false }
  func set(_ value: [UInt8], at address: CharacteristicAddress) { values[address] = value }

  private func answer() async throws {
    if let refusal { throw refusal }
    if !isAnswering { try await Task.sleep(for: .seconds(3600)) }
  }
}

let testCharacteristic = CharacteristicAddress(service: ServiceUuid("B0ED0001-0000-4000-8000-00000000B0ED")!, characteristic: CharacteristicUuid("2A37")!)
let readOnlyCharacteristic = CharacteristicAddress(service: ServiceUuid("180D")!, characteristic: CharacteristicUuid("2A38")!)

/// A characteristic that only indicates, to check Bonded picks indications when notify is missing.
let indicateOnlyCharacteristic = CharacteristicAddress(service: ServiceUuid("180D")!, characteristic: CharacteristicUuid("2A39")!)

/// Collects a subscription's events.
actor SubscriptionRecorder {
  private(set) var events: [SubscriptionEvent] = []
  private(set) var isFinished = false

  init(_ stream: AsyncStream<SubscriptionEvent>) {
    Task {
      for await event in stream { await self.append(event) }
      await self.finish()
    }
  }

  private func append(_ event: SubscriptionEvent) { events.append(event) }
  private func finish() { isFinished = true }
}
