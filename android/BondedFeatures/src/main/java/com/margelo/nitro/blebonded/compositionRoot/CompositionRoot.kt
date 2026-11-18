package com.margelo.nitro.blebonded.compositionRoot

import android.content.Context
import com.margelo.nitro.blebonded.features.bluetoothState.adapters.androidAdapterStateSource.AndroidAdapterStateSource
import com.margelo.nitro.blebonded.features.bluetoothState.application.useCases.getAdapterState.GetAdapterState
import com.margelo.nitro.blebonded.features.bluetoothState.application.useCases.observeAdapterState.ObserveAdapterState
import com.margelo.nitro.blebonded.features.permissions.adapters.androidPermissionSource.AndroidPermissionSource
import com.margelo.nitro.blebonded.features.permissions.application.useCases.getPermissionStatus.GetPermissionStatus
import com.margelo.nitro.blebonded.features.permissions.application.useCases.observePermission.ObservePermission
import com.margelo.nitro.blebonded.features.permissions.application.useCases.requestPermission.RequestPermission
import com.margelo.nitro.blebonded.infrastructure.bluetooth.BluetoothRadio
import com.margelo.nitro.blebonded.infrastructure.lifecycle.AppForeground
import com.margelo.nitro.blebonded.infrastructure.permissions.PermissionPrompt
import com.margelo.nitro.blebonded.infrastructure.permissions.PermissionStore

/**
 * Creates the shared Bluetooth client and the adapters, and builds each use case with them. The Nitro
 * controllers get their use cases from here, passing the application context and the permission dialog,
 * which only the Nitro module can show, on first use.
 */
class CompositionRoot private constructor(context: Context, prompt: PermissionPrompt) {
  private val radio = BluetoothRadio(context.applicationContext)
  private val foreground = AppForeground(context)

  private val adapterStateSource = AndroidAdapterStateSource(radio, foreground)
  val getAdapterState = GetAdapterState(adapterStateSource)
  val observeAdapterState = ObserveAdapterState(adapterStateSource)

  private val permissionSource = AndroidPermissionSource(radio, PermissionStore(context), prompt, foreground)
  val getPermissionStatus = GetPermissionStatus(permissionSource)
  val requestPermission = RequestPermission(permissionSource)
  val observePermission = ObservePermission(permissionSource)

  companion object {
    @Volatile private var instance: CompositionRoot? = null

    fun get(context: Context, prompt: PermissionPrompt): CompositionRoot =
      instance ?: synchronized(this) {
        instance ?: CompositionRoot(context, prompt).also { instance = it }
      }
  }
}
