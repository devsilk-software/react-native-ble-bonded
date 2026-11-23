// swift-tools-version: 6.2

import PackageDescription

// A Bluetooth Low Energy peripheral on the Mac, for testing Bonded on a phone without other hardware.
let package = Package(
  name: "TestPeripheral",
  platforms: [.macOS(.v13)],
  targets: [
    .executableTarget(name: "TestPeripheral", path: "Sources/TestPeripheral"),
  ]
)
