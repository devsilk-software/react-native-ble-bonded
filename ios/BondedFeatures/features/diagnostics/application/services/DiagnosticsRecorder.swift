import Foundation

/// Records domain events from the event bus while diagnostics are on, keeps the newest up to the capacity, and
/// saves them in batches so they survive the app being killed. Off by default: nothing is recorded or written.
public actor DiagnosticsRecorder {
  private let bus: EventBus
  private let store: any DiagnosticsStore
  private let environment: any DiagnosticsEnvironment
  private let saveDelay: Duration
  private var log = EventLog()
  private var isRecording = false
  /// Whether GATT operations are recorded too; off by default, since they can be many.
  private var isRecordingGatt = false
  private var hasLoaded = false
  private var recording: Task<Void, Never>?
  private var pendingSave: Task<Void, Never>?
  /// The last storage operation. Each new one waits for it, so saves and deletes reach the store in the order
  /// they were asked for, and a save taken before a clear can't write after it.
  private var storage: Task<Void, Never>?
  private var observers: [UUID: AsyncStream<DiagnosticEvent>.Continuation] = [:]

  public init(
    bus: EventBus,
    store: any DiagnosticsStore,
    environment: any DiagnosticsEnvironment,
    saveDelay: Duration = .seconds(2)
  ) {
    self.bus = bus
    self.store = store
    self.environment = environment
    self.saveDelay = saveDelay
  }

  public var isEnabled: Bool { isRecording }

  /// Changes the settings; `nil` keeps the current value. Turning off deletes the stored events.
  public func configure(enabled: Bool?, capacity: Int?, gatt: Bool? = nil) async {
    if let gatt {
      isRecordingGatt = gatt
    }
    if let capacity {
      log.setCapacity(capacity)
      if isRecording { scheduleSave() }
    }
    guard let enabled, enabled != isRecording else { return }
    if enabled {
      await start()
    } else {
      await stop()
    }
  }

  public func events(since: Int64?) -> [DiagnosticEvent] {
    log.events(since: since)
  }

  public func clear() async {
    log.removeAll()
    pendingSave?.cancel()
    pendingSave = nil
    let store = self.store
    await inOrder { await store.delete() }
  }

  /// Every event recorded from the moment this returns: the observer is registered before it does.
  public func liveEvents() -> AsyncStream<DiagnosticEvent> {
    let (stream, continuation) = AsyncStream<DiagnosticEvent>.makeStream()
    let id = UUID()
    continuation.onTermination = { _ in
      Task { await self.removeObserver(id) }
    }
    observers[id] = continuation
    return stream
  }

  /// Writes the events now instead of after the batching delay.
  func saveNow() async {
    pendingSave?.cancel()
    await savePending()
  }

  // MARK: - Recording

  private func start() async {
    isRecording = true
    if !hasLoaded {
      let store = self.store
      let stored = await inOrder { await store.load() }
      log = EventLog(capacity: log.capacity, events: stored + log.events)
      hasLoaded = true
    }
    let events = await bus.events()
    recording = Task { [weak self] in
      for await event in events {
        await self?.record(event)
      }
    }
  }

  private func stop() async {
    isRecording = false
    recording?.cancel()
    recording = nil
    log.removeAll()
    hasLoaded = true
    await clear()
  }

  private func record(_ domainEvent: DomainEvent) async {
    guard isRecording, let kind = DiagnosticEvent.Kind(rawValue: domainEvent.kind.rawValue) else { return }
    if kind == .gattOperation, !isRecordingGatt { return }
    let at = environment.now()
    let appActivity = await environment.appActivity()
    // Diagnostics may have been turned off while the app's state was being read.
    guard isRecording, !Task.isCancelled else { return }
    let event = DiagnosticEvent(
      kind: kind,
      at: at,
      appActivity: appActivity,
      deviceId: domainEvent.deviceId?.rawValue,
      code: domainEvent.errorCode?.rawValue,
      platformCode: domainEvent.platformCode,
      detail: domainEvent.detail
    )
    log.append(event)
    for observer in observers.values {
      observer.yield(event)
    }
    scheduleSave()
  }

  private func scheduleSave() {
    guard pendingSave == nil else { return }
    let delay = saveDelay
    pendingSave = Task { [weak self] in
      try? await Task.sleep(for: delay)
      guard !Task.isCancelled else { return }
      await self?.savePending()
    }
  }

  /// Saves without cancelling the pending save, which may be the task calling this.
  private func savePending() async {
    pendingSave = nil
    guard isRecording else { return }
    let store = self.store
    let events = log.events
    await inOrder { await store.save(events) }
  }

  /// Runs `operation` after every storage operation asked for before it, and returns its result.
  private func inOrder<Result: Sendable>(_ operation: @escaping @Sendable () async -> Result) async -> Result {
    let previous = storage
    let task = Task {
      await previous?.value
      return await operation()
    }
    storage = Task { _ = await task.value }
    return await task.value
  }

  private func removeObserver(_ id: UUID) {
    observers[id] = nil
  }
}
