package com.margelo.nitro.blebonded.compositionRoot

import android.content.Context
import com.margelo.nitro.blebonded.features.bluetoothState.adapters.androidAdapterStateSource.AndroidAdapterStateSource
import com.margelo.nitro.blebonded.features.bluetoothState.application.useCases.getAdapterState.GetAdapterState
import com.margelo.nitro.blebonded.infrastructure.bluetooth.BluetoothRadio

/**
 * Creates the shared Bluetooth client and the adapters, and builds each use case with them. The Nitro
 * controllers get their use cases from here, passing the application context on first use.
 */
class CompositionRoot private constructor(context: Context) {
  private val radio = BluetoothRadio(context.applicationContext)

  val getAdapterState = GetAdapterState(AndroidAdapterStateSource(radio))

  companion object {
    @Volatile private var instance: CompositionRoot? = null

    fun get(context: Context): CompositionRoot =
      instance ?: synchronized(this) {
        instance ?: CompositionRoot(context).also { instance = it }
      }
  }
}
