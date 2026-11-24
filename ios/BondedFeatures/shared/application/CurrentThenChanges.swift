/// The observation rule every Bonded listener follows: the current value at once, then each change, with
/// repeats suppressed. Subscribes to `changes` before reading `current`, so no change in between is lost.
public func currentThenChanges<Value: Equatable & Sendable>(
  current: @escaping @Sendable () async -> Value,
  changes: @escaping @Sendable () -> AsyncStream<Value>
) -> AsyncStream<Value> {
  let (stream, continuation) = AsyncStream<Value>.makeStream()
  let task = Task {
    let updates = changes()
    var last = await current()
    continuation.yield(last)
    for await value in updates where value != last {
      last = value
      continuation.yield(value)
    }
    continuation.finish()
  }
  continuation.onTermination = { _ in task.cancel() }
  return stream
}
