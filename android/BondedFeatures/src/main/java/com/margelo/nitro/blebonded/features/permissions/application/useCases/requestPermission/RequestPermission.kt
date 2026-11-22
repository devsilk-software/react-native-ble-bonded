package com.margelo.nitro.blebonded.features.permissions.application.useCases.requestPermission

import com.margelo.nitro.blebonded.features.permissions.application.interfaces.PermissionSource
import com.margelo.nitro.blebonded.features.permissions.domain.PermissionStatus

/** Asks for Bluetooth permission where the platform allows it, and returns the status the user chose. */
class RequestPermission(private val source: PermissionSource) {
  suspend operator fun invoke(): PermissionStatus = source.request()
}
