package com.margelo.nitro.blebonded

import com.margelo.nitro.NitroModules
import com.margelo.nitro.blebonded.compositionRoot.CompositionRoot

/** The composition root, created on first use with the application context and the system dialogs. */
internal fun root(): CompositionRoot {
  val context = NitroModules.applicationContext ?: error("Bonded used before React Native started")
  return CompositionRoot.get(context, ReactPermissionPrompt, ReactBluetoothEnablePrompt)
}
