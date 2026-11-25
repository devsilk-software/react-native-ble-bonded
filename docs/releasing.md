# Releasing

Releases are published from the maintainer's machine, with npm's two-factor confirmation (decision 12).

## Checklist

1. **Start from master.** Everything for the release is merged, CI is green, and the working tree is clean:
   `git checkout master && git pull && git status`.
2. **Set the version** in `package.json`. A preview is `0.1.0-beta.N`; a release is `0.1.0`.
3. **Run every check:**

   ```sh
   yarn install
   yarn specs && git diff --exit-code -- nitrogen Package.swift   # the generated code matches the specs, as CI checks
   git status --porcelain                                       # and nothing new was generated: prints nothing
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
6. **Publish**, from your own terminal: npm asks for two-factor confirmation there, and gives up at once in a
   terminal that isn't interactive.
   - While there's no full release yet, a preview goes out as `latest` (`npm publish --tag latest`): npm's page shows
     the README of the version tagged `latest`, and a pre-release version needs the tag named.
   - Once there's a full release, a preview goes out as `next` (`npm publish --tag next`), so
     `npm install react-native-ble-bonded` keeps installing the release.
   - A release: `npm publish`.

   If npm's page still shows the previous version as latest after a few minutes, set the tag again, which refreshes
   it: `npm dist-tag add react-native-ble-bonded@<version> latest`.
7. **Release on GitHub:** `gh release create v<version> --target <full commit hash> --title "<version>: …"
   --notes-file <notes>`, adding `--prerelease` for a beta. The notes say what's in it and what isn't yet.
