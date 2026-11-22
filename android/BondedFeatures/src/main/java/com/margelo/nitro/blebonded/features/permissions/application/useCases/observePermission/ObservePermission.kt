package com.margelo.nitro.blebonded.features.permissions.application.useCases.observePermission

import com.margelo.nitro.blebonded.features.permissions.application.interfaces.PermissionSource
import com.margelo.nitro.blebonded.features.permissions.domain.PermissionStatus
import com.margelo.nitro.blebonded.shared.domain.currentThenChanges
import kotlinx.coroutines.flow.Flow

/** Follows the app's Bluetooth permission: the current status at once, then each change, repeats suppressed. */
class ObservePermission(private val source: PermissionSource) {
  operator fun invoke(): Flow<PermissionStatus> = currentThenChanges({ source.currentStatus() }, source.statusChanges())
}
