package com.margelo.nitro.blebonded.features.scanning.domain

/**
 * Android allows an app five scan starts in 30 seconds; a sixth is silently ignored, so the scan finds nothing.
 * The limiter says how long to wait so a start stays within the limit (decision R3).
 */
class ScanStartLimiter(private val maxStarts: Int = 5, private val windowMs: Long = 30_000) {
  private val starts = ArrayDeque<Long>()

  /** Milliseconds to wait before a start at [now]; 0 when it may start at once. */
  fun delayBeforeStart(now: Long): Long {
    while (starts.isNotEmpty() && starts.first() <= now - windowMs) starts.removeFirst()
    return if (starts.size < maxStarts) 0 else starts.first() + windowMs - now
  }

  fun recordStart(at: Long) {
    starts.addLast(at)
  }
}
