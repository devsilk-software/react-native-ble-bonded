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

  /** The device with this Bluetooth address, or null when the address isn't valid or there's no adapter. */
  fun remoteDevice(address: String): android.bluetooth.BluetoothDevice? =
    runCatching { manager?.adapter?.getRemoteDevice(address) }.getOrNull()

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

  /** The device's `BluetoothDevice.BOND_*` state; `BOND_NONE` for an address Android doesn't know. Needs `BLUETOOTH_CONNECT`. */
  @Suppress("MissingPermission")
  fun bondState(address: String): Int = remoteDevice(address)?.bondState ?: android.bluetooth.BluetoothDevice.BOND_NONE

  /** The device's bond state now, then every `BluetoothDevice.BOND_*` the system broadcasts for it. */
  fun bondStates(address: String): Flow<Int> =
    callbackFlow {
      val receiver =
        object : BroadcastReceiver() {
          override fun onReceive(context: Context, intent: Intent) {
            val changed: android.bluetooth.BluetoothDevice? =
              if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(android.bluetooth.BluetoothDevice.EXTRA_DEVICE, android.bluetooth.BluetoothDevice::class.java)
              } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(android.bluetooth.BluetoothDevice.EXTRA_DEVICE)
              }
            if (changed?.address == address) {
              trySend(intent.getIntExtra(android.bluetooth.BluetoothDevice.EXTRA_BOND_STATE, android.bluetooth.BluetoothDevice.BOND_NONE))
            }
          }
        }
      val filter = IntentFilter(android.bluetooth.BluetoothDevice.ACTION_BOND_STATE_CHANGED)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
      } else {
        context.registerReceiver(receiver, filter)
      }
      // Registered first, so no change between reading the state and listening is missed.
      trySend(bondState(address))
      awaitClose { context.unregisterReceiver(receiver) }
    }

  /** Asks Android to bond with the device, which shows the system's pairing dialog; false when Android refuses to start. */
  @Suppress("MissingPermission")
  fun createBond(address: String): Boolean = remoteDevice(address)?.createBond() ?: false

  /**
   * Asks Android to remove its bond with the device. A hidden API, called by reflection: throws when this Android
   * version doesn't have it, and returns false when Android refuses.
   */
  fun removeBond(address: String): Boolean {
    val device = remoteDevice(address) ?: return false
    return device.javaClass.getMethod("removeBond").invoke(device) as Boolean
  }
}

/** Android refused to scan; [errorCode] is a `ScanCallback.SCAN_FAILED_*` value. */
class ScanFailedException(val errorCode: Int) : Exception("Android refused to scan (error $errorCode)")
