package com.margelo.nitro.blebonded.features.diagnostics.domain

/** The recorded events, oldest first, never more than [capacity]: the newest are kept. */
class EventLog(capacity: Int = DEFAULT_CAPACITY, events: List<DiagnosticEvent> = emptyList()) {
  var capacity: Int = maxOf(1, capacity)
    private set
  private val stored = ArrayDeque(events)

  init {
    trim()
  }

  val events: List<DiagnosticEvent> get() = stored.toList()

  fun append(event: DiagnosticEvent) {
    stored.addLast(event)
    trim()
  }

  /** A smaller capacity drops the oldest events; a larger one keeps them all. */
  fun setCapacity(capacity: Int) {
    this.capacity = maxOf(1, capacity)
    trim()
  }

  fun clear() = stored.clear()

  /** The events at or after [since] (milliseconds), or all of them. */
  fun events(since: Long?): List<DiagnosticEvent> = if (since == null) events else stored.filter { it.at >= since }

  private fun trim() {
    while (stored.size > capacity) stored.removeFirst()
  }

  companion object {
    const val DEFAULT_CAPACITY = 1000
  }
}
