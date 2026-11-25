import CoreBluetooth
import Dispatch
import Foundation

/// The one `CBCentralManager` Bonded uses, shared by every feature's adapters. It knows Core Bluetooth and
/// nothing about Bonded's features (decision A2).
///
/// The manager is created lazily, because creating it shows the iOS permission prompt when the user hasn't
/// decided yet. Until the app asks (`requestAuthorization()`) or the user has already allowed Bluetooth, the
/// state reads `unauthorized` from `CBManager.authorization`, which never prompts.
///
/// Core Bluetooth calls its delegate on Bonded's own serial queue, never the main thread. The delegate passes
/// each callback, in order, into an `AsyncStream` that this actor consumes, so all state lives in the actor.
/// An actor running directly on that queue would need `DispatchSerialQueue`, which requires iOS 17; Bonded
/// supports iOS 16.
public actor BluetoothCentral {
  private var manager: CBCentralManager?
  private var delegate: CentralDelegate?
  private var state: CBManagerState = .unknown
  private var hasReceivedState = false
  private var stateWaiters: [CheckedContinuation<CBManagerState, Never>] = []
  private var authorizationWaiters: [CheckedContinuation<CBManagerAuthorization, Never>] = []
  private var stateObservers: [UUID: AsyncStream<CBManagerState>.Continuation] = [:]
  private var authorizationObservers: [UUID: AsyncStream<CBManagerAuthorization>.Continuation] = [:]
  private var foregroundObserver: (any NSObjectProtocol)?
  private var discoveries: AsyncStream<Discovery>.Continuation?
  private var peripherals: [UUID: CBPeripheral] = [:]
  /// Peripherals connected or being connected. Tracked here because when Bluetooth goes off, Core Bluetooth marks
  /// every peripheral disconnected without telling each one.
  private var connections: Set<UUID> = []
  private var connectWaiters: [UUID: CheckedContinuation<Void, any Error>] = [:]
  private var disconnectWaiters: [UUID: [CheckedContinuation<Void, Never>]] = [:]
  private var serviceWaiters: [UUID: CheckedContinuation<Void, any Error>] = [:]
  private var characteristicWaiters: [UUID: (remaining: Int, continuation: CheckedContinuation<Void, any Error>)] = [:]
  private var disconnectionObservers: [UUID: AsyncStream<CentralDisconnect>.Continuation] = [:]
  /// The last operation on each peripheral; each new one waits for it and its answer.
  private var operations: [UUID: Task<Void, Never>] = [:]
  /// Callers waiting for an operation's result, by token, so a cancel reaches only its own operation.
  private var readCallers: [UUID: (identifier: UUID, continuation: CheckedContinuation<Data, any Error>)] = [:]
  private var writeCallers: [UUID: (identifier: UUID, continuation: CheckedContinuation<Void, any Error>)] = [:]
  /// The request each peripheral is waiting on, and for which characteristic.
  private var answerWaiters: [UUID: (request: UUID, key: String, continuation: CheckedContinuation<Result<Data?, any Error>, Never>)] = [:]
  private var readyWaiters: [UUID: CheckedContinuation<Void, any Error>] = [:]
  /// Callers waiting for a signal strength reading, by token, and the tokens waiting on each peripheral.
  private var rssiCallers: [UUID: (identifier: UUID, continuation: CheckedContinuation<Int, any Error>)] = [:]
  private var rssiRequests: [UUID: [UUID]] = [:]
  /// Callers waiting for a characteristic's descriptors, by token.
  private var descriptorCallers: [UUID: (identifier: UUID, continuation: CheckedContinuation<[String]?, any Error>)] = [:]
  /// The piece each chunked write is on, by its caller's token.
  private var pieceProgress: [UUID: Int] = [:]
  private var notificationObservers: [UUID: AsyncStream<CentralNotification>.Continuation] = [:]

  public init() {}

  /// Whether the app may use Bluetooth, read without creating the manager, so it never prompts.
  public nonisolated var authorization: CBManagerAuthorization {
    CBManager.authorization
  }

  /// The manager's state. Without the user's permission and before the app has asked, reports `unauthorized`
  /// without creating the manager. Otherwise waits for Core Bluetooth's first report if needed.
  public func managerState() async -> CBManagerState {
    if manager == nil, authorization != .allowedAlways {
      return .unauthorized
    }
    activate()
    if hasReceivedState {
      return state
    }
    return await withCheckedContinuation { continuation in
      stateWaiters.append(continuation)
    }
  }

  /// Asks for Bluetooth permission if the user hasn't decided yet (creating the manager shows the prompt), and
  /// returns the user's decision.
  public func requestAuthorization() async -> CBManagerAuthorization {
    if authorization != .notDetermined {
      activateIfAuthorized()
      return authorization
    }
    // Without Bluetooth Low Energy iOS may never prompt, and an existing manager won't report again.
    if hasReceivedState, state == .unsupported {
      return authorization
    }
    activate()
    return await withCheckedContinuation { continuation in
      authorizationWaiters.append(continuation)
    }
  }

  /// Every state change, from now on.
  public nonisolated func stateUpdates() -> AsyncStream<CBManagerState> {
    let (stream, continuation) = AsyncStream<CBManagerState>.makeStream()
    let id = UUID()
    continuation.onTermination = { _ in
      Task { await self.removeStateObserver(id) }
    }
    Task { await self.addStateObserver(id, continuation) }
    return stream
  }

  /// Every authorization change, from now on, including changes made in Settings while the app was in the
  /// background.
  public nonisolated func authorizationUpdates() -> AsyncStream<CBManagerAuthorization> {
    let (stream, continuation) = AsyncStream<CBManagerAuthorization>.makeStream()
    let id = UUID()
    continuation.onTermination = { _ in
      Task { await self.removeAuthorizationObserver(id) }
    }
    Task { await self.addAuthorizationObserver(id, continuation) }
    return stream
  }

  // MARK: - Scanning

  /// Starts scanning, replacing a running scan, and returns every discovery from now on. The stream finishes
  /// when `stopScan()` is called or Bluetooth leaves the powered-on state, which stops Core Bluetooth's scan.
  public func startScan(services: [CBUUID]?, allowDuplicates: Bool) async -> AsyncStream<Discovery> {
    stopScan()
    let (stream, continuation) = AsyncStream<Discovery>.makeStream()
    guard await managerState() == .poweredOn, let manager else {
      continuation.finish()
      return stream
    }
    discoveries = continuation
    manager.scanForPeripherals(withServices: services, options: [CBCentralManagerScanOptionAllowDuplicatesKey: allowDuplicates])
    return stream
  }

  public func stopScan() {
    if manager?.state == .poweredOn {
      manager?.stopScan()
    }
    discoveries?.finish()
    discoveries = nil
  }

  // MARK: - Connections

  /// Connects to a peripheral the system knows (one found by a scan or connected before) and returns once
  /// connected. Core Bluetooth waits for the peripheral without a timeout; cancelling the calling task cancels
  /// the attempt.
  public func connect(_ identifier: UUID) async throws {
    guard await managerState() == .poweredOn, let manager else { throw CentralError.notReady }
    guard let peripheral = manager.retrievePeripherals(withIdentifiers: [identifier]).first else {
      throw CentralError.unknownPeripheral
    }
    peripherals[identifier] = peripheral
    peripheral.delegate = delegate
    connections.insert(identifier)
    if peripheral.state == .connected { return }
    try await withTaskCancellationHandler {
      try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, any Error>) in
        connectWaiters[identifier]?.resume(throwing: CancellationError())
        connectWaiters[identifier] = continuation
        manager.connect(peripheral)
      }
    } onCancel: {
      Task { await self.cancelConnection(identifier) }
    }
  }

  /// Discovers every service and every characteristic of a connected peripheral. Cancelling the calling task
  /// cancels the connection, which ends the discovery.
  public func discover(_ identifier: UUID) async throws -> [DiscoveredService] {
    guard let peripheral = peripherals[identifier], peripheral.state == .connected else { throw CentralError.notConnected }
    try await withTaskCancellationHandler {
      try Task.checkCancellation()
      try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, any Error>) in
        serviceWaiters[identifier] = continuation
        peripheral.discoverServices(nil)
      }
      let services = peripheral.services ?? []
      if !services.isEmpty {
        try Task.checkCancellation()
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, any Error>) in
          characteristicWaiters[identifier] = (services.count, continuation)
          for service in services {
            peripheral.discoverCharacteristics(nil, for: service)
          }
        }
      }
    } onCancel: {
      Task { await self.cancelConnection(identifier) }
    }
    return (peripheral.services ?? []).map(DiscoveredService.init)
  }

  /// Disconnects a peripheral and returns once it has, or at once when it isn't connected.
  public func disconnect(_ identifier: UUID) async {
    guard let manager, let peripheral = peripherals[identifier], peripheral.state != .disconnected else { return }
    await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
      disconnectWaiters[identifier, default: []].append(continuation)
      manager.cancelPeripheralConnection(peripheral)
    }
  }

  /// Every disconnection of a connected peripheral that `disconnect` didn't cause, from the moment this returns.
  public func disconnections() -> AsyncStream<CentralDisconnect> {
    let (stream, continuation) = AsyncStream<CentralDisconnect>.makeStream()
    let id = UUID()
    continuation.onTermination = { _ in
      Task { await self.removeDisconnectionObserver(id) }
    }
    disconnectionObservers[id] = continuation
    return stream
  }

  /// The peripheral's name as Core Bluetooth last saw it.
  public func peripheralName(_ identifier: UUID) -> String? {
    peripherals[identifier]?.name ?? manager?.retrievePeripherals(withIdentifiers: [identifier]).first?.name
  }

  private func cancelConnection(_ identifier: UUID) {
    connections.remove(identifier)
    if let peripheral = peripherals[identifier] {
      manager?.cancelPeripheralConnection(peripheral)
    }
    fail(identifier, with: CancellationError())
  }

  private func removeDisconnectionObserver(_ id: UUID) {
    disconnectionObservers[id] = nil
  }

  /// Ends every wait on this peripheral with `error`.
  private func fail(_ identifier: UUID, with error: any Error) {
    failOperations(identifier, with: CentralError.notConnected)
    connectWaiters.removeValue(forKey: identifier)?.resume(throwing: error)
    serviceWaiters.removeValue(forKey: identifier)?.resume(throwing: error)
    characteristicWaiters.removeValue(forKey: identifier)?.continuation.resume(throwing: error)
  }

  private func handleConnection(_ event: CentralEvent) {
    switch event {
    case let .connected(identifier):
      connectWaiters.removeValue(forKey: identifier)?.resume()
    case let .failedToConnect(identifier, error):
      connections.remove(identifier)
      fail(identifier, with: CentralError.platform(error))
    case let .disconnected(identifier, error):
      connections.remove(identifier)
      let wasRequested = disconnectWaiters[identifier] != nil
      disconnectWaiters.removeValue(forKey: identifier)?.forEach { $0.resume() }
      let wasSettingUp = connectWaiters[identifier] != nil || serviceWaiters[identifier] != nil
        || characteristicWaiters[identifier] != nil
      fail(identifier, with: CentralError.platform(error ?? PlatformErrorInfo(domain: "", code: 0, message: "Disconnected")))
      if !wasRequested, !wasSettingUp {
        let disconnect = CentralDisconnect(identifier: identifier, error: error, isBluetoothOff: false)
        disconnectionObservers.values.forEach { $0.yield(disconnect) }
      }
    case let .servicesDiscovered(identifier, error):
      if let error {
        serviceWaiters.removeValue(forKey: identifier)?.resume(throwing: CentralError.platform(error))
      } else {
        serviceWaiters.removeValue(forKey: identifier)?.resume()
      }
    case let .characteristicsDiscovered(identifier, error):
      guard var waiter = characteristicWaiters[identifier] else { return }
      if let error {
        characteristicWaiters[identifier] = nil
        waiter.continuation.resume(throwing: CentralError.platform(error))
        return
      }
      waiter.remaining -= 1
      if waiter.remaining == 0 {
        characteristicWaiters[identifier] = nil
        waiter.continuation.resume()
      } else {
        characteristicWaiters[identifier] = waiter
      }
    default:
      break
    }
  }

  /// Bluetooth left the powered-on state: Core Bluetooth drops every connection without telling each one.
  private func dropConnections() {
    let dropped = connections
    connections.removeAll()
    for identifier in dropped {
      disconnectWaiters.removeValue(forKey: identifier)?.forEach { $0.resume() }
      fail(identifier, with: CentralError.notReady)
      let disconnect = CentralDisconnect(identifier: identifier, error: nil, isBluetoothOff: true)
      disconnectionObservers.values.forEach { $0.yield(disconnect) }
    }
  }

  // MARK: - Characteristics

  /// The characteristic's property bits, or `nil` when the connected peripheral has no such characteristic.
  /// `service` and `characteristic` are full lowercase UUIDs.
  public func characteristicProperties(_ identifier: UUID, service: String, characteristic: String) throws -> UInt? {
    try self.characteristic(on: identifier, service: service, characteristic: characteristic)?.properties.rawValue
  }

  /// The longest value one write without response may carry.
  public func maximumWriteLength(_ identifier: UUID) throws -> Int {
    try connectedPeripheral(identifier).maximumWriteValueLength(for: .withoutResponse)
  }

  /// Reads the characteristic's value. Cancelling stops waiting at once; the peripheral's next operation still waits
  /// for this one's answer, so an answer never reaches the wrong caller.
  public func read(_ identifier: UUID, service: String, characteristic: String) async throws -> Data {
    let token = UUID()
    return try await withTaskCancellationHandler {
      try await withCheckedThrowingContinuation { (caller: CheckedContinuation<Data, any Error>) in
        readCallers[token] = (identifier, caller)
        enqueue(on: identifier) { await self.runRead(token, identifier, service: service, characteristic: characteristic) }
      }
    } onCancel: {
      Task { await self.abandon(token) }
    }
  }

  /// Writes `data`. With response, returns once the peripheral confirmed; without, once Core Bluetooth took it,
  /// waiting for room when its buffer is full. Cancelling stops waiting at once.
  public func write(_ data: Data, to identifier: UUID, service: String, characteristic: String, withResponse: Bool) async throws {
    let token = UUID()
    try await withTaskCancellationHandler {
      try await withCheckedThrowingContinuation { (caller: CheckedContinuation<Void, any Error>) in
        writeCallers[token] = (identifier, caller)
        enqueue(on: identifier) {
          await self.runWrite(token, data, to: identifier, service: service, characteristic: characteristic, withResponse: withResponse)
        }
      }
    } onCancel: {
      Task { await self.abandon(token) }
    }
  }

  /// Writes `pieces` in order as one operation, so no other operation on the peripheral runs between them. Each piece
  /// waits as `write` does; one that takes longer than `limit` fails. Throws `PieceError` naming the piece that failed;
  /// the pieces after it aren't sent. Cancelling stops waiting at once and sends no further piece.
  public func write(
    pieces: [Data],
    to identifier: UUID,
    service: String,
    characteristic: String,
    withResponse: Bool,
    limit: Duration
  ) async throws {
    let token = UUID()
    try await withTaskCancellationHandler {
      try await withCheckedThrowingContinuation { (caller: CheckedContinuation<Void, any Error>) in
        writeCallers[token] = (identifier, caller)
        pieceProgress[token] = 0
        enqueue(on: identifier) {
          await self.runWritePieces(
            token,
            pieces,
            to: identifier,
            service: service,
            characteristic: characteristic,
            withResponse: withResponse,
            limit: limit
          )
        }
      }
    } onCancel: {
      Task { await self.abandon(token) }
    }
  }

  /// Reads the connected peripheral's signal strength, in dBm. Not queued behind other operations: Core Bluetooth
  /// reads it apart from them. Callers waiting at the same time share the next reading. Cancelling stops waiting at
  /// once.
  public func readRssi(_ identifier: UUID) async throws -> Int {
    let token = UUID()
    return try await withTaskCancellationHandler {
      try await withCheckedThrowingContinuation { (caller: CheckedContinuation<Int, any Error>) in
        do {
          let peripheral = try connectedPeripheral(identifier)
          rssiCallers[token] = (identifier, caller)
          rssiRequests[identifier, default: []].append(token)
          peripheral.readRSSI()
        } catch {
          caller.resume(throwing: error)
        }
      }
    } onCancel: {
      Task { await self.abandon(token) }
    }
  }

  /// The characteristic's descriptor UUIDs, full and lowercase, discovered the first time and then kept by Core
  /// Bluetooth for the connection; `nil` when the peripheral has no such characteristic. Cancelling stops waiting.
  public func descriptorUuids(_ identifier: UUID, service: String, characteristic: String) async throws -> [String]? {
    let token = UUID()
    return try await withTaskCancellationHandler {
      try await withCheckedThrowingContinuation { (caller: CheckedContinuation<[String]?, any Error>) in
        descriptorCallers[token] = (identifier, caller)
        enqueue(on: identifier) { await self.runDiscoverDescriptors(token, identifier, service: service, characteristic: characteristic) }
      }
    } onCancel: {
      Task { await self.abandon(token) }
    }
  }

  /// Reads a descriptor's value, as bytes: Core Bluetooth's numbers become two little-endian bytes and its text
  /// UTF-8, as they are on the device. Cancelling stops waiting at once.
  public func readDescriptor(_ identifier: UUID, service: String, characteristic: String, descriptor: String) async throws -> Data {
    let token = UUID()
    return try await withTaskCancellationHandler {
      try await withCheckedThrowingContinuation { (caller: CheckedContinuation<Data, any Error>) in
        readCallers[token] = (identifier, caller)
        enqueue(on: identifier) {
          await self.runReadDescriptor(token, identifier, service: service, characteristic: characteristic, descriptor: descriptor)
        }
      }
    } onCancel: {
      Task { await self.abandon(token) }
    }
  }

  /// Writes a descriptor's value and returns once the peripheral confirmed. Cancelling stops waiting at once.
  public func writeDescriptor(_ data: Data, to identifier: UUID, service: String, characteristic: String, descriptor: String) async throws {
    let token = UUID()
    try await withTaskCancellationHandler {
      try await withCheckedThrowingContinuation { (caller: CheckedContinuation<Void, any Error>) in
        writeCallers[token] = (identifier, caller)
        enqueue(on: identifier) {
          await self.runWriteDescriptor(token, data, identifier, service: service, characteristic: characteristic, descriptor: descriptor)
        }
      }
    } onCancel: {
      Task { await self.abandon(token) }
    }
  }

  /// Switches notifications (or indications; Core Bluetooth picks) on or off and returns once the peripheral
  /// confirmed. Cancelling stops waiting at once.
  public func setNotify(_ isOn: Bool, _ identifier: UUID, service: String, characteristic: String) async throws {
    let token = UUID()
    try await withTaskCancellationHandler {
      try await withCheckedThrowingContinuation { (caller: CheckedContinuation<Void, any Error>) in
        writeCallers[token] = (identifier, caller)
        enqueue(on: identifier) { await self.runNotify(token, isOn, identifier, service: service, characteristic: characteristic) }
      }
    } onCancel: {
      Task { await self.abandon(token) }
    }
  }

  /// Every value a peripheral notifies, from the moment this returns.
  public func notifications() -> AsyncStream<CentralNotification> {
    let (stream, continuation) = AsyncStream<CentralNotification>.makeStream()
    let id = UUID()
    continuation.onTermination = { _ in
      Task { await self.removeNotificationObserver(id) }
    }
    notificationObservers[id] = continuation
    return stream
  }

  private func removeNotificationObserver(_ id: UUID) {
    notificationObservers[id] = nil
  }

  private func runNotify(_ token: UUID, _ isOn: Bool, _ identifier: UUID, service: String, characteristic: String) async {
    guard writeCallers[token] != nil else { return }
    let result: Result<Void, any Error>
    do {
      let peripheral = try connectedPeripheral(identifier)
      guard let target = try self.characteristic(on: identifier, service: service, characteristic: characteristic) else {
        throw CentralError.notConnected
      }
      result = await answer(on: identifier, key: Self.notifyKey(service, characteristic)) {
        peripheral.setNotifyValue(isOn, for: target)
      }.map { _ in () }
    } catch {
      result = .failure(error)
    }
    writeCallers.removeValue(forKey: token)?.continuation.resume(with: result)
  }

  /// Which characteristic a notification-state answer belongs to; apart from value answers on the same one.
  static func notifyKey(_ service: String, _ characteristic: String) -> String {
    "notify|" + key(service, characteristic)
  }

  /// One operation runs at a time per peripheral, each after the one before has its answer.
  private func enqueue(on identifier: UUID, _ operation: @escaping @Sendable () async -> Void) {
    let previous = operations[identifier]
    operations[identifier] = Task {
      await previous?.value
      await operation()
    }
  }

  private func runRead(_ token: UUID, _ identifier: UUID, service: String, characteristic: String) async {
    // A caller that gave up while queued is never sent.
    guard readCallers[token] != nil else { return }
    let result: Result<Data, any Error>
    do {
      let peripheral = try connectedPeripheral(identifier)
      guard let target = try self.characteristic(on: identifier, service: service, characteristic: characteristic) else {
        throw CentralError.notConnected
      }
      let key = Self.key(service, characteristic)
      result = await answer(on: identifier, key: key) { peripheral.readValue(for: target) }.map { $0 ?? Data() }
    } catch {
      result = .failure(error)
    }
    readCallers.removeValue(forKey: token)?.continuation.resume(with: result)
  }

  private func runWrite(_ token: UUID, _ data: Data, to identifier: UUID, service: String, characteristic: String, withResponse: Bool) async {
    guard writeCallers[token] != nil else { return }
    let result: Result<Void, any Error>
    do {
      let peripheral = try connectedPeripheral(identifier)
      guard let target = try self.characteristic(on: identifier, service: service, characteristic: characteristic) else {
        throw CentralError.notConnected
      }
      if withResponse {
        result = await answer(on: identifier, key: Self.key(service, characteristic)) {
          peripheral.writeValue(data, for: target, type: .withResponse)
        }.map { _ in () }
      } else {
        while !peripheral.canSendWriteWithoutResponse {
          try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, any Error>) in
            readyWaiters[identifier] = continuation
          }
          // The caller may have given up while waiting for room.
          guard writeCallers[token] != nil else { return }
        }
        peripheral.writeValue(data, for: target, type: .withoutResponse)
        result = .success(())
      }
    } catch {
      result = .failure(error)
    }
    writeCallers.removeValue(forKey: token)?.continuation.resume(with: result)
  }

  private func runDiscoverDescriptors(_ token: UUID, _ identifier: UUID, service: String, characteristic: String) async {
    guard descriptorCallers[token] != nil else { return }
    let result: Result<[String]?, any Error>
    do {
      let peripheral = try connectedPeripheral(identifier)
      if let target = try self.characteristic(on: identifier, service: service, characteristic: characteristic) {
        if target.descriptors == nil {
          _ = try await answer(on: identifier, key: Self.descriptorsKey(service, characteristic)) {
            peripheral.discoverDescriptors(for: target)
          }.get()
        }
        result = .success((target.descriptors ?? []).map { Self.fullUuid($0.uuid) })
      } else {
        result = .success(nil)
      }
    } catch {
      result = .failure(error)
    }
    descriptorCallers.removeValue(forKey: token)?.continuation.resume(with: result)
  }

  private func runReadDescriptor(_ token: UUID, _ identifier: UUID, service: String, characteristic: String, descriptor: String) async {
    guard readCallers[token] != nil else { return }
    let result: Result<Data, any Error>
    do {
      let peripheral = try connectedPeripheral(identifier)
      let target = try self.descriptor(on: identifier, service: service, characteristic: characteristic, descriptor: descriptor)
      result = await answer(on: identifier, key: Self.descriptorKey(service, characteristic, descriptor)) {
        peripheral.readValue(for: target)
      }.map { $0 ?? Data() }
    } catch {
      result = .failure(error)
    }
    readCallers.removeValue(forKey: token)?.continuation.resume(with: result)
  }

  private func runWriteDescriptor(_ token: UUID, _ data: Data, _ identifier: UUID, service: String, characteristic: String, descriptor: String) async {
    guard writeCallers[token] != nil else { return }
    let result: Result<Void, any Error>
    do {
      let peripheral = try connectedPeripheral(identifier)
      let target = try self.descriptor(on: identifier, service: service, characteristic: characteristic, descriptor: descriptor)
      result = await answer(on: identifier, key: Self.descriptorKey(service, characteristic, descriptor)) {
        peripheral.writeValue(data, for: target)
      }.map { _ in () }
    } catch {
      result = .failure(error)
    }
    writeCallers.removeValue(forKey: token)?.continuation.resume(with: result)
  }

  /// The discovered descriptor; `notFound` when the device's services changed since the use case checked it.
  private func descriptor(on identifier: UUID, service: String, characteristic: String, descriptor: String) throws -> CBDescriptor {
    guard let target = try self.characteristic(on: identifier, service: service, characteristic: characteristic)?
      .descriptors?
      .first(where: { Self.fullUuid($0.uuid) == descriptor })
    else { throw CentralError.notFound }
    return target
  }

  /// Which characteristic a descriptor discovery answer belongs to.
  static func descriptorsKey(_ service: String, _ characteristic: String) -> String {
    "descriptors|" + key(service, characteristic)
  }

  /// Which descriptor a descriptor read or write answer belongs to.
  static func descriptorKey(_ service: String, _ characteristic: String, _ descriptor: String) -> String {
    "descriptor|\(key(service, characteristic))|\(descriptor)"
  }

  private func runWritePieces(
    _ token: UUID,
    _ pieces: [Data],
    to identifier: UUID,
    service: String,
    characteristic: String,
    withResponse: Bool,
    limit: Duration
  ) async {
    defer { pieceProgress[token] = nil }
    for (index, data) in pieces.enumerated() {
      // The caller gave up, or an earlier piece took too long: send nothing more.
      guard writeCallers[token] != nil else { return }
      pieceProgress[token] = index
      let failure: CentralError?
      // A piece that takes too long fails for the caller now; the operation still keeps its turn until the answer.
      let timer = Task {
        try await Task.sleep(for: limit)
        self.expirePiece(token, index: index)
      }
      do {
        let peripheral = try connectedPeripheral(identifier)
        guard let target = try self.characteristic(on: identifier, service: service, characteristic: characteristic) else {
          throw CentralError.notConnected
        }
        if withResponse {
          _ = try await answer(on: identifier, key: Self.key(service, characteristic)) {
            peripheral.writeValue(data, for: target, type: .withResponse)
          }.get()
        } else {
          while !peripheral.canSendWriteWithoutResponse {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, any Error>) in
              readyWaiters[identifier] = continuation
            }
            guard writeCallers[token] != nil else {
              timer.cancel()
              return
            }
          }
          peripheral.writeValue(data, for: target, type: .withoutResponse)
        }
        failure = nil
      } catch let error as CentralError {
        failure = error
      } catch {
        failure = .notConnected
      }
      timer.cancel()
      if let failure {
        failPiece(token, index: index, error: failure)
        return
      }
    }
    writeCallers.removeValue(forKey: token)?.continuation.resume()
  }

  private func failPiece(_ token: UUID, index: Int, error: CentralError?) {
    writeCallers.removeValue(forKey: token)?.continuation.resume(throwing: PieceError(index: index, error: error))
  }

  /// A piece's time ran out. Cancelling its timer can't stop one already waiting for the actor, so a timer for a
  /// piece that has finished meanwhile does nothing.
  private func expirePiece(_ token: UUID, index: Int) {
    guard pieceProgress[token] == index else { return }
    failPiece(token, index: index, error: nil)
  }

  /// Sends a request and waits for the answer for this characteristic, at most Bluetooth's own 30 seconds, or until
  /// the peripheral disconnects. Answers for other characteristics don't end the wait.
  private func answer(on identifier: UUID, key: String, send: () -> Void) async -> Result<Data?, any Error> {
    await withCheckedContinuation { (continuation: CheckedContinuation<Result<Data?, any Error>, Never>) in
      let request = UUID()
      answerWaiters[identifier] = (request, key, continuation)
      send()
      Task {
        try? await Task.sleep(for: .seconds(30))
        self.expire(identifier, request: request)
      }
    }
  }

  private func expire(_ identifier: UUID, request: UUID) {
    guard let waiter = answerWaiters[identifier], waiter.request == request else { return }
    answerWaiters[identifier] = nil
    waiter.continuation.resume(returning: .failure(CentralError.noAnswer))
  }

  /// The caller gave up: it gets `CancellationError` now; the operation, if already sent, keeps its slot until the
  /// answer arrives.
  private func abandon(_ token: UUID) {
    readCallers.removeValue(forKey: token)?.continuation.resume(throwing: CancellationError())
    writeCallers.removeValue(forKey: token)?.continuation.resume(throwing: CancellationError())
    descriptorCallers.removeValue(forKey: token)?.continuation.resume(throwing: CancellationError())
    if let caller = rssiCallers.removeValue(forKey: token) {
      rssiRequests[caller.identifier]?.removeAll { $0 == token }
      caller.continuation.resume(throwing: CancellationError())
    }
  }

  private func connectedPeripheral(_ identifier: UUID) throws -> CBPeripheral {
    guard let peripheral = peripherals[identifier], peripheral.state == .connected else { throw CentralError.notConnected }
    return peripheral
  }

  private func characteristic(on identifier: UUID, service: String, characteristic: String) throws -> CBCharacteristic? {
    let peripheral = try connectedPeripheral(identifier)
    return peripheral.services?
      .first { Self.fullUuid($0.uuid) == service }?
      .characteristics?
      .first { Self.fullUuid($0.uuid) == characteristic }
  }

  /// A Core Bluetooth UUID in its full 128-bit lowercase form.
  static func fullUuid(_ uuid: CBUUID) -> String {
    let text = uuid.uuidString.lowercased()
    switch text.count {
    case 4: return "0000\(text)-0000-1000-8000-00805f9b34fb"
    case 8: return "\(text)-0000-1000-8000-00805f9b34fb"
    default: return text
    }
  }

  /// Which characteristic an answer belongs to.
  static func key(_ service: String, _ characteristic: String) -> String {
    "\(service)|\(characteristic)"
  }

  /// The peripheral disconnected: every waiting caller and request on it fails.
  private func failOperations(_ identifier: UUID, with error: any Error) {
    for (token, caller) in readCallers where caller.identifier == identifier {
      readCallers[token] = nil
      caller.continuation.resume(throwing: error)
    }
    for (token, caller) in writeCallers where caller.identifier == identifier {
      writeCallers[token] = nil
      // A chunked write says which piece the disconnect stopped.
      if let index = pieceProgress[token] {
        caller.continuation.resume(throwing: PieceError(index: index, error: error as? CentralError ?? .notConnected))
      } else {
        caller.continuation.resume(throwing: error)
      }
    }
    for (token, caller) in descriptorCallers where caller.identifier == identifier {
      descriptorCallers[token] = nil
      caller.continuation.resume(throwing: error)
    }
    answerWaiters.removeValue(forKey: identifier)?.continuation.resume(returning: .failure(error))
    readyWaiters.removeValue(forKey: identifier)?.resume(throwing: error)
    for token in rssiRequests.removeValue(forKey: identifier) ?? [] {
      rssiCallers.removeValue(forKey: token)?.continuation.resume(throwing: error)
    }
  }

  private func handleOperation(_ event: CentralEvent) {
    switch event {
    case let .valueUpdated(identifier, key, value, error):
      // Notifications and read answers both arrive here, and Core Bluetooth doesn't say which. While a read of the
      // characteristic waits, the value is its answer; otherwise it's a notification. Android keeps them apart too.
      guard let waiter = answerWaiters[identifier], waiter.key == key else {
        if error == nil, let value {
          let notification = CentralNotification(identifier: identifier, key: key, value: value)
          notificationObservers.values.forEach { $0.yield(notification) }
        }
        return
      }
      answerWaiters[identifier] = nil
      waiter.continuation.resume(returning: error.map { .failure(CentralError.platform($0)) } ?? .success(value))
    case let .notificationStateUpdated(identifier, key, error):
      guard let waiter = answerWaiters[identifier], waiter.key == "notify|" + key else { return }
      answerWaiters[identifier] = nil
      waiter.continuation.resume(returning: error.map { .failure(CentralError.platform($0)) } ?? .success(nil))
    case let .wrote(identifier, key, error):
      guard let waiter = answerWaiters[identifier], waiter.key == key else { return }
      answerWaiters[identifier] = nil
      waiter.continuation.resume(returning: error.map { .failure(CentralError.platform($0)) } ?? .success(nil))
    case let .readyToWrite(identifier):
      readyWaiters.removeValue(forKey: identifier)?.resume()
    case let .descriptorsDiscovered(identifier, key, error), let .descriptorWrote(identifier, key, error):
      guard let waiter = answerWaiters[identifier], waiter.key == key else { return }
      answerWaiters[identifier] = nil
      waiter.continuation.resume(returning: error.map { .failure(CentralError.platform($0)) } ?? .success(nil))
    case let .descriptorUpdated(identifier, key, value, error):
      guard let waiter = answerWaiters[identifier], waiter.key == key else { return }
      answerWaiters[identifier] = nil
      waiter.continuation.resume(returning: error.map { .failure(CentralError.platform($0)) } ?? .success(value))
    case let .rssiRead(identifier, rssi, error):
      // A reading is the same for every caller waiting, so each answer goes to all of them; a lost answer then
      // never leaves a later caller waiting for an answer that went to someone else.
      let result: Result<Int, any Error> = error.map { .failure(CentralError.platform($0)) } ?? .success(rssi)
      for token in rssiRequests.removeValue(forKey: identifier) ?? [] {
        rssiCallers.removeValue(forKey: token)?.continuation.resume(with: result)
      }
    default:
      break
    }
  }

  // MARK: - Manager

  private func activate() {
    guard manager == nil else { return }
    let (events, continuation) = AsyncStream<CentralEvent>.makeStream()
    let delegate = CentralDelegate(events: continuation)
    self.delegate = delegate
    manager = CBCentralManager(
      delegate: delegate,
      queue: DispatchQueue(label: "com.devsilk.bonded.bluetooth"),
      options: [CBCentralManagerOptionShowPowerAlertKey: false]
    )
    Task { [weak self] in
      for await event in events {
        await self?.handle(event)
      }
    }
  }

  /// Creates the manager when it can no longer prompt, so the real power state becomes available.
  private func activateIfAuthorized() {
    if authorization == .allowedAlways {
      activate()
    }
  }

  private func handle(_ event: CentralEvent) {
    switch event {
    case let .discovered(discovery):
      discoveries?.yield(discovery)
    case .connected, .failedToConnect, .disconnected, .servicesDiscovered, .characteristicsDiscovered:
      handleConnection(event)
    case .valueUpdated, .wrote, .readyToWrite, .notificationStateUpdated, .rssiRead, .descriptorsDiscovered, .descriptorUpdated,
      .descriptorWrote:
      handleOperation(event)
    case let .stateChanged(newState):
      state = newState
      if newState != .poweredOn {
        // Core Bluetooth has stopped scanning and dropped every connection by itself.
        discoveries?.finish()
        discoveries = nil
        dropConnections()
      }
      hasReceivedState = true
      let waiters = stateWaiters
      stateWaiters.removeAll()
      for waiter in waiters {
        waiter.resume(returning: newState)
      }
      for observer in stateObservers.values {
        observer.yield(newState)
      }
      let currentAuthorization = authorization
      // Without Bluetooth Low Energy (the Simulator, for one) iOS may never prompt; stop waiting for an answer.
      if currentAuthorization != .notDetermined || newState == .unsupported {
        let authorizationWaiters = self.authorizationWaiters
        self.authorizationWaiters.removeAll()
        for waiter in authorizationWaiters {
          waiter.resume(returning: currentAuthorization)
        }
      }
      for observer in authorizationObservers.values {
        observer.yield(currentAuthorization)
      }
    }
  }

  // MARK: - Observers

  private func addStateObserver(_ id: UUID, _ continuation: AsyncStream<CBManagerState>.Continuation) {
    stateObservers[id] = continuation
    observeForeground()
  }

  private func removeStateObserver(_ id: UUID) {
    stateObservers[id] = nil
  }

  private func addAuthorizationObserver(_ id: UUID, _ continuation: AsyncStream<CBManagerAuthorization>.Continuation) {
    authorizationObservers[id] = continuation
    observeForeground()
  }

  private func removeAuthorizationObserver(_ id: UUID) {
    authorizationObservers[id] = nil
  }

  /// Checks again whenever the app becomes active: the user may have changed the permission in Settings.
  /// Core Bluetooth reports such changes itself only once the manager exists.
  private func observeForeground() {
    guard foregroundObserver == nil else { return }
    // UIApplication.didBecomeActiveNotification, by name, so this package doesn't import UIKit.
    foregroundObserver = NotificationCenter.default.addObserver(
      forName: Notification.Name("UIApplicationDidBecomeActiveNotification"),
      object: nil,
      queue: nil
    ) { [weak self] _ in
      Task { await self?.applicationDidBecomeActive() }
    }
  }

  private func applicationDidBecomeActive() {
    let currentAuthorization = authorization
    for observer in authorizationObservers.values {
      observer.yield(currentAuthorization)
    }
    guard manager == nil else { return }
    if currentAuthorization == .allowedAlways {
      activate() // The delegate reports the real state.
    } else {
      for observer in stateObservers.values {
        observer.yield(.unauthorized)
      }
    }
  }
}

