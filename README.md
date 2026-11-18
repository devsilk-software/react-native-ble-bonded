# react-native-ble-bonded

Bluetooth Low Energy for React Native, built for connections that stay up: in the foreground, in the
background, and after the system relaunches your app.

> **Status: in development, not ready for use.**

Maintained by Albert Liberski ([Devsilk](https://devsilk.com/)), formerly the maintainer of
react-native-ble-plx.

## Requirements

- React Native 0.88 or newer, New Architecture
- iOS 16 or newer; Android 7 (API 24) or newer
- Nitro Modules v1

## What works today

```ts
import { ble } from 'react-native-ble-bonded'

const state = await ble.getState() // 'poweredOn', 'poweredOff', 'unauthorized', …
```

## Developing

```sh
yarn install
yarn specs              # regenerate Nitro's code after changing src/specs
yarn typecheck && yarn lint && yarn test
yarn test:ios           # Swift tests, on the Mac
yarn test:android       # Kotlin tests, on the JVM
yarn check:behaviours   # every behaviour tested on both platforms
```

The example app in `example/` is the device-test app:

```sh
yarn build              # compile src into lib, which the example app's Metro loads
cd example
yarn prebuild
yarn android
```

iOS: Nitro v1 builds only through Swift Package Manager until its CocoaPods support lands
(margelo/nitro#2054), and Expo apps still use CocoaPods, so the example app doesn't build for iOS yet. A bare
React Native app set up with `npx react-native spm` builds and runs Bonded on iOS.

Read [`docs/architecture.md`](docs/architecture.md) before adding a feature. The decisions behind the
design are in [`docs/decisions.md`](docs/decisions.md).

## Licence

MIT © Devsilk
