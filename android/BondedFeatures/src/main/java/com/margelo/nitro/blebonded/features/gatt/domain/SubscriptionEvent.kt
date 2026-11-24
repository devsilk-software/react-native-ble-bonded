package com.margelo.nitro.blebonded.features.gatt.domain

import com.margelo.nitro.blebonded.shared.domain.BleError

/** What a subscription reports. */
sealed interface SubscriptionEvent {
  class Value(val value: ByteArray) : SubscriptionEvent

  /** Switching notifications back on after a reconnect failed; the subscription stays and tries again. */
  data class Failed(val error: BleError) : SubscriptionEvent
}
