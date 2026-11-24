package com.margelo.nitro.blebonded.shared.domain

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
  ;

  /** The property's public name, as JavaScript and iOS write it (`writeWithoutResponse`). */
  val publicName: String
    get() = name.lowercase().split('_').mapIndexed { index, part -> if (index == 0) part else part.replaceFirstChar(Char::uppercase) }.joinToString("")
}
