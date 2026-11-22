package com.margelo.nitro.blebonded.infrastructure.lifecycle

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Bundle
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Tells when one of the app's activities comes back to the foreground: the moment to check again what the
 * user may have changed in the system settings, such as a permission.
 */
class AppForeground(context: Context) {
  private val application = context.applicationContext as Application

  /** Whether the app's process is in the foreground right now, as the system ranks it. */
  fun isInForeground(): Boolean {
    val state = ActivityManager.RunningAppProcessInfo()
    ActivityManager.getMyMemoryState(state)
    return state.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
  }

  /** One value each time an activity resumes, from now on. */
  fun resumes(): Flow<Unit> =
    callbackFlow {
      val callbacks =
        object : Application.ActivityLifecycleCallbacks {
          override fun onActivityResumed(activity: Activity) {
            trySend(Unit)
          }

          override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

          override fun onActivityStarted(activity: Activity) = Unit

          override fun onActivityPaused(activity: Activity) = Unit

          override fun onActivityStopped(activity: Activity) = Unit

          override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

          override fun onActivityDestroyed(activity: Activity) = Unit
        }
      application.registerActivityLifecycleCallbacks(callbacks)
      awaitClose { application.unregisterActivityLifecycleCallbacks(callbacks) }
    }
}
