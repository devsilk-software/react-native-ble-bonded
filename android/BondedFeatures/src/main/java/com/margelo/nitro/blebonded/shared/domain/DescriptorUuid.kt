package com.margelo.nitro.blebonded.shared.domain

/** A GATT descriptor UUID, always in its full 128-bit lowercase form, like [ServiceUuid]. */
@JvmInline
value class DescriptorUuid private constructor(val rawValue: String) {
  override fun toString(): String = rawValue

  companion object {
    /** Accepts a 16-bit (`2901`), 32-bit or 128-bit UUID; null for anything else. */
    fun parse(text: String): DescriptorUuid? = ServiceUuid.parse(text)?.let { DescriptorUuid(it.rawValue) }

    /** The Client Characteristic Configuration descriptor, which switches a characteristic's notifications. */
    val CLIENT_CONFIGURATION: DescriptorUuid = parse("2902")!!
  }
}
