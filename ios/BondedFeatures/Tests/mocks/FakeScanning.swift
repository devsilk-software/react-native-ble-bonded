@testable import BondedFeatures

/// A `ScanSource` the test drives: it reports the readiness it's given and the advertisements the test emits.
actor FakeScanSource: ScanSource {
  var currentReadiness: ScanReadiness
  private(set) var startCount = 0
  private(set) var stopCount = 0
  private(set) var lastServices: [ServiceUuid] = []
  private var continuation: AsyncStream<Advertisement>.Continuation?

  init(readiness: ScanReadiness = .ready) {
    currentReadiness = readiness
  }

  var isScanning: Bool { continuation != nil }

  func readiness() async -> ScanReadiness { currentReadiness }

  func start(services: [ServiceUuid], allowDuplicates: Bool) async -> AsyncStream<Advertisement> {
    startCount += 1
    lastServices = services
    let (stream, continuation) = AsyncStream<Advertisement>.makeStream()
    self.continuation = continuation
    return stream
  }

  func stop() async {
    stopCount += 1
    continuation?.finish()
    continuation = nil
  }

  func emit(_ advertisement: Advertisement) {
    continuation?.yield(advertisement)
  }

  /// Bluetooth goes off: readiness changes and the platform stops scanning on its own.
  func turnBluetoothOff() {
    currentReadiness = .bluetoothOff
    continuation?.finish()
    continuation = nil
  }
}

/// A `ScanEnvironment` whose app the test sends to the background and back.
actor FakeScanEnvironment: ScanEnvironment {
  private var isBackground: Bool
  private var observers: [AsyncStream<Bool>.Continuation] = []

  init(isInBackground: Bool = false) {
    isBackground = isInBackground
  }

  func isInBackground() async -> Bool { isBackground }

  func backgroundChanges() async -> AsyncStream<Bool> {
    let (stream, continuation) = AsyncStream<Bool>.makeStream()
    observers.append(continuation)
    return stream
  }

  func setInBackground(_ isInBackground: Bool) {
    isBackground = isInBackground
    observers.forEach { $0.yield(isInBackground) }
  }
}

/// Collects a scan's events as they arrive.
actor ScanRecorder {
  private(set) var events: [ScanEvent] = []

  init(_ session: ScanSession) {
    let events = session.events
    Task { for await event in events { await self.append(event) } }
  }

  private func append(_ event: ScanEvent) { events.append(event) }

  var devices: [ScannedDevice] {
    events.compactMap { if case let .device(device) = $0 { device } else { nil } }
  }

  var stop: ScanEvent? {
    events.first { if case .stopped = $0 { true } else { false } }
  }
}

/// Waits until `condition` holds, for up to a second.
func eventually(_ condition: () async -> Bool) async {
  for _ in 0..<200 where await !condition() {
    try? await Task.sleep(for: .milliseconds(5))
  }
}

func advertisement(_ id: String, name: String? = nil, rssi: Int = -60, manufacturer: [UInt8]? = nil) -> Advertisement {
  Advertisement(
    id: DeviceId(id),
    name: name,
    rssi: rssi,
    serviceUuids: [ServiceUuid("180D")!],
    rawManufacturerData: manufacturer,
    txPowerLevel: 4,
    isConnectable: true,
    timestamp: 1000
  )
}
