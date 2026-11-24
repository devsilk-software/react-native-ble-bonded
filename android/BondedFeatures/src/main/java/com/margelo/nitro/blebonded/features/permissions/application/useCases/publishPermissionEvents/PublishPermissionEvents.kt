package com.margelo.nitro.blebonded.features.permissions.application.useCases.publishPermissionEvents

import com.margelo.nitro.blebonded.features.permissions.application.useCases.observePermission.ObservePermission
import com.margelo.nitro.blebonded.features.permissions.domain.publicName
import com.margelo.nitro.blebonded.shared.application.EventBus
import com.margelo.nitro.blebonded.shared.domain.DomainEvent

/** Publishes every permission change on the event bus, for whoever listens. Runs until cancelled. */
class PublishPermissionEvents(private val observe: ObservePermission, private val bus: EventBus) {
  suspend operator fun invoke() {
    observe().collect { status -> bus.publish(DomainEvent(DomainEvent.Kind.PERMISSION_CHANGED, detail = status.publicName)) }
  }
}
