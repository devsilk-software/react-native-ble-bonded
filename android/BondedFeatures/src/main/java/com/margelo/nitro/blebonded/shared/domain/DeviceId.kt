package com.margelo.nitro.blebonded.shared.domain

/** A peripheral's identity, as the platform reports it: the Bluetooth MAC address on Android. */
@JvmInline
value class DeviceId(val rawValue: String) {
  override fun toString(): String = rawValue
}
