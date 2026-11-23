package com.margelo.nitro.blebonded.features.connection.domain

import com.margelo.nitro.blebonded.shared.domain.CharacteristicUuid
import com.margelo.nitro.blebonded.shared.domain.ServiceUuid

/** A service the device offers, with its characteristics. */
data class GattService(val uuid: ServiceUuid, val characteristics: List<GattCharacteristic>)

data class GattCharacteristic(val uuid: CharacteristicUuid, val properties: List<CharacteristicProperty>)

/** What a characteristic allows. */
enum class CharacteristicProperty {
  BROADCAST,
  READ,
  WRITE_WITHOUT_RESPONSE,
  WRITE,
  NOTIFY,
  INDICATE,
  AUTHENTICATED_SIGNED_WRITES,
  EXTENDED_PROPERTIES,
}
