# Releasing

Releases are published from the maintainer's machine, with npm's two-factor confirmation (decision 12).

## Checklist

1. **Start from master.** Everything for the release is merged, CI is green, and the working tree is clean:
   `git checkout master && git pull && git status`.
2. **Set the version** in `package.json`. A preview is `0.1.0-beta.N`; a release is `0.1.0`.
3. **Run every check:**

   ```sh
   yarn install
   yarn specs && git diff --exit-code nitrogen   # the generated code matches the specs
   yarn typecheck && yarn lint && yarn test
   yarn test:ios && yarn test:android
   yarn check:behaviours
   ```

4. **Check the package.** `npm pack --dry-run` lists what npm will publish. It must contain `lib/`, `src/` (without
   `__tests__`), `nitrogen/`, `Package.swift`, the podspec, `ios/controllers`, the `ios/BondedFeatures` sources
   (without `Tests`), `android/src`, `android/BondedFeatures/src/main`, the Android build files, `nitro.json`,
   `react-native.config.js` and the README. It must not contain `example/`, `tools/`, `docs/`, test files or build
   output.
5. **Try the package in an app.** `npm pack`, install the `.tgz` into a fresh bare React Native app, set it up as
   the README's Install section says, and build it for iOS and Android. A device check of scan and connect is
   enough.
6. **Publish:**
   - a preview: `npm publish --tag next`, so `npm install react-native-ble-bonded` keeps installing the last
     release;
   - a release: `npm publish`.
7. **Tag and announce:** `git tag v<version> && git push origin v<version>`, then a GitHub release with what's in it
   and what's known not to work.
