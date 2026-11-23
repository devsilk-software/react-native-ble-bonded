import CoreBluetooth
import Foundation
import Testing
@testable import BondedFeatures

@Suite("Connection")
struct ConnectionTests {
  private let device = DeviceId("A")

  private func coordinator(
    _ source: FakeConnectionSource,
    bus: EventBus = EventBus(),
    store: FakeRememberedDeviceStore = FakeRememberedDeviceStore()
  ) -> ConnectionCoordinator {
    ConnectionCoordinator(source: source, bus: bus, store: store, retry: RetryPolicy(directAttempts: 2), now: { 5000 })
  }

  @Test("connects and discovers the device's services")
  func connectsAndDiscoversTheDevicesServices() async throws {
    let coordinator = coordinator(FakeConnectionSource(outcomes: [.succeed([heartRateService])]))

    try await coordinator.connect(device)

    #expect(try await coordinator.services(of: device) == [heartRateService])
  }

  @Test("reports connecting, then connected once services are discovered")
  func reportsConnectingThenConnectedOnceServicesAreDiscovered() async throws {
    let coordinator = coordinator(FakeConnectionSource(outcomes: [.succeed([heartRateService])]))
    let recorder = ConnectionRecorder(await coordinator.changes())

    try await coordinator.connect(device)
    await eventually { await recorder.states.count == 2 }

    #expect(await recorder.states == [.connecting, .connected])
    #expect(await recorder.changes.last?.name == "Thermo-1")
  }

  @Test("resolves at once when already connected")
  func resolvesAtOnceWhenAlreadyConnected() async throws {
    let source = FakeConnectionSource(outcomes: [.succeed([heartRateService])])
    let coordinator = coordinator(source)

    try await coordinator.connect(device)
    try await coordinator.connect(device)

    #expect(await source.attemptCount == 1)
  }

  @Test("shares one attempt when connect is called again while connecting")
  func sharesOneAttemptWhenConnectIsCalledAgainWhileConnecting() async throws {
    let source = FakeConnectionSource()
    let coordinator = coordinator(source)
    let options = ConnectOptions(timeout: .milliseconds(50))

    let first = Task { try await coordinator.connect(device, options: options) }
    let second = Task { try await coordinator.connect(device, options: options) }
    let firstFailure = await first.result
    let secondFailure = await second.result

    #expect(await source.attemptCount == 1)
    for result in [firstFailure, secondFailure] {
      guard case let .failure(error) = result else { Issue.record("expected a timeout"); continue }
      #expect((error as? BleError)?.code == .timeout)
    }
  }

