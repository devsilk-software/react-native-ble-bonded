# react-native-ble-bonded

Bluetooth Low Energy for React Native, built for connections that stay up.

> **Status: preview (`0.1.0-beta.1`).** [This preview](#this-preview) lists what's in it; the API may still change
> before 0.1. Connections in the background and after the system relaunches your app aren't in it yet. Feedback and bug reports are welcome in [issues](https://github.com/devsilk-software/react-native-ble-bonded/issues).

Maintained by [Albert Liberski](https://github.com/aliberski) ([Devsilk](https://github.com/devsilk-software)), a former maintainer of
react-native-ble-plx.

## Built on

- **[Nitro Modules](https://nitro.margelo.com):** calls go straight from JavaScript to Swift and Kotlin through JSI,
  on the New Architecture. The Swift and Kotlin interfaces are generated from one TypeScript spec, so the JavaScript
  and native sides can't drift apart.
- **Swift Package Manager on iOS:** the library is a Swift package and builds with React Native's Swift Package
  Manager support, without CocoaPods.
- **Swift 6 and Kotlin coroutines:** the native code passes Swift 6's strict concurrency checks on iOS and uses
  coroutines on Android, with the same structure and the same behaviours on both.

## Requirements

| | Minimum |
|---|---|
| React Native | 0.88, New Architecture (the old architecture isn't supported) |
| Nitro Modules | `react-native-nitro-modules` and `nitro-core` `1.0.0-alpha.nightly.136.gdb81de61bd32`, exactly |
| iOS | 16.0, built with Xcode 26 (Swift 6.2) or newer, through Swift Package Manager (not CocoaPods) |
| Android | 7.0 (API 24); compileSdk 36; JDK 17 |
| Node | 22.13, React Native 0.88's own minimum |
| Expo | not supported yet: Expo can't build Nitro v1 modules |

## Install

```sh
npm install react-native-ble-bonded@next react-native-nitro-modules@1.0.0-alpha.nightly.136.gdb81de61bd32 nitro-core@1.0.0-alpha.nightly.136.gdb81de61bd32
```

**iOS:** Nitro v1 builds only through Swift Package Manager, and needs iOS 16:

1. Switch the app to Swift Package Manager once: `npx react-native spm add --deintegrate`. A new React Native app is
   set up for CocoaPods; this removes that setup, and needs CocoaPods (`pod`) installed to do it.
2. Set the app target's minimum deployment to iOS 16.0 (Xcode › the app target › General › Minimum Deployments). A
   new React Native 0.88 app starts at 15.1, and the build then fails with "requires minimum platform version 16.0".
3. Run `npx react-native spm` again, so the linked packages pick up iOS 16.
4. Add `NSBluetoothAlwaysUsageDescription` to `Info.plist`, saying why your app uses Bluetooth.

**Android:** add the permissions to `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" android:usesPermissionFlags="neverForLocation" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" android:maxSdkVersion="30" />
```

Bonded asks for the runtime permissions itself when you scan or connect, or earlier with `ble.requestPermissions()`.

## A quick tour

```tsx
import { ble } from 'react-native-ble-bonded'
import { useDevice, useNotifications, useRssi, useScan } from 'react-native-ble-bonded/hooks'

const HEART_RATE = '180D'
const MEASUREMENT = '2A37'

function Nearby({ onPick }: { onPick: (id: string) => void }) {
  // Each device once, strongest first; asks for permission and Bluetooth when needed.
  const { devices, isScanning } = useScan({ services: [HEART_RATE], sort: 'signal' })
  return devices.map((device) => <Row key={device.id} title={device.name} onPress={() => onPick(device.id)} />)
}

function Monitor({ id }: { id: string }) {
  const device = useDevice(id) // re-renders when the connection changes
  const { value } = useNotifications(device, HEART_RATE, MEASUREMENT) // subscribes once connected
  const { rssi } = useRssi(device) // signal strength in dBm, every second
  // …
}

const device = ble.device(id)
await device.connect() // remembered for next time; ble.connect() reconnects to it
const value = await device.read(service, characteristic) // ArrayBuffer
await device.write(service, characteristic, new Uint8Array([1, 2, 3]))
await device.writeChunked(service, characteristic, firmwareBlock, { withoutResponse: true }) // cut to the max write length
await device.createBond({ trigger: { service, characteristic: encryptedCharacteristic } }) // pairs on both platforms
```

Every Bluetooth or platform failure is a `BleError` with a `code` that means the same on iOS and Android, the
platform's own code, and a message that says what to do. A mistake in the call itself, such as a negative
`timeout`, throws at once instead. Diagnostics record connections, disconnections and bond changes natively, also in
the background, for a bug report: `ble.configure({ diagnostics: { enabled: true } })`, then
`ble.getDiagnostics()`.

New to Bluetooth? [`docs/glossary.md`](docs/glossary.md) explains the terms the API and docs use.

## This preview

- Bluetooth state and permissions, with hooks
- Scanning with filters, deduplicated and sorted for lists (`useScan`)
- Connecting, disconnecting, connection state (`useDevice`), with Android's connection workarounds built in
- Remembered devices: reconnect with `ble.connect()`; on iOS, found again by name and services when iOS changes the
  device's ID
- Reading and writing, with and without response; long writes up to 512 bytes; chunked writes
- Notifications and indications that survive disconnects (`useNotifications`)
- Descriptors
- Maximum write length and signal strength (`useRssi`)
- Pairing with `createBond`, and Android's bond state under `device.android`
- Typed errors and native diagnostics

Not in this preview:

- Reconnecting by itself after a drop, connections in the background, and the system relaunching your app
- Expo: Expo can't build Nitro v1 modules yet

## Developing

```sh
yarn install
yarn specs              # regenerate Nitro's code after changing src/specs
yarn typecheck && yarn lint && yarn test
yarn test:ios           # Swift tests, on the Mac
yarn test:android       # Kotlin tests, on the JVM
yarn check:behaviours   # every behaviour tested on both platforms
```

The example app in `example/` is the device-test app, a bare React Native app, with a Mac test peripheral in
[`tools/test-peripheral`](tools/test-peripheral):

```sh
yarn build              # compile src into lib, which the example app's Metro loads
cd example
yarn spm --yes          # once after cloning: sets up Swift Package Manager for iOS
yarn ios                # or: yarn android
```

Read [`docs/architecture.md`](docs/architecture.md) before adding a feature. The decisions behind the
design are in [`docs/decisions.md`](docs/decisions.md); releasing is in [`docs/releasing.md`](docs/releasing.md).

## Licence

MIT © Devsilk
