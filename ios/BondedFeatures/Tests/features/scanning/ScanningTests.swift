import Testing
@testable import BondedFeatures

@Suite("Scanning")
struct ScanningTests {
  private func coordinator(
    source: FakeScanSource = FakeScanSource(),
    environment: FakeScanEnvironment = FakeScanEnvironment(),
    bus: EventBus = EventBus()
  ) -> ScanCoordinator {
    ScanCoordinator(source: source, environment: environment, bus: bus)
  }

  @Test("reports each device found with its advertisement")
  func reportsEachDeviceFoundWithItsAdvertisement() async throws {
    let source = FakeScanSource()
    let scan = ScanRecorder(try await coordinator(source: source).start(ScanRequest()))

    await source.emit(advertisement("A", name: "Thermo-1", rssi: -42))
    await eventually { await scan.devices.count == 1 }

    #expect(await scan.devices == [
      ScannedDevice(id: DeviceId("A"), name: "Thermo-1", rssi: -42, serviceUuids: [ServiceUuid("180D")!]),
    ])
  }

  @Test("reports each device once unless duplicates are allowed")
  func reportsEachDeviceOnceUnlessDuplicatesAreAllowed() async throws {
    let source = FakeScanSource()
    let coordinator = coordinator(source: source)

    let once = ScanRecorder(try await coordinator.start(ScanRequest()))
    for rssi in [-60, -55, -50] { await source.emit(advertisement("A", rssi: rssi)) }
    await source.emit(advertisement("B"))
    await eventually { await once.devices.count == 2 }
    #expect(await once.devices.map(\.id.rawValue) == ["A", "B"])

    let every = ScanRecorder(try await coordinator.start(ScanRequest(allowDuplicates: true)))
    for rssi in [-60, -55, -50] { await source.emit(advertisement("A", rssi: rssi)) }
    await eventually { await every.devices.count == 3 }
    #expect(await every.devices.map(\.rssi) == [-60, -55, -50])
  }

  @Test("reports only devices whose name matches the filter")
  func reportsOnlyDevicesWhoseNameMatchesTheFilter() async throws {
    #expect(NameFilter.exact("Thermo-1").matches("Thermo-1"))
    #expect(!NameFilter.exact("Thermo-1").matches("Thermo-12"))
    #expect(NameFilter.pattern(startsWith: "Thermo-", endsWith: "A", includes: "3F").matches("Thermo-3F2A"))
    #expect(!NameFilter.pattern(startsWith: "Thermo-", endsWith: "B", includes: nil).matches("Thermo-3F2A"))
    #expect(!NameFilter.pattern(startsWith: nil, endsWith: nil, includes: "x").matches(nil))

    let source = FakeScanSource()
    let filter = NameFilter.pattern(startsWith: "Thermo-", endsWith: nil, includes: nil)
    let scan = ScanRecorder(try await coordinator(source: source).start(ScanRequest(name: filter)))
    await source.emit(advertisement("A", name: "Headphones"))
    await source.emit(advertisement("B"))
    await source.emit(advertisement("C", name: "Thermo-3F2A"))
    await eventually { await scan.devices.count == 1 }

    #expect(await scan.devices.map(\.id.rawValue) == ["C"])
  }

  @Test("splits the company ID off the manufacturer data")
  func splitsTheCompanyIdOffTheManufacturerData() async throws {
    #expect(ManufacturerData(raw: [0x59, 0x00, 0x01, 0x02]) == ManufacturerData(companyId: 0x0059, data: [0x01, 0x02]))
    #expect(ManufacturerData(raw: [0x59]) == nil)

    let source = FakeScanSource()
    let scan = ScanRecorder(try await coordinator(source: source).start(ScanRequest()))
    await source.emit(advertisement("A", manufacturer: [0x4C, 0x00, 0x10]))
    await eventually { await scan.devices.count == 1 }

    #expect(await scan.devices.first?.manufacturerData == ManufacturerData(companyId: 0x004C, data: [0x10]))
  }

  @Test("includes the full advertisement only when asked")
  func includesTheFullAdvertisementOnlyWhenAsked() async throws {
    let source = FakeScanSource()
    let coordinator = coordinator(source: source)

    let lean = ScanRecorder(try await coordinator.start(ScanRequest()))
    await source.emit(advertisement("A", manufacturer: [0x4C, 0x00, 0x10]))
    await eventually { await lean.devices.count == 1 }
    #expect(await lean.devices.first?.full == nil)

    let full = ScanRecorder(try await coordinator.start(ScanRequest(fullAdvertisement: true)))
    await source.emit(advertisement("A", manufacturer: [0x4C, 0x00, 0x10]))
    await eventually { await full.devices.count == 1 }
    let details = try #require(await full.devices.first?.full)
    #expect(details.txPowerLevel == 4)
    #expect(details.isConnectable == true)
    #expect(details.rawManufacturerData == [0x4C, 0x00, 0x10])
    #expect(details.timestamp == 1000)
  }

  @Test("stops the scan after the timeout")
  func stopsTheScanAfterTheTimeout() async throws {
    let source = FakeScanSource()
    let scan = ScanRecorder(try await coordinator(source: source).start(ScanRequest(timeout: .milliseconds(20))))

    await eventually { await scan.stop != nil }

    #expect(await scan.stop == .stopped(.timeout, error: nil))
    #expect(await !source.isScanning)
  }

  @Test("replaces a running scan when a new one starts")
  func replacesARunningScanWhenANewOneStarts() async throws {
    let source = FakeScanSource()
    let coordinator = coordinator(source: source)
    let first = ScanRecorder(try await coordinator.start(ScanRequest()))
    let second = ScanRecorder(try await coordinator.start(ScanRequest()))

    await source.emit(advertisement("A"))
    await eventually { await second.devices.count == 1 }
    await eventually { await first.stop != nil }

    #expect(await first.stop == .stopped(.replaced, error: nil))
    #expect(await first.devices.isEmpty)
    #expect(await source.isScanning)
  }

  @Test("refuses to scan while Bluetooth is off or without permission")
  func refusesToScanWhileBluetoothIsOffOrWithoutPermission() async {
    for (readiness, code) in [(ScanReadiness.bluetoothOff, BleError.Code.bluetoothOff), (.unauthorized, .unauthorized), (.unsupported, .unsupported)] {
      let source = FakeScanSource(readiness: readiness)
      await #expect { try await coordinator(source: source).start(ScanRequest()) } throws: { ($0 as? BleError)?.code == code }
      #expect(await source.startCount == 0)
    }
  }

  @Test("refuses an unfiltered scan in the background")
  func refusesAnUnfilteredScanInTheBackground() async throws {
    let source = FakeScanSource()
    let coordinator = coordinator(source: source, environment: FakeScanEnvironment(isInBackground: true))

    await #expect { try await coordinator.start(ScanRequest()) } throws: { ($0 as? BleError)?.code == .unsupported }
    _ = try await coordinator.start(ScanRequest(services: [ServiceUuid("180D")!]))
    #expect(await source.lastServices == [ServiceUuid("180D")!])
  }

  @Test("pauses an unfiltered scan in the background and resumes it in the foreground")
  func pausesAnUnfilteredScanInTheBackgroundAndResumesItInTheForeground() async throws {
    let source = FakeScanSource()
    let environment = FakeScanEnvironment()
    let scan = ScanRecorder(try await coordinator(source: source, environment: environment).start(ScanRequest()))

    await environment.setInBackground(true)
    await eventually { await !source.isScanning }
    #expect(await !source.isScanning)

    await environment.setInBackground(false)
    await eventually { await source.isScanning }
    await source.emit(advertisement("A"))
    await eventually { await scan.devices.count == 1 }

    #expect(await scan.stop == nil)
    #expect(await scan.devices.map(\.id.rawValue) == ["A"])
  }

  @Test("reports a failure when Bluetooth goes off during a scan")
  func reportsAFailureWhenBluetoothGoesOffDuringAScan() async throws {
    let source = FakeScanSource()
    let scan = ScanRecorder(try await coordinator(source: source).start(ScanRequest()))

    await source.turnBluetoothOff()
    await eventually { await scan.stop != nil }

    guard case let .stopped(reason, error) = try #require(await scan.stop) else { return }
    #expect(reason == .failed)
    #expect(error?.code == .bluetoothOff)
  }

  @Test("publishes when a scan starts and stops")
  func publishesWhenAScanStartsAndStops() async throws {
    let bus = EventBus()
    let published = await bus.events()
    let coordinator = coordinator(bus: bus)

    let session = try await coordinator.start(ScanRequest())
    await coordinator.stop(session.id)

    var iterator = published.makeAsyncIterator()
    #expect(await iterator.next() == DomainEvent(kind: .scanStarted, detail: "unfiltered"))
    #expect(await iterator.next() == DomainEvent(kind: .scanStopped, detail: "stopped"))
  }

  @Test("expands short service UUIDs to their full form")
  func expandsShortServiceUuidsToTheirFullForm() {
    #expect(ServiceUuid("180D")?.rawValue == "0000180d-0000-1000-8000-00805f9b34fb")
    #expect(ServiceUuid("0000FEAA")?.rawValue == "0000feaa-0000-1000-8000-00805f9b34fb")
    #expect(ServiceUuid("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")?.rawValue == "6e400001-b5a3-f393-e0a9-e50e24dcca9e")
    #expect(ServiceUuid("180D") == ServiceUuid("0000180d-0000-1000-8000-00805f9b34fb"))
    #expect(ServiceUuid("not a uuid") == nil)
    #expect(ServiceUuid("180") == nil)
  }
}
