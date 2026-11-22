import Foundation
import os
@testable import BondedFeatures

/// A `DiagnosticsStore` in memory, starting with whatever a previous launch left.
actor FakeDiagnosticsStore: DiagnosticsStore {
  private(set) var saved: [DiagnosticEvent]
  private(set) var deleteCount = 0

  init(saved: [DiagnosticEvent] = []) {
    self.saved = saved
  }

  func load() async -> [DiagnosticEvent] { saved }
  func save(_ events: [DiagnosticEvent]) async { saved = events }

  func delete() async {
    saved = []
    deleteCount += 1
  }
}

/// A `DiagnosticsEnvironment` with a clock that ticks by one millisecond per event and a fixed app state.
final class FakeDiagnosticsEnvironment: DiagnosticsEnvironment, Sendable {
  private let clock = Clock()
  let activity: DiagnosticEvent.AppActivity

  init(activity: DiagnosticEvent.AppActivity = .foreground) {
    self.activity = activity
  }

  func now() -> Int64 { clock.tick() }
  func appActivity() async -> DiagnosticEvent.AppActivity { activity }

  private final class Clock: Sendable {
    private let time = OSAllocatedUnfairLock(initialState: Int64(1000))
    func tick() -> Int64 { time.withLock { $0 += 1; return $0 } }
  }
}

/// Publishes events and waits until the recorder has recorded them.
func publish(_ events: [DomainEvent], on bus: EventBus, until recorder: DiagnosticsRecorder, count: Int) async {
  for event in events {
    await bus.publish(event)
  }
  for _ in 0..<200 where await recorder.events(since: nil).count < count {
    try? await Task.sleep(for: .milliseconds(5))
  }
}
