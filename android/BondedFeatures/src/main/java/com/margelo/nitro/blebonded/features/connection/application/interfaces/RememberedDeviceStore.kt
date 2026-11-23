package com.margelo.nitro.blebonded.features.connection.application.interfaces

import com.margelo.nitro.blebonded.features.connection.domain.RememberedDevice

/** Where remembered devices survive the app being killed. Implemented with the app's preferences, and by a fake in tests. */
interface RememberedDeviceStore {
  suspend fun load(): List<RememberedDevice>

  suspend fun save(devices: List<RememberedDevice>)
}
