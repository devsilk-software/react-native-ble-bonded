/// The recorded events, oldest first, never more than `capacity`: the newest are kept.
public struct EventLog: Sendable, Equatable {
  public static let defaultCapacity = 1000

  public private(set) var capacity: Int
  public private(set) var events: [DiagnosticEvent]

  public init(capacity: Int = EventLog.defaultCapacity, events: [DiagnosticEvent] = []) {
    self.capacity = max(1, capacity)
    self.events = events
    trim()
  }

  public mutating func append(_ event: DiagnosticEvent) {
    events.append(event)
    trim()
  }

  /// A smaller capacity drops the oldest events; a larger one keeps them all.
  public mutating func setCapacity(_ capacity: Int) {
    self.capacity = max(1, capacity)
    trim()
  }

  public mutating func removeAll() {
    events.removeAll()
  }

  /// The events at or after `since` (milliseconds), or all of them.
  public func events(since: Int64?) -> [DiagnosticEvent] {
    guard let since else { return events }
    return events.filter { $0.at >= since }
  }

  private mutating func trim() {
    if events.count > capacity {
      events.removeFirst(events.count - capacity)
    }
  }
}
