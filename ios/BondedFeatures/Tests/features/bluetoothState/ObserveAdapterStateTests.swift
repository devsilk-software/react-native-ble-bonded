import Testing
@testable import BondedFeatures

@Suite("Bluetooth state changes")
struct ObserveAdapterStateTests {
  @Test("reports the current state first, then each change")
  func reportsTheCurrentStateFirstThenEachChange() async {
    let (changes, push) = AsyncStream<AdapterState>.makeStream()
    let observe = ObserveAdapterState(source: FakeAdapterStateSource(state: .poweredOff, changes: changes))
    push.yield(.poweredOn)
    push.yield(.unauthorized)
    push.finish()

    var received: [AdapterState] = []
    for await state in observe() {
      received.append(state)
    }

    #expect(received == [.poweredOff, .poweredOn, .unauthorized])
  }

  @Test("suppresses repeated states")
  func suppressesRepeatedStates() async {
    let (changes, push) = AsyncStream<AdapterState>.makeStream()
    let observe = ObserveAdapterState(source: FakeAdapterStateSource(state: .poweredOn, changes: changes))
    for state in [AdapterState.poweredOn, .poweredOn, .poweredOff, .poweredOff, .poweredOn] {
      push.yield(state)
    }
    push.finish()

    var received: [AdapterState] = []
    for await state in observe() {
      received.append(state)
    }

    #expect(received == [.poweredOn, .poweredOff, .poweredOn])
  }
}