  @Test("gives up after the timeout")
  func givesUpAfterTheTimeout() async {
    let source = FakeConnectionSource()
    let coordinator = coordinator(source)

    await #expect { try await coordinator.connect(device, options: ConnectOptions(timeout: .milliseconds(30))) } throws: {
      ($0 as? BleError)?.code == .timeout
    }
    #expect(await source.cancelledAttempts == 1)
    await #expect(throws: BleError.self) { try await coordinator.services(of: device) }
  }

  @Test("cancels a connection attempt when disconnect is called")
  func cancelsAConnectionAttemptWhenDisconnectIsCalled() async {
    let source = FakeConnectionSource()
    let coordinator = coordinator(source)
    let recorder = ConnectionRecorder(await coordinator.changes())

    let attempt = Task { try await coordinator.connect(device, options: ConnectOptions(timeout: nil)) }
    await eventually { await source.attemptCount == 1 }
    await coordinator.disconnect(device)

    await #expect { try await attempt.value } throws: { ($0 as? BleError)?.code == .cancelled }
    await eventually { await recorder.states.last == .disconnected }
    #expect(await recorder.changes.last?.lastDisconnect == Disconnect(reason: .requested))
  }

  @Test("reports why the device disconnected")
  func reportsWhyTheDeviceDisconnected() async throws {
    let source = FakeConnectionSource(outcomes: [.succeed([heartRateService])])
    let coordinator = coordinator(source)
    let recorder = ConnectionRecorder(await coordinator.changes())
    try await coordinator.connect(device)

    let lost = Disconnect(reason: .outOfRange, error: BleError(code: .disconnected, message: "Out of range", platformCode: 6))
    await source.drop(device, lost)
    await eventually { await recorder.states.last == .disconnected }
    #expect(await recorder.changes.last?.lastDisconnect == lost)
  }

  @Test("disconnects a device that finished connecting after disconnect was called")
  func disconnectsADeviceThatFinishedConnectingAfterDisconnectWasCalled() async {
    let source = FakeConnectionSource(outcomes: [.succeedLate([heartRateService], .milliseconds(50))])
    let coordinator = coordinator(source)

    let attempt = Task { try await coordinator.connect(device, options: ConnectOptions(timeout: nil)) }
    await eventually { await source.attemptCount == 1 }
    await coordinator.disconnect(device)

    await #expect { try await attempt.value } throws: { ($0 as? BleError)?.code == .cancelled }
    #expect(await source.disconnectCount == 1)
    await #expect(throws: BleError.self) { try await coordinator.services(of: device) }
  }

  @Test("connects again only after a disconnect still under way has finished")
  func connectsAgainOnlyAfterADisconnectStillUnderWayHasFinished() async throws {
    let source = FakeConnectionSource(outcomes: [.succeed([heartRateService]), .succeed([heartRateService])])
    await source.setDisconnectPause(.milliseconds(50))
    let coordinator = coordinator(source)
    let recorder = ConnectionRecorder(await coordinator.changes())
    try await coordinator.connect(device)

    let disconnecting = Task { await coordinator.disconnect(device) }
    await eventually { await recorder.states.last == .disconnecting }
    try await coordinator.connect(device)
    await disconnecting.value
    await eventually { await recorder.states.count == 6 }

    #expect(await recorder.states == [.connecting, .connected, .disconnecting, .disconnected, .connecting, .connected])
  }

  @Test("disconnects when asked and reports it as requested")
  func disconnectsWhenAskedAndReportsItAsRequested() async throws {
    let source = FakeConnectionSource(outcomes: [.succeed([heartRateService])])
    let coordinator = coordinator(source)
    let recorder = ConnectionRecorder(await coordinator.changes())
    try await coordinator.connect(device)

    await coordinator.disconnect(device)
    await eventually { await recorder.states.count == 4 }

    #expect(await recorder.states == [.connecting, .connected, .disconnecting, .disconnected])
    #expect(await recorder.changes.last?.lastDisconnect == Disconnect(reason: .requested))
    #expect(await source.disconnectCount == 1)
  }

  @Test("retries transient failures, first directly, then waiting for the device")
  func retriesTransientFailuresFirstDirectlyThenWaitingForTheDevice() async throws {
    let source = FakeConnectionSource(outcomes: [.fail(transientFailure()), .fail(transientFailure()), .succeed([heartRateService])])
    let coordinator = coordinator(source)

    try await coordinator.connect(device)

    #expect(await source.modes == [.direct, .direct, .waiting])
    #expect(RetryPolicy().backoff(beforeAttempt: 2) == .milliseconds(250))
    #expect(RetryPolicy().backoff(beforeAttempt: 3) == .milliseconds(500))
    #expect(RetryPolicy().backoff(beforeAttempt: 9) == .seconds(2))
  }

  @Test("fails at once on an error that won't pass on its own")
  func failsAtOnceOnAnErrorThatWontPassOnItsOwn() async {
    let refused = BleError(code: .connectionFailed, message: "Refused", platformCode: 5, operation: "connect")
    let source = FakeConnectionSource(outcomes: [.fail(refused)])
    let coordinator = coordinator(source)

    await #expect { try await coordinator.connect(device) } throws: { ($0 as? BleError) == refused }
    #expect(await source.attemptCount == 1)
  }

  @Test("refuses to connect while Bluetooth is off or without permission")
  func refusesToConnectWhileBluetoothIsOffOrWithoutPermission() async {
    for (readiness, code) in [(ConnectionReadiness.bluetoothOff, BleError.Code.bluetoothOff), (.unauthorized, .unauthorized)] {
      let source = FakeConnectionSource(readiness: readiness)
      await #expect { try await coordinator(source).connect(device) } throws: { ($0 as? BleError)?.code == code }
      #expect(await source.attemptCount == 0)
    }
  }

  @Test("reports each device's current state first, then each change")
  func reportsEachDevicesCurrentStateFirstThenEachChange() async throws {
    let coordinator = coordinator(FakeConnectionSource(outcomes: [.succeed([heartRateService])]))
    try await coordinator.connect(device)

    let recorder = ConnectionRecorder(await coordinator.changes())
    await coordinator.disconnect(device)
    await eventually { await recorder.states.count == 3 }

    #expect(await recorder.states == [.connected, .disconnecting, .disconnected])
  }

  @Test("keeps waiting for the device while Bluetooth is off")
  func keepsWaitingForTheDeviceWhileBluetoothIsOff() async throws {
    let off = BleError(code: .bluetoothOff, message: "Bluetooth is off", operation: "connect")
    let source = FakeConnectionSource(outcomes: [.fail(off), .succeed([heartRateService])])
    let coordinator = coordinator(source)
    await source.setReadiness(.ready)

    try await coordinator.connect(device, options: ConnectOptions(timeout: nil))

    #expect(await source.attemptCount == 2)
    #expect(try await coordinator.services(of: device) == [heartRateService])
  }

  @Test("refuses to read services while not connected")
  func refusesToReadServicesWhileNotConnected() async {
    let coordinator = coordinator(FakeConnectionSource())

    await #expect { try await coordinator.services(of: device) } throws: { ($0 as? BleError)?.code == .notConnected }
  }

  @Test("remembers a device after it connects")
  func remembersADeviceAfterItConnects() async throws {
    let store = FakeRememberedDeviceStore()
    let coordinator = coordinator(FakeConnectionSource(outcomes: [.succeed([])]), store: store)

    try await coordinator.connect(device)

    let saved = await store.saved
    #expect(saved.map(\.id) == [device])
    #expect(saved.first?.name == "Thermo-1")
    #expect(saved.first?.lastConnectedAt == 5000)
  }

  @Test("remembers nothing when asked not to")
  func remembersNothingWhenAskedNotTo() async throws {
    let store = FakeRememberedDeviceStore()
    let coordinator = coordinator(FakeConnectionSource(outcomes: [.succeed([])]), store: store)

    try await coordinator.connect(device, options: ConnectOptions(remember: false))

    #expect(await store.saved.isEmpty)
    #expect(await coordinator.rememberedDevices().isEmpty)
  }

  @Test("reports remembered devices before they connect again")
  func reportsRememberedDevicesBeforeTheyConnectAgain() async {
    let earlier = RememberedDevice(id: DeviceId("B"), name: "Thermo-2", identity: DeviceIdentity(name: "Thermo-2"), lastConnectedAt: 1000)
    let coordinator = coordinator(FakeConnectionSource(), store: FakeRememberedDeviceStore(saved: [earlier]))

    let recorder = ConnectionRecorder(await coordinator.changes())
    await eventually { await recorder.changes.count == 1 }

    #expect(await recorder.changes == [
      ConnectionChange(id: DeviceId("B"), name: "Thermo-2", state: .disconnected, lastDisconnect: nil, remembered: earlier),
    ])
  }

  @Test("lists every known connection, remembered devices included")
  func listsEveryKnownConnectionRememberedDevicesIncluded() async throws {
    let earlier = RememberedDevice(id: DeviceId("B"), name: "Thermo-2", identity: DeviceIdentity(name: "Thermo-2"), lastConnectedAt: 1000)
    let coordinator = coordinator(FakeConnectionSource(outcomes: [.succeed([])]), store: FakeRememberedDeviceStore(saved: [earlier]))
    try await coordinator.connect(device, options: ConnectOptions(remember: false))

    let connections = await coordinator.connections()

    #expect(connections.map(\.id) == [device, DeviceId("B")])
    #expect(connections.map(\.state) == [.connected, .disconnected])
    #expect(connections.last?.remembered == earlier)
  }

  @Test("forgets a device and disconnects it")
  func forgetsADeviceAndDisconnectsIt() async throws {
    let source = FakeConnectionSource(outcomes: [.succeed([])])
    let store = FakeRememberedDeviceStore()
    let coordinator = coordinator(source, store: store)
    let recorder = ConnectionRecorder(await coordinator.changes())
    try await coordinator.connect(device)

    await coordinator.forget(device)
    await eventually { await recorder.changes.last?.remembered == nil }

    #expect(await source.disconnectCount == 1)
    #expect(await store.saved.isEmpty)
    #expect(await recorder.changes.last?.remembered == nil)
  }

  @Test("keeps the identity the device was found by")
  func keepsTheIdentityTheDeviceWasFoundBy() async throws {
    let store = FakeRememberedDeviceStore()
    let coordinator = coordinator(FakeConnectionSource(outcomes: [.succeed([]), .succeed([])]), store: store)
    let found = DeviceIdentity(name: "Thermo-1", services: ["0000180d-0000-1000-8000-00805f9b34fb"], companyId: 0x0059)

    try await coordinator.connect(device, options: ConnectOptions(identity: found))
    await coordinator.disconnect(device)
    try await coordinator.connect(device)

    #expect(await store.saved.first?.identity == found)
  }

  @Test("publishes connection attempts, retries, connections and disconnections")
  func publishesConnectionAttemptsRetriesConnectionsAndDisconnections() async throws {
    let bus = EventBus()
    let published = await bus.events()
    let coordinator = coordinator(FakeConnectionSource(outcomes: [.fail(transientFailure()), .succeed([])]), bus: bus)

    try await coordinator.connect(device)
    await coordinator.disconnect(device)

    var kinds: [DomainEvent.Kind] = []
    for await event in published {
      kinds.append(event.kind)
      if kinds.count == 4 { break }
    }
    #expect(kinds == [.connecting, .connectionRetried, .connected, .disconnected])
  }
}

