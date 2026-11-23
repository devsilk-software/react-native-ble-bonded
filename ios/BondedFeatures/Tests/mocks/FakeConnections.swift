@testable import BondedFeatures

/// A `ConnectionSource` the test scripts: each attempt takes the next outcome, and an attempt without one waits
/// for the device until it's cancelled.
actor FakeConnectionSource: ConnectionSource {
  enum Outcome {
    case succeed([GattService])
    case fail(BleError)
  }

  var currentReadiness: ConnectionReadiness
  private var outcomes: [Outcome]
  private(set) var modes: [ConnectMode] = []
  private(set) var cancelledAttempts = 0
  private(set) var disconnectCount = 0
  private var observers: [AsyncStream<PlatformDisconnect>.Continuation] = []
  /// Platform codes that count as transient, like Android's 133.
  private let transientCodes: Set<Int>

  init(readiness: ConnectionReadiness = .ready, outcomes: [Outcome] = [], transientCodes: Set<Int> = [133]) {
    currentReadiness = readiness
    self.outcomes = outcomes
    self.transientCodes = transientCodes
  }

  var attemptCount: Int { modes.count }

  func readiness() async -> ConnectionReadiness { currentReadiness }

  func attempt(_ id: DeviceId, mode: ConnectMode) async throws -> [GattService] {
    modes.append(mode)
    if !outcomes.isEmpty {
      switch outcomes.removeFirst() {
      case let .succeed(services): return services
      case let .fail(error): throw error
      }
    }
    do {
      try await Task.sleep(for: .seconds(3600))
    } catch {
      cancelledAttempts += 1
      throw error
    }
    return []
  }

  func disconnect(_ id: DeviceId) async {
    disconnectCount += 1
  }

  func disconnections() async -> AsyncStream<PlatformDisconnect> {
    let (stream, continuation) = AsyncStream<PlatformDisconnect>.makeStream()
    observers.append(continuation)
    return stream
  }

  nonisolated func isTransient(_ error: BleError) -> Bool {
    error.platformCode.map(transientCodes.contains) ?? false
  }

  func name(of id: DeviceId) async -> String? { "Thermo-1" }

  func setReadiness(_ readiness: ConnectionReadiness) {
    currentReadiness = readiness
  }

  /// The device drops the connection by itself.
  func drop(_ id: DeviceId, _ disconnect: Disconnect) {
    observers.forEach { $0.yield(PlatformDisconnect(id: id, disconnect: disconnect)) }
  }
}

/// Collects connection changes as they arrive.
actor ConnectionRecorder {
  private(set) var changes: [ConnectionChange] = []

  init(_ stream: AsyncStream<ConnectionChange>) {
    Task { for await change in stream { await self.append(change) } }
  }

  private func append(_ change: ConnectionChange) { changes.append(change) }

  var states: [ConnectionState] { changes.map(\.state) }
}

let heartRateService = GattService(
  uuid: ServiceUuid("180D")!,
  characteristics: [GattCharacteristic(uuid: CharacteristicUuid("2A37")!, properties: [.notify])]
)

func transientFailure() -> BleError {
  BleError(code: .connectionFailed, message: "GATT error", platformCode: 133, operation: "connect")
}
