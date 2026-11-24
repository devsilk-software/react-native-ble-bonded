package com.margelo.nitro.blebonded.infrastructure.bluetooth

import java.util.concurrent.ConcurrentHashMap

/**
 * Every device's GATT client, by Bluetooth address: the shared Bluetooth client's platform objects (decision A2).
 * Connecting registers a client; reading and writing use it.
 */
class GattClients {
  private val clients = ConcurrentHashMap<String, GattConnection>()

  operator fun get(address: String): GattConnection? = clients[address]

  operator fun set(address: String, connection: GattConnection) {
    clients[address] = connection
  }

  fun remove(address: String): GattConnection? = clients.remove(address)

  /** Removes [connection] only if it's still the device's client, never a newer one. */
  fun remove(address: String, connection: GattConnection): Boolean = clients.remove(address, connection)
}
