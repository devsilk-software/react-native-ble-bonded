package com.margelo.nitro.blebonded

import com.margelo.nitro.blebonded.shared.domain.BleError
import com.margelo.nitro.blebonded.shared.domain.DeviceId
import com.margelo.nitro.core.Callback
import com.margelo.nitro.core.Promise
import com.margelo.nitro.core.invoke
import com.margelo.nitro.blebonded.features.connection.domain.AndroidConnectionOverrides as Overrides
import com.margelo.nitro.blebonded.features.connection.domain.ConnectOptions as Options
import com.margelo.nitro.blebonded.features.connection.domain.ConnectionChange as Change
import com.margelo.nitro.blebonded.features.connection.domain.DeviceIdentity as Identity
import com.margelo.nitro.blebonded.features.connection.domain.GattService as Service
import com.margelo.nitro.blebonded.features.connection.domain.ServiceCacheRefresh as CacheRefresh

/**
 * The `Connection` HybridObject: calls the `connection` feature's use cases and maps between them and the
 * generated Nitro types. Nothing else lives here (decision A5).
 */
class HybridConnection : HybridConnectionSpec() {
  companion object : HybridConnectionSpec.Companion {
    private val listeners = ListenerRegistry()

    override fun connect(id: String, options: NativeConnectOptions): Promise<Unit> {
      val connectOptions =
        Options(
          timeoutMs = if (options.timeoutMs > 0) options.timeoutMs.toLong() else null,
          android = options.android?.toOverrides() ?: Overrides(),
          remember = options.remember,
          identity = options.identity?.let { Identity(it.name, it.services.toList(), it.companyId?.toInt()) },
        )
      return Promise.async {
        try {
          root().connect(DeviceId(id), connectOptions)
        } catch (error: BleError) {
          throw Exception(error.jsMessage)
        }
      }
    }

    override fun disconnect(id: String): Promise<Unit> = Promise.async { root().disconnect(DeviceId(id)) }

    override fun forget(id: String): Promise<Unit> = Promise.async { root().forgetDevice(DeviceId(id)) }

    override fun getServices(id: String): Promise<Array<NativeService>> =
      Promise.async {
        try {
          root().getServices(DeviceId(id)).map { it.toSpec() }.toTypedArray()
        } catch (error: BleError) {
          throw Exception(error.jsMessage)
        }
      }

    override fun addListener(onChange: Callback<(change: NativeConnectionChange) -> Unit>): Promise<Double> =
      Promise.async { listeners.add(root().observeConnections()) { change -> onChange(change.toSpec()) } }

    override fun removeListener(id: Double) = listeners.remove(id)

    override fun configureAndroid(options: NativeAndroidConnectionOptions) = root().configureConnections(options.toOverrides())

    private fun NativeAndroidConnectionOptions.toOverrides() =
      Overrides(
        retryTransientErrors = retryTransientErrors,
        closeGattOnDisconnect = closeGattOnDisconnect,
        waitForBonding = waitForBonding,
        discoveryDelayMs = discoveryDelayMs?.toLong(),
        highPriorityDuringSetup = highPriorityDuringSetup,
        refreshServiceCache =
          when (refreshServiceCache) {
            ServiceCacheRefresh.ONFAILURE -> CacheRefresh.ON_FAILURE
            ServiceCacheRefresh.ALWAYS -> CacheRefresh.ALWAYS
            ServiceCacheRefresh.NEVER -> CacheRefresh.NEVER
            null -> null
          },
      )

    // The generated enums name each case like Bonded's, without underscores (OUT_OF_RANGE is OUTOFRANGE).
    private fun specName(name: String) = name.replace("_", "")

    private fun Change.toSpec(): NativeConnectionChange {
      val error = lastDisconnect?.error
      return NativeConnectionChange(
        id = id.rawValue,
        name = name,
        state = ConnectionState.valueOf(specName(state.name)),
        reason = lastDisconnect?.let { DisconnectReason.valueOf(specName(it.reason.name)) },
        errorCode = error?.code?.publicName,
        errorMessage = error?.message,
        platformCode = error?.platformCode?.toDouble(),
        isRemembered = remembered != null,
        identity = remembered?.identity?.let { NativeDeviceIdentity(it.name, it.services.toTypedArray(), it.companyId?.toDouble()) },
        lastConnectedAt = remembered?.lastConnectedAt?.toDouble(),
      )
    }

    private fun Service.toSpec() =
      NativeService(
        uuid = uuid.rawValue,
        characteristics =
          characteristics.map { characteristic ->
            NativeCharacteristic(
              uuid = characteristic.uuid.rawValue,
              properties = characteristic.properties.map { CharacteristicProperty.valueOf(specName(it.name)) }.toTypedArray(),
            )
          }.toTypedArray(),
      )
  }
}
