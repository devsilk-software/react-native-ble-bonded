package com.margelo.nitro.blebonded.features.permissions.domain

/** A runtime permission Bluetooth needs on Android. */
enum class RuntimePermission {
  /** `BLUETOOTH_SCAN`, from Android 12. */
  SCAN,

  /** `BLUETOOTH_CONNECT`, from Android 12. */
  CONNECT,

  /** `ACCESS_FINE_LOCATION`, which scanning needs up to Android 11. */
  FINE_LOCATION,
}

/** What Android says about one permission, plus Bonded's record of whether it has asked. */
data class PermissionFacts(
  val granted: Boolean,
  /** `shouldShowRequestPermissionRationale`: refused before, and the dialog can still be shown. */
  val showRationale: Boolean,
  val requestedBefore: Boolean,
)

/** Android 12 (API 31) split Bluetooth into scan and connect permissions; before, scanning needed location. */
fun requiredPermissions(sdkInt: Int): List<RuntimePermission> =
  if (sdkInt >= ANDROID_12) {
    listOf(RuntimePermission.SCAN, RuntimePermission.CONNECT)
  } else {
    listOf(RuntimePermission.FINE_LOCATION)
  }

/**
 * The app's Bluetooth permission from Android's facts. Android gives no rationale hint both before the first
 * request and after "Don't ask again", so a permission counts as blocked only if Bonded has asked before.
 */
fun permissionStatus(hasLowEnergy: Boolean, facts: List<PermissionFacts>): PermissionStatus =
  when {
    !hasLowEnergy -> PermissionStatus.UNAVAILABLE
    facts.all { it.granted } -> PermissionStatus.GRANTED
    facts.any { !it.granted && !it.showRationale && it.requestedBefore } -> PermissionStatus.BLOCKED
    else -> PermissionStatus.DENIED
  }

private const val ANDROID_12 = 31
