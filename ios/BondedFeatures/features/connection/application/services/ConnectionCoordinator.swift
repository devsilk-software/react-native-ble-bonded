import Foundation

/// Owns every device's connection: connects and discovers in one step, gives up after the timeout, retries
/// failures that may pass on their own, shares one attempt between callers, cancels an attempt on `disconnect`,
/// and reports each change, current state first (decision A6), and publishes it for diagnostics.
public actor ConnectionCoordinator {
  private let source: any ConnectionSource
  private let bus: EventBus
  private let retry: RetryPolicy
  private let store: any RememberedDeviceStore
  private let now: @Sendable () -> Int64
  private var devices: [DeviceId: Entry] = [:]
  private var remembered: [DeviceId: RememberedDevice] = [:]
  private var hasLoaded = false
  /// The last save; each new one waits for it and writes the list as it is then, so saves never land out of order.
  private var saving: Task<Void, Never>?
  private var observers: [UUID: AsyncStream<ConnectionChange>.Continuation] = [:]
  private var isWatching = false

  private struct Entry {
    var name: String?
    var state: ConnectionState = .disconnected
    var lastDisconnect: Disconnect?
    var services: [GattService] = []
    var attempt: Task<[GattService], any Error>?
    var isCancelling = false
    /// A `disconnect()` still under way; a new connection waits for it.
    var disconnecting: Task<Void, Never>?
  }

  public init(
    source: any ConnectionSource,
    bus: EventBus,
    store: any RememberedDeviceStore,
    retry: RetryPolicy = RetryPolicy(),
    now: @escaping @Sendable () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }
  ) {
    self.source = source
    self.bus = bus
    self.store = store
    self.retry = retry
    self.now = now
  }

  /// Disconnects the device and forgets it. The system's pairing stays.
  public func forget(_ id: DeviceId) async {
    await loadRemembered()
    await disconnect(id)
    await bus.publish(DomainEvent(kind: .forgotten, deviceId: id))
    guard remembered.removeValue(forKey: id) != nil else { return }
    emit(id)
    await persist()
  }

  /// Every known device's current connection, remembered devices included.
  public func connections() async -> [ConnectionChange] {
    await loadRemembered()
    return Set(devices.keys).union(remembered.keys).sorted { $0.rawValue < $1.rawValue }.compactMap(change(for:))
  }

  /// Saves the remembered devices as they are once the previous save has finished.
  private func persist() async {
    let previous = saving
    let task = Task {
      await previous?.value
      await self.store.save(Array(await self.rememberedSnapshot()))
    }
    saving = task
    await task.value
  }

  private func rememberedSnapshot() -> [RememberedDevice] {
    Array(remembered.values)
  }

  /// The remembered devices, most recently connected first.
  public func rememberedDevices() async -> [RememberedDevice] {
    await loadRemembered()
    return remembered.values.sorted { $0.lastConnectedAt > $1.lastConnectedAt }
  }

  private func loadRemembered() async {
    guard !hasLoaded else { return }
    hasLoaded = true
    for device in await store.load() where remembered[device.id] == nil {
      remembered[device.id] = device
    }
  }

  /// Remembers the device in memory; the caller reports the change, together with the connection's, and saves it.
  private func remember(_ id: DeviceId, name: String?, identity: DeviceIdentity?) {
    let known = remembered[id]?.identity ?? DeviceIdentity(name: name)
    remembered[id] = RememberedDevice(id: id, name: name, identity: known.updated(with: identity), lastConnectedAt: now())
  }

  /// Connects and discovers the device's services; returns once it's ready. Returns at once when it's already
  /// connected, and shares the running attempt when one is under way. Throws a `BleError`.
  public func connect(_ id: DeviceId, options: ConnectOptions = ConnectOptions()) async throws {
    await loadRemembered()
    await watchDisconnections()
    while let disconnecting = devices[id]?.disconnecting {
      await disconnecting.value
    }
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
    if let disconnecting = entry.disconnecting {
      await disconnecting.value
      return
    }
    guard entry.state == .connected else { return }
    update(id) { $0.state = .disconnecting }
    let task = Task { await self.finishDisconnect(id) }
    devices[id]?.disconnecting = task
    await task.value
  }

  private func finishDisconnect(_ id: DeviceId) async {
    await source.disconnect(id)
    update(id) {
      $0.state = .disconnected
      $0.lastDisconnect = Disconnect(reason: .requested)
      $0.services = []
      $0.disconnecting = nil
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

  /// Every known device's current connection, remembered devices included, then every change, from the moment
  /// this returns.
  public func changes() async -> AsyncStream<ConnectionChange> {
    await loadRemembered()
    let (stream, continuation) = AsyncStream<ConnectionChange>.makeStream()
    let id = UUID()
    continuation.onTermination = { _ in
      Task { await self.removeObserver(id) }
    }
    observers[id] = continuation
    for deviceId in Set(devices.keys).union(remembered.keys).sorted(by: { $0.rawValue < $1.rawValue }) {
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
      let name = await source.name(of: id) ?? devices[id]?.name
      // No await from this check to `connected`: a `disconnect()` or `forget()` either comes before it, and the link
      // goes, or after it, and finds the device connected.
      if devices[id]?.isCancelling == true {
        await source.disconnect(id)
        throw CancellationError()
      }
      if options.remember {
        remember(id, name: name, identity: options.identity)
      }
      update(id) {
        $0.state = .connected
        $0.services = services
        $0.attempt = nil
        $0.name = name ?? $0.name
      }
      if options.remember {
        await persist()
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
    if before != (entry.state, entry.lastDisconnect, entry.name) {
      emit(id)
    }
  }

  private func emit(_ id: DeviceId) {
    let snapshot = change(for: id)
      ?? ConnectionChange(id: id, name: nil, state: .disconnected, lastDisconnect: nil)
    observers.values.forEach { $0.yield(snapshot) }
  }

  private func change(for id: DeviceId) -> ConnectionChange? {
    let saved = remembered[id]
    guard let entry = devices[id] else {
      return saved.map { ConnectionChange(id: id, name: $0.name, state: .disconnected, lastDisconnect: nil, remembered: $0) }
    }
    return ConnectionChange(id: id, name: entry.name ?? saved?.name, state: entry.state, lastDisconnect: entry.lastDisconnect, remembered: saved)
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
