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
    case let .stateChanged(newState):
      state = newState
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
}

/// Receives Core Bluetooth's callbacks on Bonded's queue and forwards them, in order, to `BluetoothCentral`.
private final class CentralDelegate: NSObject, CBCentralManagerDelegate, Sendable {
  private let events: AsyncStream<CentralEvent>.Continuation

  init(events: AsyncStream<CentralEvent>.Continuation) {
    self.events = events
  }

  func centralManagerDidUpdateState(_ central: CBCentralManager) {
    events.yield(.stateChanged(central.state))
  }
}
