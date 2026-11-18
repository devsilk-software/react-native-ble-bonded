import CoreBluetooth
import Testing
@testable import BondedFeatures

@Suite("Bluetooth state")
struct GetAdapterStateTests {
  @Test("reports the radio's current state", arguments: AdapterState.allCases)
  func reportsTheRadiosCurrentState(state: AdapterState) async {
    let getAdapterState = GetAdapterState(source: FakeAdapterStateSource(state: state))

    #expect(await getAdapterState() == state)
  }

  @Test(
    "maps every platform state to an adapter state",
    arguments: [
      (CBManagerState.unknown, AdapterState.unknown),
      (.resetting, .resetting),
      (.unsupported, .unsupported),
      (.unauthorized, .unauthorized),
      (.poweredOff, .poweredOff),
      (.poweredOn, .poweredOn),
    ]
  )
  func mapsEveryPlatformStateToAnAdapterState(platform: CBManagerState, expected: AdapterState) {
    #expect(CoreBluetoothAdapterStateSource.adapterState(from: platform) == expected)
  }
}
