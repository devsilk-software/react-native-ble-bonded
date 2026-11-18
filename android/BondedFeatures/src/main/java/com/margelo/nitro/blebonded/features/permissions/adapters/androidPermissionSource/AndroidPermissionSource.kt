package com.margelo.nitro.blebonded.features.permissions.adapters.androidPermissionSource

import android.Manifest
import android.os.Build
import com.margelo.nitro.blebonded.features.permissions.application.interfaces.PermissionSource
import com.margelo.nitro.blebonded.features.permissions.domain.PermissionFacts
import com.margelo.nitro.blebonded.features.permissions.domain.PermissionStatus
import com.margelo.nitro.blebonded.features.permissions.domain.RuntimePermission
import com.margelo.nitro.blebonded.features.permissions.domain.permissionStatus
import com.margelo.nitro.blebonded.features.permissions.domain.requiredPermissions
import com.margelo.nitro.blebonded.infrastructure.bluetooth.BluetoothRadio
import com.margelo.nitro.blebonded.infrastructure.lifecycle.AppForeground
import com.margelo.nitro.blebonded.infrastructure.permissions.PermissionPrompt
import com.margelo.nitro.blebonded.infrastructure.permissions.PermissionStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Answers [PermissionSource] with Android's runtime permissions and the system dialog. */
class AndroidPermissionSource(
  private val radio: BluetoothRadio,
  private val store: PermissionStore,
  private val prompt: PermissionPrompt,
  private val foreground: AppForeground,
  private val sdkInt: Int = Build.VERSION.SDK_INT,
) : PermissionSource {
  override suspend fun currentStatus(): PermissionStatus = readStatus()

  override suspend fun request(): PermissionStatus {
    val permissions = requiredPermissions(sdkInt).map(::manifestName)
    if (radio.hasLowEnergy() && !permissions.all(store::isGranted)) {
      store.markRequested(permissions)
      prompt.request(permissions)
    }
    return readStatus()
  }

  /** Android has no event for permissions changed in Settings, so the status is read again on each resume. */
  override fun statusChanges(): Flow<PermissionStatus> = foreground.resumes().map { readStatus() }

  private fun readStatus(): PermissionStatus =
    permissionStatus(
      hasLowEnergy = radio.hasLowEnergy(),
      facts =
        requiredPermissions(sdkInt).map(::manifestName).map { permission ->
          PermissionFacts(
            granted = store.isGranted(permission),
            showRationale = prompt.shouldShowRationale(permission),
            requestedBefore = store.wasRequested(permission),
          )
        },
    )

  private fun manifestName(permission: RuntimePermission): String =
    when (permission) {
      RuntimePermission.SCAN -> Manifest.permission.BLUETOOTH_SCAN
      RuntimePermission.CONNECT -> Manifest.permission.BLUETOOTH_CONNECT
      RuntimePermission.FINE_LOCATION -> Manifest.permission.ACCESS_FINE_LOCATION
    }
}
