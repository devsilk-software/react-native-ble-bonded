package com.margelo.nitro.blebonded.shared.domain

/**
 * A GATT service UUID, always in its full 128-bit lowercase form, so the same service compares equal however
 * the platform or the app wrote it (`180D`, `0000180d-0000-1000-8000-00805f9b34fb`).
 */
@JvmInline
value class ServiceUuid private constructor(val rawValue: String) {
  override fun toString(): String = rawValue

  companion object {
    private val hex = Regex("^[0-9a-f]+$")
    private val full = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

    /** Accepts a 16-bit (`180D`), 32-bit or 128-bit UUID; null for anything else. */
    fun parse(text: String): ServiceUuid? {
      val lower = text.lowercase()
      return when {
        lower.length == 4 && hex.matches(lower) -> ServiceUuid("0000$lower-0000-1000-8000-00805f9b34fb")
        lower.length == 8 && hex.matches(lower) -> ServiceUuid("$lower-0000-1000-8000-00805f9b34fb")
        full.matches(lower) -> ServiceUuid(lower)
        else -> null
      }
    }
  }
}
