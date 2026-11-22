package com.margelo.nitro.blebonded.features.diagnostics.adapters.fileDiagnosticsStore

import android.content.Context
import com.margelo.nitro.blebonded.features.diagnostics.application.interfaces.DiagnosticsStore
import com.margelo.nitro.blebonded.features.diagnostics.domain.DiagnosticEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

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

  override suspend fun save(events: List<DiagnosticEvent>) {
    withContext(Dispatchers.IO) {
      file.parentFile?.mkdirs()
      val array = JSONArray()
      events.forEach { array.put(encode(it)) }
      val temporary = File(file.parentFile, "${file.name}.tmp")
      temporary.writeText(array.toString())
      temporary.renameTo(file)
    }
  }

  override suspend fun delete() {
    withContext(Dispatchers.IO) { file.delete() }
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
}
