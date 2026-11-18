# react-native-ble-bonded

Bluetooth Low Energy for React Native, built for connections that stay up: in the foreground, in the
background, and after the system relaunches your app.

> **Status: in development, not ready for use.**

Maintained by [Albert Liberski](https://github.com/aliberski) ([Devsilk](https://github.com/devsilk-software)), a former maintainer of
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

The example app in `example/` is the device-test app, a bare React Native app:

```sh
yarn build              # compile src into lib, which the example app's Metro loads
cd example
yarn spm --yes          # once after cloning: sets up Swift Package Manager for iOS
yarn ios                # or: yarn android
```

On iOS, Nitro v1 builds only through Swift Package Manager, so the example uses React Native's SPM support
instead of CocoaPods. An Expo example follows once Nitro v1 builds through CocoaPods (margelo/nitro#2054).

Read [`docs/architecture.md`](docs/architecture.md) before adding a feature. The decisions behind the
design are in [`docs/decisions.md`](docs/decisions.md).

## Licence

MIT © Devsilk
