package com.margelo.nitro.blebonded.infrastructure.permissions

import android.content.Context
import android.content.pm.PackageManager

/**
 * Whether the app holds a permission, and whether Bonded has ever asked for it. Android can't tell "never
 * asked" from "don't ask again" by itself, so the record of past requests lives in the app's private storage.
 */
class PermissionStore(context: Context) {
  private val context = context.applicationContext
  private val requests = context.getSharedPreferences("com.devsilk.bonded.permissions", Context.MODE_PRIVATE)

  fun isGranted(permission: String): Boolean =
    context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

  fun wasRequested(permission: String): Boolean = requests.getBoolean(permission, false)

  fun markRequested(permissions: List<String>) {
    val editor = requests.edit()
    permissions.forEach { editor.putBoolean(it, true) }
    editor.apply()
  }
}
