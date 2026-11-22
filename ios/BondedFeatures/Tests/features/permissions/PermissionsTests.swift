import CoreBluetooth
import Testing
@testable import BondedFeatures

@Suite("Permissions")
struct PermissionsTests {
  @Test("reports the permission status without asking", arguments: PermissionStatus.allCases)
  func reportsThePermissionStatusWithoutAsking(status: PermissionStatus) async {
    let getStatus = GetPermissionStatus(source: FakePermissionSource(status: status, afterRequest: .blocked))

    #expect(await getStatus() == status)
  }

  @Test("reports the status the user chose after asking", arguments: PermissionStatus.allCases)
  func reportsTheStatusTheUserChoseAfterAsking(choice: PermissionStatus) async {
    let request = RequestPermission(source: FakePermissionSource(status: .denied, afterRequest: choice))

    #expect(await request() == choice)
  }

  @Test(
    "maps every platform authorization to a permission status",
    arguments: [
      (CBManagerAuthorization.notDetermined, PermissionStatus.denied),
      (.allowedAlways, .granted),
      (.denied, .blocked),
      (.restricted, .blocked),
    ]
  )
  func mapsEveryPlatformAuthorizationToAPermissionStatus(platform: CBManagerAuthorization, expected: PermissionStatus) {
    #expect(CoreBluetoothPermissionSource.permissionStatus(from: platform) == expected)
  }

  @Test("reports the current status first, then each change")
  func reportsTheCurrentStatusFirstThenEachChange() async {
    let (changes, push) = AsyncStream<PermissionStatus>.makeStream()
    let observe = ObservePermission(source: FakePermissionSource(status: .denied, changes: changes))
    push.yield(.granted)
    push.yield(.blocked)
    push.finish()

    var received: [PermissionStatus] = []
    for await status in observe() {
      received.append(status)
    }

    #expect(received == [.denied, .granted, .blocked])
  }

  @Test("suppresses repeated statuses")
  func suppressesRepeatedStatuses() async {
    let (changes, push) = AsyncStream<PermissionStatus>.makeStream()
    let observe = ObservePermission(source: FakePermissionSource(status: .granted, changes: changes))
    for status in [PermissionStatus.granted, .granted, .blocked, .blocked] {
      push.yield(status)
    }
    push.finish()

    var received: [PermissionStatus] = []
    for await status in observe() {
      received.append(status)
    }

    #expect(received == [.granted, .blocked])
  }
}
