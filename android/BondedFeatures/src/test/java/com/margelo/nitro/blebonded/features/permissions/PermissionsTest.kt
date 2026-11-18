package com.margelo.nitro.blebonded.features.permissions

import com.margelo.nitro.blebonded.features.permissions.application.useCases.getPermissionStatus.GetPermissionStatus
import com.margelo.nitro.blebonded.features.permissions.application.useCases.observePermission.ObservePermission
import com.margelo.nitro.blebonded.features.permissions.application.useCases.requestPermission.RequestPermission
import com.margelo.nitro.blebonded.features.permissions.domain.PermissionFacts
import com.margelo.nitro.blebonded.features.permissions.domain.PermissionStatus
import com.margelo.nitro.blebonded.features.permissions.domain.RuntimePermission
import com.margelo.nitro.blebonded.features.permissions.domain.permissionStatus
import com.margelo.nitro.blebonded.features.permissions.domain.requiredPermissions
import com.margelo.nitro.blebonded.mocks.FakePermissionSource
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList

class PermissionsTest : BehaviorSpec({
  given("a permission status") {
    PermissionStatus.entries.forEach { status ->
      `when`("the status is $status") {
        then("reports the permission status without asking") {
          GetPermissionStatus(FakePermissionSource(status, afterRequest = PermissionStatus.BLOCKED))() shouldBe status
        }
      }
    }
  }

  given("an app that may still ask") {
    PermissionStatus.entries.forEach { choice ->
      `when`("the user's choice leads to $choice") {
        then("reports the status the user chose after asking") {
          RequestPermission(FakePermissionSource(PermissionStatus.DENIED, afterRequest = choice))() shouldBe choice
        }
      }
    }
  }

  given("Android's facts about each permission") {
    val granted = PermissionFacts(granted = true, showRationale = false, requestedBefore = true)
    val neverAsked = PermissionFacts(granted = false, showRationale = false, requestedBefore = false)
    val refusedOnce = PermissionFacts(granted = false, showRationale = true, requestedBefore = true)
    val dontAskAgain = PermissionFacts(granted = false, showRationale = false, requestedBefore = true)
    val cases =
      listOf(
        Triple("no Bluetooth Low Energy", permissionStatus(false, listOf(granted)), PermissionStatus.UNAVAILABLE),
        Triple("every permission granted", permissionStatus(true, listOf(granted, granted)), PermissionStatus.GRANTED),
        Triple("a permission refused once", permissionStatus(true, listOf(granted, refusedOnce)), PermissionStatus.DENIED),
        Triple("don't ask again on one permission", permissionStatus(true, listOf(granted, dontAskAgain)), PermissionStatus.BLOCKED),
      )
    cases.forEach { (name, actual, expected) ->
      `when`("the facts show $name") {
        then("maps every platform authorization to a permission status") {
          actual shouldBe expected
        }
      }
    }

    `when`("Bonded has never asked, so Android gives no hint") {
      then("reports blocked only after the app has asked before") {
        permissionStatus(true, listOf(neverAsked, neverAsked)) shouldBe PermissionStatus.DENIED
        permissionStatus(true, listOf(dontAskAgain, dontAskAgain)) shouldBe PermissionStatus.BLOCKED
      }
    }
  }

  given("the Android version") {
    listOf(
      30 to listOf(RuntimePermission.FINE_LOCATION),
      31 to listOf(RuntimePermission.SCAN, RuntimePermission.CONNECT),
      36 to listOf(RuntimePermission.SCAN, RuntimePermission.CONNECT),
    ).forEach { (sdkInt, expected) ->
      `when`("the phone runs API $sdkInt") {
        then("asks for scan and connect permissions from Android 12, location before") {
          requiredPermissions(sdkInt) shouldBe expected
        }
      }
    }
  }

  given("a permission that is denied") {
    `when`("the user grants it, then blocks it in Settings") {
      then("reports the current status first, then each change") {
        val source =
          FakePermissionSource(PermissionStatus.DENIED, changes = flowOf(PermissionStatus.GRANTED, PermissionStatus.BLOCKED))

        ObservePermission(source)().toList() shouldBe
          listOf(PermissionStatus.DENIED, PermissionStatus.GRANTED, PermissionStatus.BLOCKED)
      }
    }
  }

  given("a permission that is granted") {
    `when`("the app returns to the foreground several times") {
      then("suppresses repeated statuses") {
        val changes = flowOf(PermissionStatus.GRANTED, PermissionStatus.GRANTED, PermissionStatus.BLOCKED, PermissionStatus.BLOCKED)

        ObservePermission(FakePermissionSource(PermissionStatus.GRANTED, changes = changes))().toList() shouldBe
          listOf(PermissionStatus.GRANTED, PermissionStatus.BLOCKED)
      }
    }
  }
})
