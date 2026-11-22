import Testing
@testable import BondedFeatures

@Suite("Diagnostics")
struct DiagnosticsTests {
  private func recorder(
    store: FakeDiagnosticsStore = FakeDiagnosticsStore(),
    activity: DiagnosticEvent.AppActivity = .foreground,
    saveDelay: Duration = .seconds(60)
  ) -> (DiagnosticsRecorder, EventBus) {
    let bus = EventBus()
    let recorder = DiagnosticsRecorder(
      bus: bus,
      store: store,
      environment: FakeDiagnosticsEnvironment(activity: activity),
      saveDelay: saveDelay
    )
    return (recorder, bus)
  }

  @Test("records nothing while diagnostics are off")
  func recordsNothingWhileDiagnosticsAreOff() async {
    let store = FakeDiagnosticsStore()
    let (recorder, bus) = recorder(store: store)

    await publish([DomainEvent(kind: .stateChanged, detail: "poweredOn")], on: bus, until: recorder, count: 1)
    await recorder.saveNow()

    #expect(await recorder.events(since: nil).isEmpty)
    #expect(await store.saved.isEmpty)
  }

  @Test("records each published event with its time and the app's state")
  func recordsEachPublishedEventWithItsTimeAndTheAppsState() async {
    let (recorder, bus) = recorder(activity: .background)
    await recorder.configure(enabled: true, capacity: nil)

    await publish(
      [DomainEvent(kind: .stateChanged, detail: "poweredOff"), DomainEvent(kind: .permissionChanged, detail: "granted")],
      on: bus,
      until: recorder,
      count: 2
    )

    #expect(await recorder.events(since: nil) == [
      DiagnosticEvent(kind: .stateChanged, at: 1001, appActivity: .background, detail: "poweredOff"),
      DiagnosticEvent(kind: .permissionChanged, at: 1002, appActivity: .background, detail: "granted"),
    ])
  }

  @Test("keeps only the newest events up to the capacity")
  func keepsOnlyTheNewestEventsUpToTheCapacity() async {
    let (recorder, bus) = recorder()
    await recorder.configure(enabled: true, capacity: 2)

    let events = ["unknown", "poweredOff", "poweredOn"].map { DomainEvent(kind: .stateChanged, detail: $0) }
    await publish(events, on: bus, until: recorder, count: 3)

    #expect(await recorder.events(since: nil).map(\.detail) == ["poweredOff", "poweredOn"])
  }

  @Test("returns the events at or after a given time")
  func returnsTheEventsAtOrAfterAGivenTime() async {
    let (recorder, bus) = recorder()
    await recorder.configure(enabled: true, capacity: nil)

    let events = ["unknown", "poweredOff", "poweredOn"].map { DomainEvent(kind: .stateChanged, detail: $0) }
    await publish(events, on: bus, until: recorder, count: 3)

    #expect(await recorder.events(since: 1002).map(\.detail) == ["poweredOff", "poweredOn"])
  }

  @Test("restores the events saved before a relaunch")
  func restoresTheEventsSavedBeforeARelaunch() async {
    let earlier = DiagnosticEvent(kind: .permissionChanged, at: 500, appActivity: .foreground, detail: "denied")
    let (recorder, _) = recorder(store: FakeDiagnosticsStore(saved: [earlier]))

    await recorder.configure(enabled: true, capacity: nil)

    #expect(await recorder.events(since: nil) == [earlier])
  }

  @Test("deletes the stored events when cleared or turned off")
  func deletesTheStoredEventsWhenClearedOrTurnedOff() async {
    let earlier = DiagnosticEvent(kind: .permissionChanged, at: 500, appActivity: .foreground, detail: "denied")
    let store = FakeDiagnosticsStore(saved: [earlier])
    let (recorder, _) = recorder(store: store)
    await recorder.configure(enabled: true, capacity: nil)

    await recorder.clear()
    #expect(await recorder.events(since: nil).isEmpty)
    #expect(await store.saved.isEmpty)

    await recorder.configure(enabled: false, capacity: nil)
    #expect(await store.deleteCount == 2)
  }

  @Test("saves the recorded events so they survive the app being killed")
  func savesTheRecordedEventsSoTheySurviveTheAppBeingKilled() async {
    let store = FakeDiagnosticsStore()
    let (recorder, bus) = recorder(store: store, saveDelay: .milliseconds(10))
    await recorder.configure(enabled: true, capacity: nil)

    await publish([DomainEvent(kind: .stateChanged, detail: "poweredOn")], on: bus, until: recorder, count: 1)
    for _ in 0..<200 where await store.saved.isEmpty {
      try? await Task.sleep(for: .milliseconds(5))
    }

    #expect(await store.saved.map(\.detail) == ["poweredOn"])
  }
}
