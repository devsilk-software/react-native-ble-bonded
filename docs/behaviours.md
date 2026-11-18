# Behaviours

Every behaviour Bonded promises, named once (decision A3). Each one has a test with exactly this name on iOS (a
Swift Testing display name) and on Android (a Kotest `then`, or `test` for an architecture rule). A behaviour that
exists on one platform only says so after a dash. `yarn check:behaviours` fails when a listed behaviour has no test
on a platform it applies to, or when a test's name isn't listed here.

Name a behaviour for what the user of Bonded can rely on, in plain words, not for how it's built.

## bluetoothState

- reports the radio's current state
- maps every platform state to an adapter state

## architecture

- the rules see the production sources
- no layer imports more than it may
- no feature uses another feature's code
- no file outside the Nitro module touches Nitro or React Native — Android only
