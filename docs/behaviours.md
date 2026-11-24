# Behaviours

Every behaviour Bonded promises, named once (decision A3). Each one has a test with exactly this name on iOS (a
Swift Testing display name) and on Android (a Kotest `then`, or `test` for an architecture rule). A behaviour that
exists on one platform only says so after a dash. `yarn check:behaviours` fails when a listed behaviour has no test
on a platform it applies to, or when a test's name isn't listed here.

Name a behaviour for what the user of Bonded can rely on, in plain words, not for how it's built.

## bluetoothState

- reports the radio's current state
- maps every platform state to an adapter state
- reports the current state first, then each change
- suppresses repeated states
- reports the state after asking to turn Bluetooth on

## permissions

- reports the permission status without asking
- reports the status the user chose after asking
- maps every platform authorization to a permission status
- reports the current status first, then each change
- suppresses repeated statuses
- reports blocked only after the app has asked before — Android only
- asks for scan and connect permissions from Android 12, location before — Android only

## scanning

- reports each device found with its advertisement
- reports each device once unless duplicates are allowed
- reports only devices whose name matches the filter
- splits the company ID off the manufacturer data
- includes the full advertisement only when asked
- stops the scan after the timeout
- replaces a running scan when a new one starts
- refuses to scan while Bluetooth is off or without permission
- refuses an unfiltered scan in the background
- pauses an unfiltered scan in the background and resumes it in the foreground
- reports a failure when Bluetooth goes off during a scan
- publishes when a scan starts and stops
- expands short service UUIDs to their full form
- spaces scan starts to stay within Android's limit — Android only

## connection

- connects and discovers the device's services
- reports connecting, then connected once services are discovered
- resolves at once when already connected
- shares one attempt when connect is called again while connecting
- gives up after the timeout
- cancels a connection attempt when disconnect is called
- disconnects when asked and reports it as requested
- disconnects a device that finished connecting after disconnect was called
- connects again only after a disconnect still under way has finished
- reports why the device disconnected
- reports each device's current state first, then each change
- retries transient failures, first directly, then waiting for the device
- fails at once on an error that won't pass on its own
- refuses to connect while Bluetooth is off or without permission
- keeps waiting for the device while Bluetooth is off
- refuses to read services while not connected
- publishes connection attempts, retries, connections and disconnections
- maps platform disconnect errors to reasons
- remembers a device after it connects
- remembers nothing when asked not to
- reports remembered devices before they connect again
- lists every known connection, remembered devices included
- forgets a device and disconnects it
- keeps the identity the device was found by
- reads every characteristic's properties
- plans the setup steps from the workarounds and the bond — Android only
- applies a connection's own workarounds over the ones set for every connection — Android only
- retries nothing when retrying transient errors is switched off — Android only

## gatt

- reads a characteristic's value
- writes a value with and without response
- refuses an operation on a characteristic the device doesn't have
- refuses an operation the characteristic doesn't allow
- refuses a value longer than the write allows
- gives up on an operation after its timeout
- names the error the device answered with
- asks for pairing when the characteristic needs it
- publishes each operation for diagnostics
- names Android's own statuses apart from the device's errors — Android only
- reports a disconnect during an operation as not connected — Android only
- names the requests Android refuses to send — Android only

## diagnostics

- records nothing while diagnostics are off
- records each published event with its time and the app's state
- keeps only the newest events up to the capacity
- returns the events at or after a given time
- saves the recorded events so they survive the app being killed
- restores the events saved before a relaunch
- deletes the stored events when cleared or turned off
- delivers every event recorded after a listener is added
- records GATT operations only when asked to

## architecture

- the rules see the production sources
- no layer imports more than it may
- no feature uses another feature's code
- no file outside the Nitro module touches Nitro or React Native — Android only
