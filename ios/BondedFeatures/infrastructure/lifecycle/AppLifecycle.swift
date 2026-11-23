import Foundation

/// Whether the app is in the background, from the system's lifecycle notifications. Starts watching on first
/// use and assumes the foreground until told otherwise.
public actor AppLifecycle {
  private var isBackground = false
  private var observers: [any NSObjectProtocol] = []
  private var changeObservers: [UUID: AsyncStream<Bool>.Continuation] = [:]

  public init() {}

  public func isInBackground() -> Bool {
    observe()
    return isBackground
  }

  /// `true` each time the app goes to the background, `false` each time it returns, from the moment this
  /// returns.
  public func backgroundChanges() -> AsyncStream<Bool> {
    observe()
    let (stream, continuation) = AsyncStream<Bool>.makeStream()
    let id = UUID()
    continuation.onTermination = { _ in
      Task { await self.removeChangeObserver(id) }
    }
    changeObservers[id] = continuation
    return stream
  }

  private func removeChangeObserver(_ id: UUID) {
    changeObservers[id] = nil
  }

  private func observe() {
    guard observers.isEmpty else { return }
    // UIApplication's notifications, by name, so this package doesn't import UIKit.
    let center = NotificationCenter.default
    observers = [
      center.addObserver(forName: Notification.Name("UIApplicationDidEnterBackgroundNotification"), object: nil, queue: nil) {
        [weak self] _ in
        Task { await self?.setBackground(true) }
      },
      center.addObserver(forName: Notification.Name("UIApplicationWillEnterForegroundNotification"), object: nil, queue: nil) {
        [weak self] _ in
        Task { await self?.setBackground(false) }
      },
    ]
  }

  private func setBackground(_ isBackground: Bool) {
    guard isBackground != self.isBackground else { return }
    self.isBackground = isBackground
    changeObservers.values.forEach { $0.yield(isBackground) }
  }
}
