@testable import BondedFeatures

/// A `BondSource` whose trigger the test describes, and whose pairing succeeds or fails as told.
actor FakeBondSource: BondSource {
  /// `nil` when the device has no such characteristic.
  var isTriggerReadable: Bool? = true
  var refusal: BleError?
  private(set) var triggerReads = 0

  func isReadable(_ trigger: BondTrigger, on id: DeviceId) async throws -> Bool? { isTriggerReadable }

  func readTrigger(_ trigger: BondTrigger, on id: DeviceId) async throws {
    triggerReads += 1
    if let refusal { throw refusal }
  }

  func refuse(with error: BleError) { refusal = error }
  func describeTrigger(isReadable: Bool?) { isTriggerReadable = isReadable }
}

let testTrigger = BondTrigger(service: ServiceUuid("B0ED0001-0000-4000-8000-00000000B0ED")!, characteristic: CharacteristicUuid("B0ED0006-0000-4000-8000-00000000B0ED")!)
