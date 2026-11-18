package com.margelo.nitro.blebonded.infrastructure.permissions

/**
 * The system permission dialog. Showing it needs the app's current activity, which only the Nitro module can
 * reach (through React Native), so the module implements this and passes it to the composition root.
 */
interface PermissionPrompt {
  /**
   * Android's hint that the user refused before and the dialog can still be shown: `Activity
   * .shouldShowRequestPermissionRationale`. False when there is no activity.
   */
  fun shouldShowRationale(permission: String): Boolean

  /** Shows the dialog for [permissions] and returns when the user has answered. */
  suspend fun request(permissions: List<String>)
}
