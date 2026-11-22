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

  @Test("reports the state after asking to turn Bluetooth on")
  func reportsTheStateAfterAskingToTurnBluetoothOn() async {
    let request = RequestBluetoothOn(source: FakeAdapterStateSource(state: .poweredOff, afterEnableRequest: .poweredOn))

    #expect(await request() == .poweredOn)
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
