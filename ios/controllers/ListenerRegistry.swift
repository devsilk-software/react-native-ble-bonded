import os

/// The listeners JavaScript registered through a controller, by ID. Each listener is a task that forwards a
/// use case's stream; removing it cancels the task, which ends the stream and its native observation.
final class ListenerRegistry: Sendable {
  private struct Listeners {
    var nextId: Double = 1
    var tasks: [Double: Task<Void, Never>] = [:]
  }

  private let listeners = OSAllocatedUnfairLock(initialState: Listeners())

  /// Starts forwarding `stream` to `onValue` and returns the listener's ID.
  func add<Value: Sendable>(_ stream: AsyncStream<Value>, _ onValue: @escaping @Sendable (Value) -> Void) -> Double {
    let task = Task {
      for await value in stream {
        onValue(value)
      }
    }
    return listeners.withLock { listeners in
      let id = listeners.nextId
      listeners.nextId += 1
      listeners.tasks[id] = task
      return id
    }
  }

  func remove(_ id: Double) {
    let task = listeners.withLock { $0.tasks.removeValue(forKey: id) }
    task?.cancel()
  }
}
