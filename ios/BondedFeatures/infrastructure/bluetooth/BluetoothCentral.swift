import CoreBluetooth
import Dispatch

/// The one `CBCentralManager` Bonded uses, shared by every feature's adapters. It knows Core Bluetooth and
/// nothing about Bonded's features (decision A2).
///
/// Core Bluetooth calls its delegate on Bonded's own serial queue, never the main thread. The delegate passes
/// each callback, in order, into an `AsyncStream` that this actor consumes, so all state lives in the actor.
/// An actor running directly on that queue would need `DispatchSerialQueue`, which requires iOS 17; Bonded
/// supports iOS 16.
public actor BluetoothCentral {
  private let manager: CBCentralManager
  private let delegate: CentralDelegate
  private var state: CBManagerState = .unknown
  private var hasReceivedState = false
  private var stateWaiters: [CheckedContinuation<CBManagerState, Never>] = []

  public init() {
    let (events, continuation) = AsyncStream<CentralEvent>.makeStream()
    let delegate = CentralDelegate(events: continuation)
    self.delegate = delegate
    // Creating the manager asks for Bluetooth permission if the user hasn't decided yet.
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

  /// The manager's state. Before Core Bluetooth's first report, waits for it.
  public func managerState() async -> CBManagerState {
    if hasReceivedState {
      return state
    }
    return await withCheckedContinuation { continuation in
      stateWaiters.append(continuation)
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
