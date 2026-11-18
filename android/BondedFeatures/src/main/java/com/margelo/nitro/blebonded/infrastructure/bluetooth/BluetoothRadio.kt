package com.margelo.nitro.blebonded.infrastructure.bluetooth

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

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

  /** Every `BluetoothAdapter.STATE_*` the system broadcasts from now on. */
  fun radioStateChanges(): Flow<Int> =
    callbackFlow {
      val receiver =
        object : BroadcastReceiver() {
          override fun onReceive(context: Context, intent: Intent) {
            trySend(intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR))
          }
        }
      val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
      } else {
        context.registerReceiver(receiver, filter)
      }
      awaitClose { context.unregisterReceiver(receiver) }
    }
}
