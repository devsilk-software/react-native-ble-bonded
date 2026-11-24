package com.margelo.nitro.blebonded.features.bluetoothState.application.useCases.publishAdapterStateEvents

import com.margelo.nitro.blebonded.features.bluetoothState.application.useCases.observeAdapterState.ObserveAdapterState
import com.margelo.nitro.blebonded.features.bluetoothState.domain.publicName
import com.margelo.nitro.blebonded.shared.application.EventBus
import com.margelo.nitro.blebonded.shared.domain.DomainEvent

/** Publishes every Bluetooth state change on the event bus, for whoever listens. Runs until cancelled. */
class PublishAdapterStateEvents(private val observe: ObserveAdapterState, private val bus: EventBus) {
  suspend operator fun invoke() {
    observe().collect { state -> bus.publish(DomainEvent(DomainEvent.Kind.STATE_CHANGED, detail = state.publicName)) }
  }
}
