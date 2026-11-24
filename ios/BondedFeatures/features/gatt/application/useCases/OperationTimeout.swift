/// Runs `operation`, giving up with `timeout` after `limit`.
func withOperationTimeout<Result: Sendable>(
  _ limit: Duration,
  timeout: BleError,
  _ operation: @escaping @Sendable () async throws -> Result
) async throws -> Result {
  try await withThrowingTaskGroup(of: Result.self) { group in
    group.addTask { try await operation() }
    group.addTask {
      try await Task.sleep(for: limit)
      throw timeout
    }
    defer { group.cancelAll() }
    guard let result = try await group.next() else { throw CancellationError() }
    return result
  }
}

extension Duration {
  var wholeMilliseconds: Int64 {
    components.seconds * 1000 + components.attoseconds / 1_000_000_000_000_000
  }
}