/// A Core Bluetooth callback, as delivered on Bonded's queue.
enum CentralEvent: Sendable {
  case stateChanged(CBManagerState)
  case discovered(Discovery)
  case connected(UUID)
  case failedToConnect(UUID, PlatformErrorInfo)
  case disconnected(UUID, PlatformErrorInfo?)
  case servicesDiscovered(UUID, PlatformErrorInfo?)
  case characteristicsDiscovered(UUID, PlatformErrorInfo?)
  case valueUpdated(UUID, String, Data?, PlatformErrorInfo?)
  case wrote(UUID, String, PlatformErrorInfo?)
  case readyToWrite(UUID)
  case notificationStateUpdated(UUID, String, PlatformErrorInfo?)
  case rssiRead(UUID, Int, PlatformErrorInfo?)
  /// The key is `BluetoothCentral.descriptorsKey`.
  case descriptorsDiscovered(UUID, String, PlatformErrorInfo?)
  /// The key is `BluetoothCentral.descriptorKey`.
  case descriptorUpdated(UUID, String, Data?, PlatformErrorInfo?)
  case descriptorWrote(UUID, String, PlatformErrorInfo?)
}

/// A value a peripheral notified. `key` is `service|characteristic`, full lowercase UUIDs.
public struct CentralNotification: Sendable {
  public let identifier: UUID
  public let key: String
  public let value: Data
}

