package com.margelo.nitro.blebonded.features.connection.domain

import com.margelo.nitro.blebonded.shared.domain.BleError
import com.margelo.nitro.blebonded.shared.domain.DeviceId

/** Where a device's connection stands. [CONNECTED] means ready: its services are discovered. */
enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING }

/** Why a device disconnected. */
enum class DisconnectReason(val publicName: String) {
  /** The app called `disconnect()`. */
  REQUESTED("requested"),

  /** Out of range or a radio dropout. */
  OUT_OF_RANGE("outOfRange"),

  /** The device ended the connection. */
  DEVICE_DISCONNECTED("deviceDisconnected"),
  BLUETOOTH_OFF("bluetoothOff"),

  /** Anything else, including a connection attempt that failed or timed out. */
  FAILED("failed"),
}

/** The last time a device disconnected, and why. [error] is set for every reason but [DisconnectReason.REQUESTED]. */
data class Disconnect(val reason: DisconnectReason, val error: BleError? = null)

/** A device's connection as listeners see it. */
data class ConnectionChange(
  val id: DeviceId,
  val name: String?,
  val state: ConnectionState,
  val lastDisconnect: Disconnect?,
  /** Set while the device is remembered. */
  val remembered: RememberedDevice? = null,
)

/** How to connect. */
data class ConnectOptions(
  /** Give up after this many milliseconds; null to wait until the device appears. */
  val timeoutMs: Long? = 30_000,
  /** This connection's workarounds, over the ones set for every connection; null keeps those. */
  val android: AndroidConnectionOverrides = AndroidConnectionOverrides(),
  /** Remember the device once connected, so it can connect again after a relaunch. */
  val remember: Boolean = true,
  /** How the device was found, kept with it to find it again when its ID changes. */
  val identity: DeviceIdentity? = null,
)

/** When Android refreshes its cache of a device's services. */
enum class ServiceCacheRefresh { ON_FAILURE, ALWAYS, NEVER }

/** Android's connection workarounds, all on by default. */
data class AndroidConnectionOptions(
  /** Retry GATT status 133 and similar failures that pass on their own. */
  val retryTransientErrors: Boolean = true,
  /** Close the GATT client after a disconnection; a leaked client causes later failures. */
  val closeGattOnDisconnect: Boolean = true,
  /** Discover services only once pairing has finished; discovery during pairing returns nothing. */
  val waitForBonding: Boolean = true,
  /** Milliseconds to wait before discovery on a bonded device, while Android handles service changes; 0 for none. */
  val discoveryDelayMs: Long = 600,
  /** High connection priority until the device is ready, then balanced. */
  val highPriorityDuringSetup: Boolean = true,
  val refreshServiceCache: ServiceCacheRefresh = ServiceCacheRefresh.ON_FAILURE,
  /** The MTU to ask for once connected, so writes without response carry more than 20 bytes; 0 to keep 23. */
  val mtu: Int = 517,
) {
  fun with(overrides: AndroidConnectionOverrides): AndroidConnectionOptions =
    AndroidConnectionOptions(
      retryTransientErrors = overrides.retryTransientErrors ?: retryTransientErrors,
      closeGattOnDisconnect = overrides.closeGattOnDisconnect ?: closeGattOnDisconnect,
      waitForBonding = overrides.waitForBonding ?: waitForBonding,
      discoveryDelayMs = overrides.discoveryDelayMs ?: discoveryDelayMs,
      highPriorityDuringSetup = overrides.highPriorityDuringSetup ?: highPriorityDuringSetup,
      refreshServiceCache = overrides.refreshServiceCache ?: refreshServiceCache,
      mtu = overrides.mtu ?: mtu,
    )
}

/** Changes to [AndroidConnectionOptions]; null keeps the current value. */
data class AndroidConnectionOverrides(
  val retryTransientErrors: Boolean? = null,
  val closeGattOnDisconnect: Boolean? = null,
  val waitForBonding: Boolean? = null,
  val discoveryDelayMs: Long? = null,
  val highPriorityDuringSetup: Boolean? = null,
  val refreshServiceCache: ServiceCacheRefresh? = null,
  val mtu: Int? = null,
)

/** How the platform looks for the device on one attempt: a fast direct connection, or one that waits for it. */
enum class ConnectMode { DIRECT, WAITING }

/** Whether the phone can connect right now, and if not, why. */
enum class ConnectionReadiness {
  READY,
  BLUETOOTH_OFF,
  UNAUTHORIZED,
  UNSUPPORTED,
  ;

  val error: BleError?
    get() =
      when (this) {
        READY -> null
        BLUETOOTH_OFF -> BleError(BleError.Code.BLUETOOTH_OFF, "Bluetooth is off", operation = "connect")
        UNAUTHORIZED -> BleError(BleError.Code.UNAUTHORIZED, "Bluetooth permission is not granted", operation = "connect")
        UNSUPPORTED -> BleError(BleError.Code.UNSUPPORTED, "This phone has no Bluetooth Low Energy", operation = "connect")
      }
}

/** When to retry a failed attempt and how to look for the device next. */
data class RetryPolicy(
  /** Direct attempts before switching to a waiting one. */
  val directAttempts: Int = 3,
) {
  /** The pause before attempt [attempt] (2 for the first retry): 250 ms, doubling, at most 2 s. */
  fun backoffMs(attempt: Int): Long = minOf(2_000L, 250L shl maxOf(0, attempt - 2))

  fun mode(attempt: Int): ConnectMode = if (attempt <= directAttempts) ConnectMode.DIRECT else ConnectMode.WAITING
}
