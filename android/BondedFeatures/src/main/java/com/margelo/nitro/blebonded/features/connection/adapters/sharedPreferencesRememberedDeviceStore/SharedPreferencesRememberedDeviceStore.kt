package com.margelo.nitro.blebonded.features.connection.adapters.sharedPreferencesRememberedDeviceStore

import android.content.Context
import com.margelo.nitro.blebonded.features.connection.application.interfaces.RememberedDeviceStore
import com.margelo.nitro.blebonded.features.connection.domain.DeviceIdentity
import com.margelo.nitro.blebonded.features.connection.domain.RememberedDevice
import com.margelo.nitro.blebonded.shared.domain.DeviceId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Answers [RememberedDeviceStore] with the app's private preferences, as JSON under one key. */
class SharedPreferencesRememberedDeviceStore(context: Context) : RememberedDeviceStore {
  private val preferences = context.applicationContext.getSharedPreferences("com.devsilk.bonded", Context.MODE_PRIVATE)

  override suspend fun load(): List<RememberedDevice> =
    withContext(Dispatchers.IO) {
      val json = preferences.getString(KEY, null) ?: return@withContext emptyList()
      runCatching {
        val array = JSONArray(json)
        (0 until array.length()).map { decode(array.getJSONObject(it)) }
      }.getOrDefault(emptyList())
    }

  override suspend fun save(devices: List<RememberedDevice>) {
    withContext(Dispatchers.IO) {
      val array = JSONArray()
      devices.forEach { array.put(encode(it)) }
      preferences.edit().putString(KEY, array.toString()).apply()
    }
  }

  private fun encode(device: RememberedDevice): JSONObject =
    JSONObject()
      .put("id", device.id.rawValue)
      .putOpt("name", device.name)
      .put("lastConnectedAt", device.lastConnectedAt)
      .put(
        "identity",
        JSONObject()
          .putOpt("name", device.identity.name)
          .put("services", JSONArray(device.identity.services))
          .putOpt("companyId", device.identity.companyId),
      )

  private fun decode(json: JSONObject): RememberedDevice {
    val identity = json.getJSONObject("identity")
    val services = identity.getJSONArray("services")
    return RememberedDevice(
      id = DeviceId(json.getString("id")),
      name = json.optString("name").ifEmpty { null },
      identity =
        DeviceIdentity(
          name = identity.optString("name").ifEmpty { null },
          services = (0 until services.length()).map { services.getString(it) },
          companyId = if (identity.has("companyId")) identity.getInt("companyId") else null,
        ),
      lastConnectedAt = json.getLong("lastConnectedAt"),
    )
  }

  private companion object {
    const val KEY = "rememberedDevices"
  }
}
