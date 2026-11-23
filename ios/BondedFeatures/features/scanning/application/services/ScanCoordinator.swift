/// Runs one scan at a time: starting a new one replaces the running one. Applies the name filter, reports each
/// device once unless duplicates are allowed, stops after the timeout, pauses an unfiltered scan while the app
/// is in the background (decision R3), and publishes when a scan starts and stops.
///
/// Every operation that starts or stops the platform's scanner runs after the ones asked for before it, so a
/// late timeout or failure can never stop a newer scan.
public actor ScanCoordinator {
  private let source: any ScanSource
  private let environment: any ScanEnvironment
  private let bus: EventBus
  private var nextId = 1
  private var current: Session?
  private var last: Task<Void, Never>?

  private struct Session {
    let id: Int
    let request: ScanRequest
    let continuation: AsyncStream<ScanEvent>.Continuation
    var seen: Set<DeviceId> = []
    /// Counts the times the platform's scanner was started for this scan; reports from an older run are stale.
    var run = 0
    var isPaused = false
    var tasks: [Task<Void, Never>] = []
  }

  public init(source: any ScanSource, environment: any ScanEnvironment, bus: EventBus) {
    self.source = source
    self.environment = environment
    self.bus = bus
  }

  /// Starts a scan, replacing the running one, and returns its ID and what it reports. Throws a `BleError` when
  /// the phone can't scan, or for an unfiltered scan while the app is in the background.
  public func start(_ request: ScanRequest) async throws -> ScanSession {
    try await inOrder { try await self.begin(request) }
  }

  /// Stops the scan with this ID; does nothing when it has already ended.
  public func stop(_ id: Int) async {
    await endInOrder(id, .stopped, error: nil)
  }

  // MARK: - Lifecycle

  private func begin(_ request: ScanRequest) async throws -> ScanSession {
    if let error = await source.readiness().error {
      throw error
    }
    if !request.isFiltered, await environment.isInBackground() {
      throw BleError(code: .unsupported, message: "Scanning in the background needs a service filter", operation: "scan")
    }
    await end(.replaced, error: nil)

    let id = nextId
    nextId += 1
    let (events, continuation) = AsyncStream<ScanEvent>.makeStream()
    current = Session(id: id, request: request, continuation: continuation)
    await runSource(for: id)

    if let timeout = request.timeout {
      addTask(to: id) {
        try? await Task.sleep(for: timeout)
        guard !Task.isCancelled else { return }
        await self.endInOrder(id, .timeout, error: nil)
      }
    }
    if !request.isFiltered {
      let changes = await environment.backgroundChanges()
      addTask(to: id) {
        for await isInBackground in changes {
          await self.setBackgroundInOrder(isInBackground, for: id)
        }
      }
    }
    await bus.publish(DomainEvent(kind: .scanStarted, detail: request.isFiltered ? "filtered" : "unfiltered"))
    return ScanSession(id: id, events: events)
  }

  /// Ends the current scan, whichever it is.
  private func end(_ reason: ScanStopReason, error: BleError?) async {
    guard let session = current else { return }
    current = nil
    session.tasks.forEach { $0.cancel() }
    await source.stop()
    session.continuation.yield(.stopped(reason, error: error))
    session.continuation.finish()
    await bus.publish(DomainEvent(kind: .scanStopped, detail: reason.rawValue, errorCode: error?.code))
  }

  private func endInOrder(_ id: Int, _ reason: ScanStopReason, error: BleError?) async {
    try? await inOrder {
      guard await self.currentId == id else { return }
      await self.end(reason, error: error)
    }
  }

  private var currentId: Int? { current?.id }

  // MARK: - The platform's scanner

  private func runSource(for id: Int) async {
    guard var session = current, session.id == id else { return }
    session.run += 1
    session.isPaused = false
    current = session
    let run = session.run
    let advertisements = await source.start(services: session.request.services, allowDuplicates: session.request.allowDuplicates)
    addTask(to: id) {
      for await advertisement in advertisements {
        await self.receive(advertisement, id: id, run: run)
      }
      await self.sourceEnded(id: id, run: run)
    }
  }

  private func receive(_ advertisement: Advertisement, id: Int, run: Int) {
    guard var session = current, session.id == id, session.run == run else { return }
    if let name = session.request.name, !name.matches(advertisement.name) { return }
    if !session.request.allowDuplicates {
      guard session.seen.insert(advertisement.id).inserted else { return }
      current = session
    }
    session.continuation.yield(.device(ScannedDevice(advertisement, isFull: session.request.fullAdvertisement)))
  }

  /// The scanner stopped without being asked: Bluetooth went off or permission was revoked.
  private func sourceEnded(id: Int, run: Int) async {
    guard let session = current, session.id == id, session.run == run, !session.isPaused else { return }
    let error = await source.readiness().error
      ?? BleError(code: .unknown, message: "Scanning stopped unexpectedly", operation: "scan")
    await endInOrder(id, .failed, error: error)
  }

  private func setBackgroundInOrder(_ isInBackground: Bool, for id: Int) async {
    try? await inOrder {
      await self.setBackground(isInBackground, for: id)
    }
  }

  private func setBackground(_ isInBackground: Bool, for id: Int) async {
    guard let session = current, session.id == id else { return }
    if isInBackground, !session.isPaused {
      current?.isPaused = true
      await source.stop()
    } else if !isInBackground, session.isPaused {
      await runSource(for: id)
    }
  }

  // MARK: - Helpers

  private func addTask(to id: Int, _ operation: @escaping @Sendable () async -> Void) {
    guard current?.id == id else { return }
    current?.tasks.append(Task { await operation() })
  }

  /// Runs `operation` after every operation asked for before it, and returns its result.
  private func inOrder<Result: Sendable>(_ operation: @escaping @Sendable () async throws -> Result) async throws -> Result {
    let previous = last
    let task = Task {
      await previous?.value
      return try await operation()
    }
    last = Task { _ = try? await task.value }
    return try await task.value
  }
}

/// A running scan: its ID, for stopping it, and what it reports.
public struct ScanSession: Sendable {
  public let id: Int
  public let events: AsyncStream<ScanEvent>
}
