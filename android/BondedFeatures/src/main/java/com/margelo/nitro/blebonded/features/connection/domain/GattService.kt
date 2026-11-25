package com.margelo.nitro.blebonded.features.connection.domain

import com.margelo.nitro.blebonded.shared.domain.CharacteristicProperty
import com.margelo.nitro.blebonded.shared.domain.CharacteristicUuid
import com.margelo.nitro.blebonded.shared.domain.DescriptorUuid
import com.margelo.nitro.blebonded.shared.domain.ServiceUuid

/** A service the device offers, with its characteristics. */
data class GattService(val uuid: ServiceUuid, val characteristics: List<GattCharacteristic>)

/** A characteristic; [descriptors] is null unless the app asked for them. */
data class GattCharacteristic(val uuid: CharacteristicUuid, val properties: List<CharacteristicProperty>, val descriptors: List<DescriptorUuid>? = null)
