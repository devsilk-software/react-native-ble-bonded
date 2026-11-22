package com.margelo.nitro.blebonded

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import com.facebook.react.bridge.BaseActivityEventListener
import com.facebook.react.bridge.ReactContext
import com.margelo.nitro.NitroModules
import com.margelo.nitro.blebonded.infrastructure.bluetooth.BluetoothEnablePrompt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * The system's "Turn on Bluetooth?" dialog, shown through React Native's activity. Lives in the Nitro module
 * because only this module may use React Native (docs/architecture.md); the features only see
 * [BluetoothEnablePrompt].
 */
object ReactBluetoothEnablePrompt : BluetoothEnablePrompt {
  private const val REQUEST_CODE = 0xB0EE

  override suspend fun requestEnable(): Boolean {
    val context = NitroModules.applicationContext as? ReactContext ?: return false
    val activity = context.currentActivity ?: return false
    return withContext(Dispatchers.Main) {
      suspendCancellableCoroutine { continuation ->
        val listener =
          object : BaseActivityEventListener() {
            override fun onActivityResult(activity: Activity, requestCode: Int, resultCode: Int, data: Intent?) {
              if (requestCode != REQUEST_CODE) return
              context.removeActivityEventListener(this)
              if (continuation.isActive) continuation.resume(resultCode == Activity.RESULT_OK)
            }
          }
        context.addActivityEventListener(listener)
        continuation.invokeOnCancellation { context.removeActivityEventListener(listener) }
        try {
          @Suppress("DEPRECATION") // Still the only way to ask; BluetoothAdapter.enable() is refused from Android 13.
          activity.startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQUEST_CODE)
        } catch (error: SecurityException) {
          context.removeActivityEventListener(listener)
          continuation.resume(false)
        }
      }
    }
  }
}
