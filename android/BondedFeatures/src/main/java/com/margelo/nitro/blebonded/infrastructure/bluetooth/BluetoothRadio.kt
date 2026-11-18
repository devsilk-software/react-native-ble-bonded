package com.margelo.nitro.blebonded.infrastructure.bluetooth

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * The phone's Bluetooth hardware, shared by every feature's adapters. It knows Android's Bluetooth APIs and
 * nothing about Bonded's features (decision A2).
 */
class BluetoothRadio(private val context: Context) {
  private val manager: BluetoothManager? = context.getSystemService(BluetoothManager::class.java)

  /** Whether the phone supports Bluetooth Low Energy at all. */
  fun hasLowEnergy(): Boolean = context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)

  /**
   * Whether the app may talk to Bluetooth devices. From Android 12 that needs the runtime permission
   * `BLUETOOTH_CONNECT`; before, the install-time `BLUETOOTH` permission is enough.
   */
  fun mayConnect(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
      context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

  /** The adapter's `BluetoothAdapter.STATE_*` value, or null when the phone has no Bluetooth adapter. */
  fun radioState(): Int? = manager?.adapter?.state
}
