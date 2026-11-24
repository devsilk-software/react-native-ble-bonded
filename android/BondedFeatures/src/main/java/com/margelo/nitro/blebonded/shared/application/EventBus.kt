package com.margelo.nitro.blebonded.shared.application

import com.margelo.nitro.blebonded.shared.domain.DomainEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * The in-process bus features publish domain events on (decision A2). The composition root creates one; a
 * feature that reacts collects [events]. Publishing with nobody collecting costs almost nothing.
 */
class EventBus {
  private val bus = MutableSharedFlow<DomainEvent>(extraBufferCapacity = 64)

  suspend fun publish(event: DomainEvent) = bus.emit(event)

  /** Every event published from now on. */
  fun events(): Flow<DomainEvent> = bus
}
