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
  private var hasLoaded = false
  private var recording: Task<Void, Never>?
  private var pendingSave: Task<Void, Never>?
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
  public func configure(enabled: Bool?, capacity: Int?) async {
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
    await store.delete()
  }

  /// Every event recorded from now on.
  public nonisolated func liveEvents() -> AsyncStream<DiagnosticEvent> {
    let (stream, continuation) = AsyncStream<DiagnosticEvent>.makeStream()
    let id = UUID()
    continuation.onTermination = { _ in
      Task { await self.removeObserver(id) }
    }
    Task { await self.addObserver(id, continuation) }
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
      log = EventLog(capacity: log.capacity, events: await store.load() + log.events)
      hasLoaded = true
    }
    let events = bus.events()
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
    let event = DiagnosticEvent(
      kind: kind,
      at: environment.now(),
      appActivity: await environment.appActivity(),
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
    await store.save(log.events)
  }

  private func addObserver(_ id: UUID, _ continuation: AsyncStream<DiagnosticEvent>.Continuation) {
    observers[id] = continuation
  }

  private func removeObserver(_ id: UUID) {
    observers[id] = nil
  }
}
