package com.margelo.nitro.blebonded.infrastructure.bluetooth

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

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

  /**
   * Whether the app may scan. From Android 12 that needs `BLUETOOTH_SCAN`; before, `ACCESS_FINE_LOCATION`.
   */
  fun mayScan(): Boolean =
    context.checkSelfPermission(
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_SCAN else Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED

  /**
   * Scans while collected. Completes when Bluetooth leaves the on state, which silently ends Android's scan,
   * and fails with [ScanFailedException] when Android refuses to scan, or `SecurityException` without the
   * permission.
   */
  fun scan(filters: List<ScanFilter>, settings: ScanSettings): Flow<ScanResult> =
    callbackFlow {
      val scanner = manager?.adapter?.bluetoothLeScanner
      if (scanner == null || radioState() != BluetoothAdapter.STATE_ON) {
        close()
        awaitClose()
        return@callbackFlow
      }
      val callback =
        object : ScanCallback() {
          override fun onScanResult(callbackType: Int, result: ScanResult) {
            trySend(result)
          }

          override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { trySend(it) }
          }

          override fun onScanFailed(errorCode: Int) {
            close(ScanFailedException(errorCode))
          }
        }
      launch {
        radioStateChanges().collect { if (it != BluetoothAdapter.STATE_ON) close() }
      }
      scanner.startScan(filters, settings, callback)
      awaitClose {
        // Stopping needs Bluetooth on; once it's off, Android has already stopped the scan.
        if (radioState() == BluetoothAdapter.STATE_ON) runCatching { scanner.stopScan(callback) }
      }
    }

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

/** Android refused to scan; [errorCode] is a `ScanCallback.SCAN_FAILED_*` value. */
class ScanFailedException(val errorCode: Int) : Exception("Android refused to scan (error $errorCode)")