@Suite("Connection on iOS")
struct CoreBluetoothConnectionTests {
  @Test("maps platform disconnect errors to reasons")
  func mapsPlatformDisconnectErrorsToReasons() {
    let id = DeviceId("A")
    let cases: [(CentralDisconnect, DisconnectReason)] = [
      (disconnection(code: CBError.connectionTimeout.rawValue), .outOfRange),
      (disconnection(code: CBError.peripheralDisconnected.rawValue), .deviceDisconnected),
      (disconnection(code: CBError.unknown.rawValue), .failed),
      (CentralDisconnect(identifier: UUID(), error: nil, isBluetoothOff: true), .bluetoothOff),
    ]
    for (platform, reason) in cases {
      let disconnect = CoreBluetoothConnectionSource.disconnect(platform, id: id)
      #expect(disconnect.reason == reason)
      #expect(disconnect.error != nil)
    }
    #expect(CoreBluetoothConnectionSource.disconnect(cases[0].0, id: id).error?.platformCode == CBError.connectionTimeout.rawValue)
  }

  @Test("reads every characteristic's properties")
  func readsEveryCharacteristicsProperties() {
    #expect(CoreBluetoothConnectionSource.properties([.read, .notify, .writeWithoutResponse]) == [.read, .writeWithoutResponse, .notify])
  }

  private func disconnection(code: Int) -> CentralDisconnect {
    CentralDisconnect(identifier: UUID(), error: PlatformErrorInfo(domain: CBErrorDomain, code: code, message: "x"), isBluetoothOff: false)
  }
}
