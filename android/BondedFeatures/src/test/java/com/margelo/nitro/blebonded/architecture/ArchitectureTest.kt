package com.margelo.nitro.blebonded.architecture

import com.lemonappdev.konsist.api.Konsist
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty

/**
 * The dependency rule (docs/architecture.md), checked on every Kotlin file's imports. The same rules run on iOS as
 * Swift tests. Architecture tests are `FunSpec`s: a rule has no given or when.
 */
class ArchitectureTest : FunSpec({
  val base = "com.margelo.nitro.blebonded"
  val files = Konsist.scopeFromProduction().files

  fun layerOf(packageName: String): String? =
    when {
      packageName.startsWith("$base.shared.domain") -> "shared/domain"
      packageName.contains(".domain") -> "domain"
      packageName.contains(".application") -> "application"
      packageName.contains(".adapters") -> "adapters"
      packageName.startsWith("$base.infrastructure") -> "infrastructure"
      else -> null
    }

  fun featureOf(name: String): String? = Regex("""^$base\.features\.([A-Za-z]+)""").find(name)?.groupValues?.get(1)

  test("the rules see the production sources") {
    files.filter { it.packagee?.name?.startsWith("$base.features") == true }.shouldNotBeEmpty()
  }

  test("no layer imports more than it may") {
    val violations =
      files.flatMap { file ->
        val layer = layerOf(file.packagee?.name ?: "") ?: return@flatMap emptyList()
        file.imports.map { it.name }.filter { import ->
          val ownCode = import.startsWith(base)
          val importedLayer = layerOf(import)
          when (layer) {
            "shared/domain" -> !import.startsWith("kotlin")
            "domain" -> !(import.startsWith("kotlin") || importedLayer in setOf("domain", "shared/domain"))
            "application" ->
              !(import.startsWith("kotlin") || import.startsWith("kotlinx.coroutines") ||
                importedLayer in setOf("domain", "application", "shared/domain"))
            "adapters", "infrastructure" -> ownCode && importedLayer == null
            else -> false
          }
        }.map { "${file.path}: $layer imports $it" }
      }
    violations.shouldBeEmpty()
  }

  test("no feature uses another feature's code") {
    val violations =
      files.flatMap { file ->
        val feature = featureOf(file.packagee?.name ?: "") ?: return@flatMap emptyList()
        file.imports.map { it.name }
          .filter { featureOf(it).let { other -> other != null && other != feature } }
          .map { "${file.path} ($feature) imports $it" }
      }
    violations.shouldBeEmpty()
  }

  test("no file outside the Nitro module touches Nitro or React Native") {
    val violations =
      files.flatMap { file ->
        file.imports.map { it.name }
          .filter { it.startsWith("com.margelo.nitro.core") || it.startsWith("com.facebook.react") }
          .map { "${file.path} imports $it" }
      }
    violations.shouldBeEmpty()
  }
})
