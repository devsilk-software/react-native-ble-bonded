package com.margelo.nitro.blebonded

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import com.facebook.react.bridge.BaseActivityEventListener
import com.facebook.react.bridge.ReactContext
import com.margelo.nitro.NitroModules
import com.margelo.nitro.blebonded.infrastructure.bluetooth.BluetoothEnablePrompt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * The system's "Turn on Bluetooth?" dialog, shown through React Native's activity. Lives in the Nitro module
 * because only this module may use React Native (docs/architecture.md); the features only see
 * [BluetoothEnablePrompt].
 */
object ReactBluetoothEnablePrompt : BluetoothEnablePrompt {
  private const val REQUEST_CODE = 0xB0EE
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private val lock = Mutex()
  private var pending: Deferred<Boolean>? = null

  /**
   * Requests made while the dialog is showing share its answer: one dialog owns the request code at a time, so
   * an answer can never resolve a different dialog's request.
   */
  override suspend fun requestEnable(): Boolean {
    val request =
      lock.withLock {
        pending?.takeIf { it.isActive } ?: scope.async { showDialog() }.also { pending = it }
      }
    return request.await()
  }

  private suspend fun showDialog(): Boolean {
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
