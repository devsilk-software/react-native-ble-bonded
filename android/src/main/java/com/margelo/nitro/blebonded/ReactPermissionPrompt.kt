package com.margelo.nitro.blebonded

import com.facebook.react.modules.core.PermissionAwareActivity
import com.margelo.nitro.NitroModules
import com.margelo.nitro.blebonded.infrastructure.permissions.PermissionPrompt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * The system permission dialog, shown through React Native's activity. Lives in the Nitro module because only
 * this module may use React Native (docs/architecture.md); the features only see [PermissionPrompt].
 */
object ReactPermissionPrompt : PermissionPrompt {
  private val nextRequestCode = AtomicInteger(REQUEST_CODE_BASE)

  private val activity: PermissionAwareActivity?
    get() = NitroModules.applicationContext?.currentActivity as? PermissionAwareActivity

  override fun shouldShowRationale(permission: String): Boolean = activity?.shouldShowRequestPermissionRationale(permission) ?: false

  override suspend fun request(permissions: List<String>): Boolean {
    val activity = activity ?: return false
    withContext(Dispatchers.Main) {
      suspendCancellableCoroutine { continuation ->
        val requestCode = nextRequestCode.incrementAndGet()
        activity.requestPermissions(permissions.toTypedArray(), requestCode) { code, _, _ ->
          if (code == requestCode && continuation.isActive) continuation.resume(Unit)
          code == requestCode
        }
      }
    }
    return true
  }

  private const val REQUEST_CODE_BASE = 0xB0ED0
}
