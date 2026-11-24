package com.margelo.nitro.blebonded.features.gatt.application.useCases.subscribe

import com.margelo.nitro.blebonded.features.gatt.application.services.SubscriptionRegistry
import com.margelo.nitro.blebonded.features.gatt.domain.CharacteristicAddress
import com.margelo.nitro.blebonded.shared.domain.DeviceId

/** Subscribes to a characteristic's notifications; returns once the device switched them on. */
class Subscribe(private val registry: SubscriptionRegistry) {
  suspend operator fun invoke(address: CharacteristicAddress, id: DeviceId): SubscriptionRegistry.Subscription = registry.subscribe(address, id)
}
