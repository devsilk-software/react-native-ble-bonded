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
  /// The last operation on each peripheral; each new one waits for it, so callbacks reach the right caller.
  private var operations: [UUID: Task<Void, Never>] = [:]
  private var valueWaiters: [UUID: CheckedContinuation<Data, any Error>] = [:]
  private var writeWaiters: [UUID: CheckedContinuation<Void, any Error>] = [:]
  private var readyWaiters: [UUID: CheckedContinuation<Void, any Error>] = [:]

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

  /// Reads the characteristic's value. Cancelling stops waiting for the answer.
  public func read(_ identifier: UUID, service: String, characteristic: String) async throws -> Data {
    try await inOrder(on: identifier) {
      try await self.performRead(identifier, service: service, characteristic: characteristic)
    }
  }

  /// Writes `data`. With response, returns once the peripheral confirmed; without, once Core Bluetooth took it,
  /// waiting for room when its buffer is full.
  public func write(_ data: Data, to identifier: UUID, service: String, characteristic: String, withResponse: Bool) async throws {
    try await inOrder(on: identifier) {
      try await self.performWrite(data, to: identifier, service: service, characteristic: characteristic, withResponse: withResponse)
    }
  }

  private func performRead(_ identifier: UUID, service: String, characteristic: String) async throws -> Data {
    let peripheral = try connectedPeripheral(identifier)
    guard let target = try self.characteristic(on: identifier, service: service, characteristic: characteristic) else {
      throw CentralError.notConnected
    }
    return try await withTaskCancellationHandler {
      try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Data, any Error>) in
        valueWaiters[identifier] = continuation
        peripheral.readValue(for: target)
      }
    } onCancel: {
      Task { await self.cancelOperation(identifier) }
    }
  }

  private func performWrite(_ data: Data, to identifier: UUID, service: String, characteristic: String, withResponse: Bool) async throws {
    let peripheral = try connectedPeripheral(identifier)
    guard let target = try self.characteristic(on: identifier, service: service, characteristic: characteristic) else {
      throw CentralError.notConnected
    }
    try await withTaskCancellationHandler {
      if withResponse {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, any Error>) in
          writeWaiters[identifier] = continuation
          peripheral.writeValue(data, for: target, type: .withResponse)
        }
        return
      }
      while !peripheral.canSendWriteWithoutResponse {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, any Error>) in
          readyWaiters[identifier] = continuation
        }
      }
      peripheral.writeValue(data, for: target, type: .withoutResponse)
    } onCancel: {
      Task { await self.cancelOperation(identifier) }
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

  private func inOrder<Result: Sendable>(on identifier: UUID, _ operation: @escaping @Sendable () async throws -> Result) async throws -> Result {
    let previous = operations[identifier]
    let task = Task {
      await previous?.value
      return try await operation()
    }
    operations[identifier] = Task { _ = try? await task.value }
    return try await withTaskCancellationHandler {
      try await task.value
    } onCancel: {
      task.cancel()
    }
  }

  private func cancelOperation(_ identifier: UUID) {
    failOperations(identifier, with: CancellationError())
  }

  private func failOperations(_ identifier: UUID, with error: any Error) {
    valueWaiters.removeValue(forKey: identifier)?.resume(throwing: error)
    writeWaiters.removeValue(forKey: identifier)?.resume(throwing: error)
    readyWaiters.removeValue(forKey: identifier)?.resume(throwing: error)
  }

  private func handleOperation(_ event: CentralEvent) {
    switch event {
    case let .valueUpdated(identifier, value, error):
      guard let waiter = valueWaiters.removeValue(forKey: identifier) else { return }
      if let error { waiter.resume(throwing: CentralError.platform(error)) } else { waiter.resume(returning: value ?? Data()) }
    case let .wrote(identifier, error):
      guard let waiter = writeWaiters.removeValue(forKey: identifier) else { return }
      if let error { waiter.resume(throwing: CentralError.platform(error)) } else { waiter.resume() }
    case let .readyToWrite(identifier):
      readyWaiters.removeValue(forKey: identifier)?.resume()
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
    case .valueUpdated, .wrote, .readyToWrite:
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
  case valueUpdated(UUID, Data?, PlatformErrorInfo?)
  case wrote(UUID, PlatformErrorInfo?)
  case readyToWrite(UUID)
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
  case platform(PlatformErrorInfo)
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
    events.yield(.valueUpdated(peripheral.identifier, characteristic.value, error.map(PlatformErrorInfo.init)))
  }

  func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: (any Error)?) {
    events.yield(.wrote(peripheral.identifier, error.map(PlatformErrorInfo.init)))
  }

  func peripheralIsReady(toSendWriteWithoutResponse peripheral: CBPeripheral) {
    events.yield(.readyToWrite(peripheral.identifier))
  }
}
