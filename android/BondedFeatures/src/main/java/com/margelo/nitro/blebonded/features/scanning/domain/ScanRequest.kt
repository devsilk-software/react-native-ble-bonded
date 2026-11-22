package com.margelo.nitro.blebonded.features.scanning.domain

import com.margelo.nitro.blebonded.shared.domain.ServiceUuid

/** What a scan looks for and how it reports it. */
data class ScanRequest(
  /** Only devices advertising one of these services; empty for every device. */
  val services: List<ServiceUuid> = emptyList(),
  val name: NameFilter? = null,
  /** Report every advertisement, not just each device's first. */
  val allowDuplicates: Boolean = false,
  /** Include every advertisement field the platform reports, not just the common ones. */
  val fullAdvertisement: Boolean = false,
  /** Stop after this many milliseconds; null for never. */
  val timeoutMs: Long? = 30_000,
) {
  /** Scans without a service filter can't run in the background (decision R3). */
  val isFiltered: Boolean get() = services.isNotEmpty()
}

/** Which device names a scan reports. A pattern's parts must all match. */
sealed interface NameFilter {
  fun matches(name: String?): Boolean

  data class Exact(val name: String) : NameFilter {
    override fun matches(name: String?): Boolean = name == this.name
  }

  data class Pattern(val startsWith: String? = null, val endsWith: String? = null, val includes: String? = null) : NameFilter {
    override fun matches(name: String?): Boolean =
      name != null &&
        (startsWith == null || name.startsWith(startsWith)) &&
        (endsWith == null || name.endsWith(endsWith)) &&
        (includes == null || name.contains(includes))
  }
}
