package com.margelo.nitro.blebonded.features.gatt.domain

import com.margelo.nitro.blebonded.shared.domain.BleError
import com.margelo.nitro.blebonded.shared.domain.DeviceId

/** Cutting data into chunks for a chunked write, and the error when one fails. */
object Chunks {
  /** [data] in order, in chunks of at most [size] bytes. */
  fun split(data: ByteArray, size: Int): List<ByteArray> {
    val step = size.coerceAtLeast(1)
    return (data.indices step step).map { data.copyOfRange(it, minOf(it + step, data.size)) }
  }

  /**
   * The error for the chunk at [index], counting from 0, of [count] chunks of [size] bytes: the chunk's own error, with
   * where the write stopped.
   */
  fun failure(error: BleError, index: Int, count: Int, size: Int, id: DeviceId): BleError {
    val sent = index * size
    return BleError(
      error.code,
      "Chunk $index of $count (counting from 0) failed after $sent bytes were sent: ${error.message}",
      error.platformCode,
      id,
      error.operation,
      attError = error.attError,
      chunkIndex = index,
      chunkCount = count,
      sentLength = sent,
    )
  }
}
