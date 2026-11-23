package com.margelo.nitro.blebonded.shared.domain

/** A GATT characteristic UUID, always in its full 128-bit lowercase form, like [ServiceUuid]. */
@JvmInline
value class CharacteristicUuid private constructor(val rawValue: String) {
  override fun toString(): String = rawValue

  companion object {
    /** Accepts a 16-bit (`2A37`), 32-bit or 128-bit UUID; null for anything else. */
    fun parse(text: String): CharacteristicUuid? = ServiceUuid.parse(text)?.let { CharacteristicUuid(it.rawValue) }
  }
}