/// A Core Bluetooth error, copied into values that can cross actors.
public struct PlatformErrorInfo: Sendable, Equatable {
  public let domain: String
  public let code: Int
  public let message: String

  init(domain: String, code: Int, message: String) {
    self.domain = domain
    self.code = code
    self.message = message
  }

  init(_ error: any Error) {
    let error = error as NSError
    self.init(domain: error.domain, code: error.code, message: error.localizedDescription)
  }
}

/// Why the client couldn't do what it was asked.
public enum CentralError: Error, Sendable {
  case notReady
  case unknownPeripheral
  case notConnected
  /// The peripheral didn't answer within Bluetooth's 30 seconds.
  case noAnswer
  /// The descriptor isn't among the ones discovered on the connected peripheral.
  case notFound
  case platform(PlatformErrorInfo)
}

/// A piece of `write(pieces:)` failed: which one, counting from 0, and why; `error` is `nil` when it took longer than
/// the caller's limit.
public struct PieceError: Error, Sendable {
  public let index: Int
  public let error: CentralError?
}

/// A connected peripheral disconnected without the app asking.
public struct CentralDisconnect: Sendable {
  public let identifier: UUID
  /// Core Bluetooth's error; `nil` when Bluetooth went off.
  public let error: PlatformErrorInfo?
  public let isBluetoothOff: Bool
}

