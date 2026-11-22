package com.margelo.nitro.blebonded.features.permissions.domain

/** Whether the app may use Bluetooth, with the meanings react-native-permissions gives its statuses. */
enum class PermissionStatus {
  /** The phone has no Bluetooth Low Energy. */
  UNAVAILABLE,

  /** Not granted, but the app can ask (including when it never has). */
  DENIED,
  GRANTED,

  /** Not granted, and only the system settings can change that. */
  BLOCKED,
}
