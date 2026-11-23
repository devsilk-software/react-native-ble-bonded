package com.margelo.nitro.blebonded.features.connection.domain

import com.margelo.nitro.blebonded.shared.domain.DeviceId

/**
 * A device the app connected to, kept across launches until the app forgets it, so it can connect again without
 * storing anything itself.
 */
data class RememberedDevice(
  val id: DeviceId,
  val name: String?,
  val identity: DeviceIdentity,
  /** Milliseconds since the Unix epoch. */
  val lastConnectedAt: Long,
)

/**
 * How a device was found, to find it again when its ID changes: a rotated address on Android, an identifier iOS
 * changed after a network reset.
 */
data class DeviceIdentity(val name: String? = null, val services: List<String> = emptyList(), val companyId: Int? = null) {
  /** This identity, with what [newer] knows filled in over it. */
  fun updated(newer: DeviceIdentity?): DeviceIdentity =
    if (newer == null) {
      this
    } else {
      DeviceIdentity(newer.name ?: name, newer.services.ifEmpty { services }, newer.companyId ?: companyId)
    }
}
