import Foundation

/// Owns every device's connection: connects and discovers in one step, gives up after the timeout, retries
/// failures that may pass on their own, shares one attempt between callers, cancels an attempt on `disconnect`,
/// and reports each change, current state first (decision A6), and publishes it for diagnostics.
public actor ConnectionCoordinator {
  private let source: any ConnectionSource
  private let bus: EventBus
  private let retry: RetryPolicy
  private var devices: [DeviceId: Entry] = [:]
  private var observers: [UUID: AsyncStream<ConnectionChange>.Continuation] = [:]
  private var isWatching = false

  private struct Entry {
    var name: String?
    var state: ConnectionState = .disconnected
    var lastDisconnect: Disconnect?
    var services: [GattService] = []
    var attempt: Task<[GattService], any Error>?
    var isCancelling = false
  }

  public init(source: any ConnectionSource, bus: EventBus, retry: RetryPolicy = RetryPolicy()) {
    self.source = source
    self.bus = bus
    self.retry = retry
  }

  /// Connects and discovers the device's services; returns once it's ready. Returns at once when it's already
  /// connected, and shares the running attempt when one is under way. Throws a `BleError`.
  public func connect(_ id: DeviceId, options: ConnectOptions = ConnectOptions()) async throws {
    await watchDisconnections()
    if let error = await source.readiness().error {
      throw error
    }
    if devices[id]?.state == .connected { return }
    if let attempt = devices[id]?.attempt {
      _ = try await attempt.value
      return
    }
    let name = await source.name(of: id)
    if devices[id]?.state == .connected { return }
    if let attempt = devices[id]?.attempt {
      _ = try await attempt.value
      return
    }
    let task = Task { try await self.run(id, options) }
    update(id) {
      $0.attempt = task
      $0.state = .connecting
      $0.name = name ?? $0.name
    }
    await bus.publish(DomainEvent(kind: .connecting, deviceId: id))
    _ = try await task.value
  }

  /// Disconnects the device and returns once it has. A connection attempt under way is cancelled, and whoever
  /// awaits it gets `cancelled`.
  public func disconnect(_ id: DeviceId) async {
    guard let entry = devices[id] else { return }
    if let attempt = entry.attempt {
      devices[id]?.isCancelling = true
      attempt.cancel()
      _ = try? await attempt.value
      return
    }
    guard entry.state == .connected else { return }
    update(id) { $0.state = .disconnecting }
    await source.disconnect(id)
    guard devices[id]?.state == .disconnecting else { return }
    update(id) {
      $0.state = .disconnected
      $0.lastDisconnect = Disconnect(reason: .requested)
      $0.services = []
    }
    await bus.publish(DomainEvent(kind: .disconnected, deviceId: id, detail: DisconnectReason.requested.rawValue))
  }

  /// The services discovered when the device connected. Throws `notConnected` while it isn't connected.
  public func services(of id: DeviceId) throws -> [GattService] {
    guard let entry = devices[id], entry.state == .connected else {
      throw BleError(code: .notConnected, message: "The device isn't connected", deviceId: id, operation: "getServices")
    }
    return entry.services
  }

  /// Every known device's current connection, then every change, from the moment this returns.
  public func changes() -> AsyncStream<ConnectionChange> {
    let (stream, continuation) = AsyncStream<ConnectionChange>.makeStream()
    let id = UUID()
    continuation.onTermination = { _ in
      Task { await self.removeObserver(id) }
    }
    observers[id] = continuation
    for deviceId in devices.keys.sorted(by: { $0.rawValue < $1.rawValue }) {
      if let change = change(for: deviceId) { continuation.yield(change) }
    }
    return stream
  }

  // MARK: - Attempts

  private func run(_ id: DeviceId, _ options: ConnectOptions) async throws -> [GattService] {
    let started = ContinuousClock.now
    do {
      let isWaiting = options.timeout == nil
      let services = try await withTimeout(options.timeout, for: id) { try await self.attempts(id, isWaiting: isWaiting) }
      let name = await source.name(of: id)
      update(id) {
        $0.state = .connected
        $0.services = services
        $0.attempt = nil
        $0.name = name ?? $0.name
      }
      let elapsed = ContinuousClock.now - started
      await bus.publish(DomainEvent(kind: .connected, deviceId: id, detail: "in \(elapsed.milliseconds) ms"))
      return services
    } catch {
      let isCancelled = devices[id]?.isCancelling == true
      let failure = isCancelled
        ? BleError(code: .cancelled, message: "The connection attempt was cancelled", deviceId: id, operation: "connect")
        : Self.bleError(error, id: id)
      let disconnect = isCancelled ? Disconnect(reason: .requested) : Disconnect(reason: .failed, error: failure)
      update(id) {
        $0.state = .disconnected
        $0.attempt = nil
        $0.isCancelling = false
        $0.lastDisconnect = disconnect
      }
      await bus.publish(DomainEvent(
        kind: .disconnected,
        deviceId: id,
        detail: disconnect.reason.rawValue,
        errorCode: disconnect.error?.code,
        platformCode: disconnect.error?.platformCode
      ))
      throw failure
    }
  }

  /// With `isWaiting`, the caller waits for the device without a timeout, so Bluetooth going off doesn't fail
  /// the attempt: it waits for Bluetooth to come back and tries again.
  private func attempts(_ id: DeviceId, isWaiting: Bool) async throws -> [GattService] {
    var attempt = 1
    while true {
      do {
        return try await source.attempt(id, mode: retry.mode(forAttempt: attempt))
      } catch let error as BleError where isWaiting && error.code == .bluetoothOff {
        while await source.readiness() != .ready {
          try await Task.sleep(for: .seconds(1))
        }
      } catch let error as BleError where source.isTransient(error) {
        try Task.checkCancellation()
        attempt += 1
        await bus.publish(DomainEvent(
          kind: .connectionRetried,
          deviceId: id,
          detail: "attempt \(attempt)",
          errorCode: error.code,
          platformCode: error.platformCode
        ))
        try await Task.sleep(for: retry.backoff(beforeAttempt: attempt))
      }
    }
  }

  private nonisolated func withTimeout<Result: Sendable>(
    _ timeout: Duration?,
    for id: DeviceId,
    _ operation: @escaping @Sendable () async throws -> Result
  ) async throws -> Result {
    guard let timeout else { return try await operation() }
    return try await withThrowingTaskGroup(of: Result.self) { group in
      group.addTask { try await operation() }
      group.addTask {
        try await Task.sleep(for: timeout)
        throw BleError(code: .timeout, message: "Couldn't connect within \(timeout)", deviceId: id, operation: "connect")
      }
      defer { group.cancelAll() }
      guard let result = try await group.next() else { throw CancellationError() }
      return result
    }
  }

  private static func bleError(_ error: any Error, id: DeviceId) -> BleError {
    if let error = error as? BleError { return error }
    if error is CancellationError {
      return BleError(code: .cancelled, message: "The connection attempt was cancelled", deviceId: id, operation: "connect")
    }
    return BleError(code: .unknown, message: String(describing: error), deviceId: id, operation: "connect")
  }

  // MARK: - Disconnections the app didn't ask for

  private func watchDisconnections() async {
    guard !isWatching else { return }
    isWatching = true
    let disconnections = await source.disconnections()
    Task { [weak self] in
      for await disconnection in disconnections {
        await self?.handle(disconnection)
      }
    }
  }

  private func handle(_ disconnection: PlatformDisconnect) async {
    // During an attempt the attempt itself fails; during `disconnect()` the reason is `requested`.
    guard devices[disconnection.id]?.state == .connected else { return }
    update(disconnection.id) {
      $0.state = .disconnected
      $0.lastDisconnect = disconnection.disconnect
      $0.services = []
    }
    let error = disconnection.disconnect.error
    await bus.publish(DomainEvent(
      kind: .disconnected,
      deviceId: disconnection.id,
      detail: disconnection.disconnect.reason.rawValue,
      errorCode: error?.code,
      platformCode: error?.platformCode
    ))
  }

  // MARK: - State

  private func update(_ id: DeviceId, _ change: (inout Entry) -> Void) {
    var entry = devices[id] ?? Entry()
    let before = (entry.state, entry.lastDisconnect, entry.name)
    change(&entry)
    devices[id] = entry
    if before != (entry.state, entry.lastDisconnect, entry.name), let snapshot = self.change(for: id) {
      observers.values.forEach { $0.yield(snapshot) }
    }
  }

  private func change(for id: DeviceId) -> ConnectionChange? {
    guard let entry = devices[id] else { return nil }
    return ConnectionChange(id: id, name: entry.name, state: entry.state, lastDisconnect: entry.lastDisconnect)
  }

  private func removeObserver(_ id: UUID) {
    observers[id] = nil
  }
}

private extension Duration {
  var milliseconds: Int64 {
    components.seconds * 1000 + components.attoseconds / 1_000_000_000_000_000
  }
}
