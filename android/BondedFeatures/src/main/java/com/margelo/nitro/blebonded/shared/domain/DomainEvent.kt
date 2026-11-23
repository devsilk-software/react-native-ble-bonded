package com.margelo.nitro.blebonded.shared.domain

/**
 * Something that happened in one feature that another may react to (decision A2). Features publish these on the
 * [EventBus] without knowing who listens.
 */
data class DomainEvent(
  val kind: Kind,
  val deviceId: DeviceId? = null,
  /** The new value or other short detail, in Bonded's public words (`poweredOff`, `blocked`, `timeout`). */
  val detail: String? = null,
  val errorCode: BleError.Code? = null,
  val platformCode: Int? = null,
) {
  enum class Kind { STATE_CHANGED, PERMISSION_CHANGED, SCAN_STARTED, SCAN_STOPPED, CONNECTING, CONNECTED, DISCONNECTED, CONNECTION_RETRIED }
}
