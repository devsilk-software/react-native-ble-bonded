// swift-tools-version: 6.2

import PackageDescription

// Everything on iOS that doesn't touch Nitro: the features, the shared kernel, the Bluetooth client and the
// composition root (docs/architecture.md). The Nitro module depends on this package (../../Package.swift); its
// tests run on the Mac with `swift test`, without an app or a simulator.
let package = Package(
  name: "BondedFeatures",
  platforms: [.iOS(.v16), .macOS(.v13)],
  products: [
    .library(name: "BondedFeatures", targets: ["BondedFeatures"]),
  ],
  targets: [
    .target(
      name: "BondedFeatures",
      path: ".",
      exclude: ["Tests"],
      sources: ["shared", "features", "infrastructure", "compositionRoot"],
      swiftSettings: [.enableUpcomingFeature("NonisolatedNonsendingByDefault")]
    ),
    .testTarget(
      name: "BondedFeaturesTests",
      dependencies: ["BondedFeatures"],
      path: "Tests",
      swiftSettings: [.enableUpcomingFeature("NonisolatedNonsendingByDefault")]
    ),
  ]
)
