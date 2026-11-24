package com.margelo.nitro.blebonded.features.gatt.application.useCases.unsubscribe

import com.margelo.nitro.blebonded.features.gatt.application.services.SubscriptionRegistry

/** Removes a subscription and switches the device's notifications off when it's connected. */
class Unsubscribe(private val registry: SubscriptionRegistry) {
  suspend operator fun invoke(subscription: Int) = registry.remove(subscription)
}
