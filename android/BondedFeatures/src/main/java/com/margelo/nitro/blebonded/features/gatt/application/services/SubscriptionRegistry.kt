package com.margelo.nitro.blebonded.features.gatt.application.services

import com.margelo.nitro.blebonded.features.gatt.application.interfaces.CharacteristicNotification
import com.margelo.nitro.blebonded.features.gatt.application.interfaces.CharacteristicSource
import com.margelo.nitro.blebonded.features.gatt.domain.CharacteristicAddress
import com.margelo.nitro.blebonded.features.gatt.domain.CharacteristicOperation
import com.margelo.nitro.blebonded.features.gatt.domain.OperationCheck
import com.margelo.nitro.blebonded.features.gatt.domain.SubscriptionEvent
import com.margelo.nitro.blebonded.shared.domain.BleError
import com.margelo.nitro.blebonded.shared.domain.CharacteristicProperty
import com.margelo.nitro.blebonded.shared.domain.DeviceId
import com.margelo.nitro.blebonded.shared.domain.DomainEvent
import com.margelo.nitro.blebonded.shared.domain.EventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The app's subscriptions: one per characteristic, kept until removed, across disconnects. Switches notifications
 * back on after each reconnect, which the `connection` feature announces on the event bus, and drops a device's
 * subscriptions when the app forgets it.
 */
class SubscriptionRegistry(private val source: CharacteristicSource, private val bus: EventBus, private val scope: CoroutineScope) {
  private val lock = Mutex()
  private var nextId = 1
  private val entries = HashMap<Int, SubscriptionEntry>()
  private var isListening = false

  private class SubscriptionEntry(val device: DeviceId, val address: CharacteristicAddress, val isIndication: Boolean) {
    val events = Channel<SubscriptionEvent>(Channel.UNLIMITED)
  }

  /** A running subscription: its ID, for removing it, and what it reports. */
  class Subscription(val id: Int, val events: Flow<SubscriptionEvent>)

  /**
   * Subscribes and returns once the device switched notifications on. Throws `alreadySubscribed` when the
   * characteristic has one, or the usual checks' errors.
   */
  suspend fun subscribe(address: CharacteristicAddress, id: DeviceId): Subscription {
    listen()
    if (lock.withLock { isSubscribed(address, id) }) throw alreadySubscribed(address, id)
    val properties = source.properties(address, id)
    OperationCheck.failure(CharacteristicOperation.SUBSCRIBE, address, id, properties)?.let { throw it }
    val isIndication = properties?.contains(CharacteristicProperty.NOTIFY) != true
    source.setNotifications(true, isIndication, address, id)
    return lock.withLock {
      // Another call may have subscribed while this one waited for the device.
      if (isSubscribed(address, id)) throw alreadySubscribed(address, id)
      val entry = SubscriptionEntry(id, address, isIndication)
      val subscription = nextId++
      entries[subscription] = entry
      Subscription(subscription, entry.events.receiveAsFlow())
    }
  }

  /** Removes the subscription and switches notifications off when the device is connected. */
  suspend fun remove(subscription: Int) {
    val entry = lock.withLock { entries.remove(subscription) } ?: return
    entry.events.close()
    runCatching { source.setNotifications(false, entry.isIndication, entry.address, entry.device) }
  }

  private fun isSubscribed(address: CharacteristicAddress, id: DeviceId) = entries.values.any { it.device == id && it.address == address }

  private suspend fun listen() {
    lock.withLock {
      if (isListening) return
      isListening = true
    }
    scope.launch(start = CoroutineStart.UNDISPATCHED) { source.notifications().collect { deliver(it) } }
    scope.launch(start = CoroutineStart.UNDISPATCHED) { bus.events().collect { handle(it) } }
  }

  private suspend fun deliver(notification: CharacteristicNotification) {
    lock.withLock {
      entries.values
        .filter { it.device == notification.id && it.address == notification.address }
        .forEach { it.events.trySend(SubscriptionEvent.Value(notification.value)) }
    }
  }

  private suspend fun handle(event: DomainEvent) {
    val device = event.deviceId ?: return
    when (event.kind) {
      DomainEvent.Kind.CONNECTED -> {
        val forDevice = lock.withLock { entries.values.filter { it.device == device } }
        for (entry in forDevice) {
          try {
            source.setNotifications(true, entry.isIndication, entry.address, device)
          } catch (failure: BleError) {
            entry.events.trySend(SubscriptionEvent.Failed(failure))
          }
        }
      }
      DomainEvent.Kind.FORGOTTEN ->
        lock.withLock {
          entries.entries.removeAll { (_, entry) ->
            (entry.device == device).also { if (it) entry.events.close() }
          }
        }
      else -> Unit
    }
  }

  private fun alreadySubscribed(address: CharacteristicAddress, id: DeviceId) =
    BleError(
      BleError.Code.ALREADY_SUBSCRIBED,
      "Characteristic ${address.characteristic} already has a subscription. Remove it first, or share its values in your app.",
      deviceId = id,
      operation = "subscribe",
    )
}
