@testable import BondedFeatures

/// A `CharacteristicSource` with the characteristics the test gives it, values it returns, and writes it records.
actor FakeCharacteristicSource: CharacteristicSource {
  var characteristics: [CharacteristicAddress: [CharacteristicProperty]]
  var values: [CharacteristicAddress: [UInt8]] = [:]
  var refusal: BleError?
  var isAnswering = true
  var maxLength = 20
  private(set) var writes: [(value: [UInt8], withResponse: Bool)] = []

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
