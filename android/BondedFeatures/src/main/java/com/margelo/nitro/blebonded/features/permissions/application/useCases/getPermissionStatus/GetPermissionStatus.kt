package com.margelo.nitro.blebonded.features.permissions.application.useCases.getPermissionStatus

import com.margelo.nitro.blebonded.features.permissions.application.interfaces.PermissionSource
import com.margelo.nitro.blebonded.features.permissions.domain.PermissionStatus

/** Reads whether the app may use Bluetooth, without asking the user. */
class GetPermissionStatus(private val source: PermissionSource) {
  suspend operator fun invoke(): PermissionStatus = source.currentStatus()
}