/// A discovered service and its characteristics, copied out of Core Bluetooth's objects.
public struct DiscoveredService: Sendable {
  public let uuid: String
  public let characteristics: [(uuid: String, properties: UInt)]

  init(_ service: CBService) {
    uuid = service.uuid.uuidString
    characteristics = (service.characteristics ?? []).map { ($0.uuid.uuidString, $0.properties.rawValue) }
  }
}

/// One advertisement Core Bluetooth reported, copied out of its dictionary into values that can cross actors.
public struct Discovery: Sendable {
  public let identifier: UUID
  public let peripheralName: String?
  public let localName: String?
  public let rssi: Int
  public let serviceUuids: [String]
  public let manufacturerData: Data?
  public let serviceData: [String: Data]
  public let txPowerLevel: Int?
  public let isConnectable: Bool?
  public let solicitedServiceUuids: [String]
  public let overflowServiceUuids: [String]
  public let receivedAt: Date

  init(peripheral: CBPeripheral, advertisementData: [String: Any], rssi: NSNumber) {
    identifier = peripheral.identifier
    peripheralName = peripheral.name
    localName = advertisementData[CBAdvertisementDataLocalNameKey] as? String
    self.rssi = rssi.intValue
    serviceUuids = (advertisementData[CBAdvertisementDataServiceUUIDsKey] as? [CBUUID] ?? []).map(\.uuidString)
    manufacturerData = advertisementData[CBAdvertisementDataManufacturerDataKey] as? Data
    serviceData = (advertisementData[CBAdvertisementDataServiceDataKey] as? [CBUUID: Data] ?? [:])
      .reduce(into: [:]) { result, entry in result[entry.key.uuidString] = entry.value }
    txPowerLevel = (advertisementData[CBAdvertisementDataTxPowerLevelKey] as? NSNumber)?.intValue
    isConnectable = (advertisementData[CBAdvertisementDataIsConnectable] as? NSNumber)?.boolValue
    solicitedServiceUuids = (advertisementData[CBAdvertisementDataSolicitedServiceUUIDsKey] as? [CBUUID] ?? []).map(\.uuidString)
    overflowServiceUuids = (advertisementData[CBAdvertisementDataOverflowServiceUUIDsKey] as? [CBUUID] ?? []).map(\.uuidString)
    receivedAt = Date()
  }
}

