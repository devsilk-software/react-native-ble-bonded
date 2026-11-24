package com.margelo.nitro.blebonded.infrastructure.bluetooth

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Every device's GATT client, by Bluetooth address: the shared Bluetooth client's platform objects (decision A2).
 * Connecting registers a client; reading and writing use it.
 */
class GattClients {
  private val clients = ConcurrentHashMap<String, GattConnection>()
  private val notified = MutableSharedFlow<ClientNotification>(extraBufferCapacity = 1024)

  /** Every value any device notifies. */
  val notifications: SharedFlow<ClientNotification> get() = notified

  /** Called by a client for each value its device notifies. */
  fun notify(notification: ClientNotification) {
    notified.tryEmit(notification)
  }

  operator fun get(address: String): GattConnection? = clients[address]

  operator fun set(address: String, connection: GattConnection) {
    clients[address] = connection
  }

  fun remove(address: String): GattConnection? = clients.remove(address)

  /** Removes [connection] only if it's still the device's client, never a newer one. */
  fun remove(address: String, connection: GattConnection): Boolean = clients.remove(address, connection)
}

/** A value a device notified; [service] and [characteristic] are full lowercase UUIDs. */
class ClientNotification(val address: String, val service: String, val characteristic: String, val value: ByteArray)
