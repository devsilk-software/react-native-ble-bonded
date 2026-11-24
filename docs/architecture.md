# Architecture

How Bonded's code is organised, and how to add a feature. The decisions behind it are A1–A5 in
[`decisions.md`](decisions.md). The reference implementation is the `bluetoothState` feature.

## The layers, top to bottom

```
src/api        the public `ble` object (TypeScript)
src/specs      the Nitro contract: one HybridObject per feature
  │  Nitrogen generates nitrogen/generated/ (never edit it)
ios/controllers, android/src/main/.../Hybrid*.kt
               thin controllers: call a use case, map the result to the generated types
ios/BondedFeatures, android/BondedFeatures
               everything that doesn't touch Nitro: features, shared kernel, Bluetooth client, composition root
```

## Directory structure

iOS and Android mirror each other.

```
ios/
├── controllers/                        # Nitro HybridObject implementations (Hybrid{Feature}.swift)
└── BondedFeatures/                     # Swift package; `swift test` runs on the Mac
    ├── shared/domain/                  # the shared kernel: DeviceId, BleError, domain events
    ├── shared/application/             # the event bus and the listener rule every feature uses
    ├── features/{feature}/
    │   ├── domain/                     # states, rules, values; base library only
    │   ├── application/
    │   │   ├── interfaces/             # what a use case needs from outside, in Bonded's words
    │   │   ├── services/               # state several use cases share, such as a recorder
    │   │   └── useCases/{useCase}/     # one folder per use case
    │   └── adapters/{thing}/           # implements an interface with a technology
    ├── infrastructure/bluetooth/       # the one Bluetooth client every feature's adapters share
    ├── infrastructure/lifecycle/       # the app's foreground and background state
    ├── compositionRoot/                # builds adapters and use cases; the controllers' entry point
    └── Tests/                          # mirrors the folders above; fakes in mocks/, rules in architecture/
android/
├── src/main/java/com/margelo/nitro/blebonded/Hybrid{Feature}.kt   # controllers
└── BondedFeatures/                     # Android library with its own Gradle build, for tests and checks
    └── src/main/java/com/margelo/nitro/blebonded/
        ├── shared/{domain,application}/  features/{feature}/…  infrastructure/{bluetooth,lifecycle}/  compositionRoot/
```

The Nitro module compiles `android/BondedFeatures/src/main/java` into itself, because React Native's
autolinking includes one Gradle project per package. On iOS the module's `Package.swift` depends on the
`BondedFeatures` package.

Features: `bluetoothState`, `permissions`, `diagnostics`, `scanning`, `connection` and `gatt` (done), then
`bonding` and `background`. A feature's folders appear with its first use case.

## The dependency rule

Dependencies point inward only.

| Layer | Holds | May import |
|---|---|---|
| `shared/domain` | the types every feature speaks | the base library only |
| `shared/application` | the event bus and the listener rule (current value first, then changes, repeats suppressed) | `shared/domain`; on Android also `kotlinx.coroutines` |
| `domain` | states, rules, values | the base library, `shared/domain` |
| `application` | use cases and the interfaces they need | `domain`, `shared/domain`, `shared/application`; on Android also `kotlinx.coroutines` |
| `adapters` | implementations of those interfaces | `application`, `domain`, `infrastructure`, the platform's Bluetooth framework |
| `infrastructure` | talks to a technology for anyone; knows no feature | the platform only |
| `compositionRoot` | wiring | anything in the package |
| controllers | Nitro boundary | `compositionRoot`, use cases, domain types, Nitro |

- **Features never use each other's code.** They share only `shared/domain`, `shared/application` and the
  Bluetooth client in `infrastructure`. A reaction across features (for example restoring subscriptions after a reconnect) goes
  through a domain event on the event bus the composition root creates.
- **Nothing in `BondedFeatures` imports Nitro or React Native.** Only the controllers do.

Two questions sort most code:

- Does it mention a technology (Core Bluetooth, `BluetoothGatt`, a permission)? Then it's an adapter or
  infrastructure, never a use case or domain.
- Does it know about Bonded's features (scanning, connections, bonds)? Then it isn't infrastructure.

## How the rules are checked

| Platform | Check | Command |
|---|---|---|
| iOS | `Tests/architecture/LayerImportTests.swift` reads every file's imports and declared types | `yarn test:ios` |
| Android | `src/test/.../architecture/ArchitectureTest.kt` with Konsist | `yarn test:android` |
| TypeScript | ESLint's boundaries rule: `specs` ← `api` ← `hooks`; `plugin` stands alone | `yarn lint` |
| Both | every behaviour in [`behaviours.md`](behaviours.md) has a test of that name on each platform | `yarn check:behaviours` |

Each check first asserts that it sees the production sources, so it can't pass by scanning nothing.

## Threading

- **iOS**: Core Bluetooth calls its delegate on Bonded's own serial queue, never the main thread. The delegate
  forwards each callback, in order, through an `AsyncStream` into the `BluetoothCentral` actor, which holds
  all state. Use cases are `async` and `Sendable`. The package builds in Swift 6 language mode; data-race
  errors are fixed, never silenced.
- **Android**: Bluetooth callbacks move onto a single-threaded dispatcher per device; GATT operations queue
  in a `Channel`, each with a timeout. Use cases are `suspend` functions.
- **Bridge**: Nitro turns `async` and `suspend` into JavaScript Promises.

## Adding a feature

1. Name its behaviours in `docs/behaviours.md`.
2. Write the domain types and the use case's interface in `application/interfaces/`, then the use case and
   its tests against a fake, on both platforms, with the behaviour names as test names.
3. Write the adapter on the shared Bluetooth client, extending the client only with platform calls.
4. Wire it in both composition roots.
5. Add or extend the feature's spec in `src/specs/`, run `yarn specs`, and implement the controller.
6. Expose it on `ble` in `src/api`, with a Jest test.
7. Add a screen or button to the example app for the device checklist.