/// Receives Core Bluetooth's callbacks on Bonded's queue and forwards them, in order, to `BluetoothCentral`.
private final class CentralDelegate: NSObject, CBCentralManagerDelegate, CBPeripheralDelegate, Sendable {
  private let events: AsyncStream<CentralEvent>.Continuation

  init(events: AsyncStream<CentralEvent>.Continuation) {
    self.events = events
  }

  func centralManagerDidUpdateState(_ central: CBCentralManager) {
    events.yield(.stateChanged(central.state))
  }

  func centralManager(
    _ central: CBCentralManager,
    didDiscover peripheral: CBPeripheral,
    advertisementData: [String: Any],
    rssi RSSI: NSNumber
  ) {
    events.yield(.discovered(Discovery(peripheral: peripheral, advertisementData: advertisementData, rssi: RSSI)))
  }

  func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
    events.yield(.connected(peripheral.identifier))
  }

  func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: (any Error)?) {
    let info = error.map(PlatformErrorInfo.init) ?? PlatformErrorInfo(domain: "", code: 0, message: "Couldn't connect")
    events.yield(.failedToConnect(peripheral.identifier, info))
  }

  func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: (any Error)?) {
    events.yield(.disconnected(peripheral.identifier, error.map(PlatformErrorInfo.init)))
  }

  func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: (any Error)?) {
    events.yield(.servicesDiscovered(peripheral.identifier, error.map(PlatformErrorInfo.init)))
  }

  func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: (any Error)?) {
    events.yield(.characteristicsDiscovered(peripheral.identifier, error.map(PlatformErrorInfo.init)))
  }

  func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: (any Error)?) {
    events.yield(.valueUpdated(peripheral.identifier, Self.key(characteristic), characteristic.value, error.map(PlatformErrorInfo.init)))
  }

  func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: (any Error)?) {
    events.yield(.wrote(peripheral.identifier, Self.key(characteristic), error.map(PlatformErrorInfo.init)))
  }

  /// Which characteristic an answer belongs to, as `BluetoothCentral.key` writes it.
  static func key(_ characteristic: CBCharacteristic) -> String {
    let service = characteristic.service.map { BluetoothCentral.fullUuid($0.uuid) } ?? ""
    return BluetoothCentral.key(service, BluetoothCentral.fullUuid(characteristic.uuid))
  }

  func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: (any Error)?) {
    events.yield(.notificationStateUpdated(peripheral.identifier, Self.key(characteristic), error.map(PlatformErrorInfo.init)))
  }

  func peripheral(_ peripheral: CBPeripheral, didDiscoverDescriptorsFor characteristic: CBCharacteristic, error: (any Error)?) {
    let service = characteristic.service.map { BluetoothCentral.fullUuid($0.uuid) } ?? ""
    let key = BluetoothCentral.descriptorsKey(service, BluetoothCentral.fullUuid(characteristic.uuid))
    events.yield(.descriptorsDiscovered(peripheral.identifier, key, error.map(PlatformErrorInfo.init)))
  }

  func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor descriptor: CBDescriptor, error: (any Error)?) {
    events.yield(.descriptorUpdated(
      peripheral.identifier,
      Self.key(descriptor),
      Self.bytes(of: descriptor.value),
      error.map(PlatformErrorInfo.init)
    ))
  }

  func peripheral(_ peripheral: CBPeripheral, didWriteValueFor descriptor: CBDescriptor, error: (any Error)?) {
    events.yield(.descriptorWrote(peripheral.identifier, Self.key(descriptor), error.map(PlatformErrorInfo.init)))
  }

  /// Which descriptor an answer belongs to, as `BluetoothCentral.descriptorKey` writes it.
  static func key(_ descriptor: CBDescriptor) -> String {
    let characteristic = descriptor.characteristic
    let service = characteristic?.service.map { BluetoothCentral.fullUuid($0.uuid) } ?? ""
    let uuid = characteristic.map { BluetoothCentral.fullUuid($0.uuid) } ?? ""
    return BluetoothCentral.descriptorKey(service, uuid, BluetoothCentral.fullUuid(descriptor.uuid))
  }

  /// A descriptor's value as the device holds it. Core Bluetooth hands the standard descriptors over as numbers
  /// (2900, 2902, 2903: two little-endian bytes) or text (2901: UTF-8), the rest as bytes.
  static func bytes(of value: Any?) -> Data? {
    switch value {
    case let data as Data: data
    case let text as String: Data(text.utf8)
    case let number as NSNumber: withUnsafeBytes(of: number.uint16Value.littleEndian) { Data($0) }
    default: nil
    }
  }

  func peripheral(_ peripheral: CBPeripheral, didReadRSSI RSSI: NSNumber, error: (any Error)?) {
    events.yield(.rssiRead(peripheral.identifier, RSSI.intValue, error.map(PlatformErrorInfo.init)))
  }

  func peripheralIsReady(toSendWriteWithoutResponse peripheral: CBPeripheral) {
    events.yield(.readyToWrite(peripheral.identifier))
  }
}
