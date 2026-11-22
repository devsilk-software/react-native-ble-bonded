package com.margelo.nitro.blebonded.features.diagnostics.adapters.fileDiagnosticsStore

import android.content.Context
import android.util.Log
import com.margelo.nitro.blebonded.features.diagnostics.application.interfaces.DiagnosticsStore
import com.margelo.nitro.blebonded.features.diagnostics.domain.DiagnosticEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** Answers [DiagnosticsStore] with a JSON file in the app's private files folder. */
class FileDiagnosticsStore(context: Context) : DiagnosticsStore {
  private val file = File(File(context.applicationContext.filesDir, "com.devsilk.bonded"), "diagnostics.json")

  override suspend fun load(): List<DiagnosticEvent> =
    withContext(Dispatchers.IO) {
      if (!file.exists()) return@withContext emptyList()
      runCatching {
        val array = JSONArray(file.readText())
        (0 until array.length()).map { decode(array.getJSONObject(it)) }
      }.getOrDefault(emptyList())
    }

  /** A failed write, such as on a full disk, is logged and skipped: diagnostics must never crash the app. */
  override suspend fun save(events: List<DiagnosticEvent>) {
    withContext(Dispatchers.IO) {
      try {
        file.parentFile?.mkdirs()
        val array = JSONArray()
        events.forEach { array.put(encode(it)) }
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(array.toString())
        if (!temporary.renameTo(file)) throw IOException("Could not replace ${file.name}")
      } catch (error: IOException) {
        Log.w(TAG, "Could not save the diagnostic events", error)
      }
    }
  }

  override suspend fun delete() {
    withContext(Dispatchers.IO) {
      if (file.exists() && !file.delete()) Log.w(TAG, "Could not delete the diagnostic events")
    }
  }

  private fun encode(event: DiagnosticEvent): JSONObject =
    JSONObject()
      .put("kind", event.kind.name)
      .put("at", event.at)
      .put("appActivity", event.appActivity.name)
      .putOpt("deviceId", event.deviceId)
      .putOpt("code", event.code)
      .putOpt("platformCode", event.platformCode)
      .putOpt("detail", event.detail)

  private fun decode(json: JSONObject): DiagnosticEvent =
    DiagnosticEvent(
      kind = DiagnosticEvent.Kind.valueOf(json.getString("kind")),
      at = json.getLong("at"),
      appActivity = DiagnosticEvent.AppActivity.valueOf(json.getString("appActivity")),
      deviceId = json.optString("deviceId").ifEmpty { null },
      code = json.optString("code").ifEmpty { null },
      platformCode = if (json.has("platformCode")) json.getInt("platformCode") else null,
      detail = json.optString("detail").ifEmpty { null },
    )

  private companion object {
    const val TAG = "Bonded"
  }
}
