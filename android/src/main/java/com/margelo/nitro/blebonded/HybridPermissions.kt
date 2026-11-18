package com.margelo.nitro.blebonded

import com.margelo.nitro.blebonded.features.permissions.domain.PermissionStatus
import com.margelo.nitro.core.Callback
import com.margelo.nitro.core.Promise
import com.margelo.nitro.core.invoke

/**
 * The `Permissions` HybridObject: calls the `permissions` feature's use cases and maps their results to the
 * generated Nitro types. Nothing else lives here (decision A5).
 */
class HybridPermissions : HybridPermissionsSpec() {
  companion object : HybridPermissionsSpec.Companion {
    private val listeners = ListenerRegistry()

    override fun getStatus(): Promise<BluetoothPermission> = Promise.async { root().getPermissionStatus().toSpec() }

    override fun request(): Promise<BluetoothPermission> = Promise.async { root().requestPermission().toSpec() }

    override fun addListener(onChange: Callback<(status: BluetoothPermission) -> Unit>): Double =
      listeners.add(root().observePermission()) { status -> onChange(status.toSpec()) }

    override fun removeListener(id: Double) = listeners.remove(id)

    private fun PermissionStatus.toSpec(): BluetoothPermission =
      when (this) {
        PermissionStatus.UNAVAILABLE -> BluetoothPermission.UNAVAILABLE
        PermissionStatus.DENIED -> BluetoothPermission.DENIED
        PermissionStatus.GRANTED -> BluetoothPermission.GRANTED
        PermissionStatus.BLOCKED -> BluetoothPermission.BLOCKED
      }
  }
}
