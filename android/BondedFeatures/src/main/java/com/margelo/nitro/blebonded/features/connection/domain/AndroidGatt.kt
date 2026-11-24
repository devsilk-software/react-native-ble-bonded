package com.margelo.nitro.blebonded.features.connection.domain

/** One step of getting a connected device ready, in order. */
sealed interface SetupStep {
  data object WaitForBonding : SetupStep

  data class Pause(val ms: Long) : SetupStep

  data object HighPriority : SetupStep

  data class RequestMtu(val size: Int) : SetupStep

  data object RefreshServiceCache : SetupStep

  data object DiscoverServices : SetupStep

  data object BalancedPriority : SetupStep
}

/** The steps from connected to ready, from the workarounds and whether the device is bonded or bonding. */
fun setupSteps(options: AndroidConnectionOptions, isBonded: Boolean, isBonding: Boolean): List<SetupStep> =
  buildList {
    if (options.highPriorityDuringSetup) add(SetupStep.HighPriority)
    if (options.mtu > DEFAULT_MTU) add(SetupStep.RequestMtu(options.mtu))
    if (options.waitForBonding && isBonding) add(SetupStep.WaitForBonding)
    if ((isBonded || isBonding) && options.discoveryDelayMs > 0) add(SetupStep.Pause(options.discoveryDelayMs))
    if (options.refreshServiceCache == ServiceCacheRefresh.ALWAYS) add(SetupStep.RefreshServiceCache)
    add(SetupStep.DiscoverServices)
    if (options.highPriorityDuringSetup) add(SetupStep.BalancedPriority)
  }

/** Bluetooth's MTU before any is requested. */
const val DEFAULT_MTU = 23

/**
 * GATT statuses that pass on their own: 133 (`GATT_ERROR`, Android's catch-all), 62 (connection failed to be
 * established), 147 (connection timeout), 257 (`GATT_FAILURE`), and 8 (supervision timeout while connecting).
 */
fun isTransientStatus(status: Int): Boolean = status in setOf(133, 62, 147, 257, 8)

/** Why a connected device disconnected, from Android's GATT status. */
fun disconnectReason(status: Int, isBluetoothOff: Boolean): DisconnectReason =
  when {
    isBluetoothOff -> DisconnectReason.BLUETOOTH_OFF
    status == 8 -> DisconnectReason.OUT_OF_RANGE
    status == 19 -> DisconnectReason.DEVICE_DISCONNECTED
    else -> DisconnectReason.FAILED
  }
