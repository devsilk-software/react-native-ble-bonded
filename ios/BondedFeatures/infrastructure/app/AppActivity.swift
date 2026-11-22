import Foundation

/// Whether the app is in the background, from the system's lifecycle notifications. Starts watching on first
/// use and assumes the foreground until told otherwise.
public actor AppActivity {
  private var isBackground = false
  private var observers: [any NSObjectProtocol] = []

  public init() {}

  public func isInBackground() -> Bool {
    observe()
    return isBackground
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
    self.isBackground = isBackground
  }
}
