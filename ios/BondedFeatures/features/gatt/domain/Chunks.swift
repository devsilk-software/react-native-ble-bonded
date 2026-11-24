/// Cutting data into chunks for a chunked write, and the error when one fails.
public enum Chunks {
  /// `data` in order, in chunks of at most `size` bytes.
  public static func split(_ data: [UInt8], size: Int) -> [[UInt8]] {
    let size = max(size, 1)
    return stride(from: 0, to: data.count, by: size).map { Array(data[$0..<min($0 + size, data.count)]) }
  }

  /// The error for the chunk at `index`, counting from 0, of `count` chunks of `size` bytes: the chunk's own error,
  /// with where the write stopped.
  public static func failure(_ error: BleError, index: Int, count: Int, size: Int, id: DeviceId) -> BleError {
    let sent = index * size
    return BleError(
      code: error.code,
      message: "Chunk \(index) of \(count) (counting from 0) failed after the device took \(sent) bytes: \(error.message)",
      platformCode: error.platformCode,
      deviceId: id,
      operation: error.operation,
      attError: error.attError,
      chunkIndex: index,
      chunkCount: count,
      sentLength: sent
    )
  }
}
