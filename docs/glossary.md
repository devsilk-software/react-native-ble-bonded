# Glossary

The words Bonded's API and docs use, in plain language. The first part explains Bluetooth Low Energy terms; the
second, terms that are Bonded's own. Each part is alphabetical. The reasons behind each behaviour are in
[`decisions.md`](decisions.md), cited by number (A11 is row A11).

- [Bluetooth terms](#bluetooth-terms)
- [Bonded terms](#bonded-terms)

## Bluetooth terms

### Advertising

A peripheral sends short packets, several times a second, to say it is there. An advertisement can carry a name,
service UUIDs, manufacturer data, service data and a TX power level. A device has to advertise before a phone can
find it or connect to it.

### ATT (Attribute Protocol)

The low-level protocol a connected phone and device use to read and write values. A write or a notification spends
3 bytes of each packet on the ATT header (what the packet is, and which attribute), which is why one packet carries
at most MTU − 3 bytes of value.

### ATT error

The code a device answers with when it refuses a request, such as `invalidAttributeValueLength` or
`insufficientEncryption`. Bonded gives its name in `BleError.attError` and the number in `platformCode`. A
firmware's own codes come as `applicationError`.

### Bluetooth Low Energy (BLE)

The low-power part of Bluetooth that sensors, wearables and other small devices use. It is separate from classic
Bluetooth, which headphones and speakers use for audio. Bonded supports only Bluetooth Low Energy.

### Bonding

Keeping the keys from pairing, on the phone and the device, so later connections are encrypted without pairing
again. iOS and Android store bonds in their system settings. See [pairing](#pairing). Bonded pairs with
`device.createBond()`, which needs a [trigger characteristic](#trigger-characteristic); on Android,
[`device.android`](#deviceandroid) reads and removes the bond. `forget()` keeps it (decision 7).

### CCCD (Client Characteristic Configuration Descriptor)

The descriptor (UUID `2902`) a phone writes to switch a characteristic's notifications or indications on or off.
Bonded writes it for you when you subscribe, and again when the subscription is removed while the device is
connected.

### Central

The side that scans and starts connections; here, the phone. Bonded acts only as a central.

### Characteristic

One value on a device, such as a heart rate or a setting, identified by a UUID and grouped in a service. Its
properties say whether it can be read, written or notify. You address it by service and characteristic UUID:
`device.read(service, characteristic)`.

### Company identifier

A number the Bluetooth SIG assigns to each manufacturer, such as `0x0059` for Nordic Semiconductor. It is the first
two bytes of manufacturer data; Bonded splits it off as `companyId`.

### Connection

A link between the phone and one device, which lets the phone read, write and receive notifications. A phone can
hold several at once; Android phones typically allow about seven.

### Connection priority (Android)

Android's setting for how often the phone and device exchange packets. High priority is faster and uses more power;
balanced is the default. See [Android connection workarounds](#android-connection-workarounds).

### Descriptor

Extra information attached to a characteristic, such as its user description (`2901`). The one apps meet most is
the CCCD. `device.readDescriptor()` and `device.writeDescriptor()` read and write them, and
`device.getServices({ descriptors: true })` lists them; writing the CCCD rejects with `unsupported`, because
subscriptions manage it (decision A14).

### Disconnect

The end of a connection, because the app asked, the device ended it, the device went out of range or Bluetooth went
off. Bonded reports why as a [disconnect reason](#disconnect-reason).

### Encryption

Scrambling the connection's traffic with keys made during pairing. A characteristic can require an encrypted
connection; reading or writing it before pairing fails with `pairingRequired`.

### Flow control

Holding back data until the receiver has room for it. The phone's queue for writes without response is small;
Bonded waits for room before each one, so a loop of awaited writes never drops a value (decision A11).

### GATT (Generic Attribute Profile)

How a device organises its data: services, which contain characteristics, which can have descriptors. The device is
the GATT server; the phone is the GATT client.

### GATT status 133 (Android)

Android's catch-all failure code, common when connecting. It often passes on a second try. Bonded retries it unless
`android.retryTransientErrors` is off.

### Indication

Like a notification, but the phone confirms each value to the device. Slower, and the device knows the value
arrived. Bonded uses notifications when a characteristic offers them, indications otherwise.

### Long write

A write with response whose value is longer than one packet allows: it goes in pieces the device puts back
together. Bluetooth limits a value to 512 bytes. `device.write()` does a long write when it needs to.

### Manufacturer data

Bytes in an advertisement that the manufacturer defines, starting with its company identifier.
`ScannedDevice.manufacturerData` has the company ID and the rest of the bytes separately.

### MTU (Maximum Transmission Unit)

The largest packet a connection carries, agreed per connection by the phone and the device. It starts at 23 bytes;
minus the 3-byte ATT header, that leaves 20 bytes for a value. iOS negotiates a larger MTU by itself; on Android,
Bonded asks for 517, the most Bluetooth allows, unless `android.mtu` says otherwise. The device may agree to less.

### Notification

A value the device sends whenever it changes, without the phone asking. The device doesn't learn whether it
arrived. Receive them with `device.subscribe()` or `useNotifications()`.

### Pairing

The phone and device exchange keys so their connection can be encrypted. The system usually shows a dialog, and may
ask for a code. Pairing that is kept is [bonding](#bonding). iOS has no call to start pairing: it starts by itself
when an operation reaches a characteristic that needs encryption.

### Peripheral

The device that advertises and accepts connections, such as a sensor. In Bonded's API it is simply "the device".

### PHY

The radio mode a packet is sent with: 1M (the default), 2M (faster) or Coded (longer range).
`ScannedDevice.primaryPhy` and `secondaryPhy` report it, on Android 8 and newer.

### Properties

What a characteristic allows: `read`, `write`, `writeWithoutResponse`, `notify`, `indicate`, `broadcast`,
`authenticatedSignedWrites`, `extendedProperties`. `device.getServices()` lists them. Bonded checks an operation
against them before sending it, and rejects with `unsupported` when the characteristic doesn't allow it.

### Read

Asking the device for a characteristic's current value: `device.read(service, characteristic)` resolves with an
`ArrayBuffer`.

### RSSI (Received Signal Strength Indicator)

How strongly the phone receives a device, in dBm. Values are negative; closer to zero is stronger. As a rough
guide: −30 to −50 is right next to the phone, −60 to −80 is a few metres away or behind a wall, below −90 is near
the edge of range. It is noisy: it jumps by several dBm between reports and changes with orientation, bodies and
walls, so average several readings and don't treat it as a distance. A scan reports it as `ScannedDevice.rssi`;
`useScan({ sort: 'signal' })` lists the strongest first. While connected, `device.readRssi()` reads it, and
`useRssi(device)` reads it every second (decision A13).

### Scanning

Listening for advertisements to find devices. See [scan](#scan).

### Service

A group of related characteristics, identified by a UUID, such as Heart Rate (`180D`). A device can advertise some
of its service UUIDs, which lets a scan look for devices of a kind.

### Service cache (Android)

Android keeps a device's discovered services and may reuse them on the next connection. After a firmware update the
cache can be out of date. `android.refreshServiceCache` sets when Bonded refreshes it: `onFailure` (the default),
`always` or `never`.

### Service data

Bytes in an advertisement tied to a service UUID. `ScannedDevice.serviceData` maps each UUID to its bytes; it comes
with `fullAdvertisement: true`.

### Service discovery

After connecting, the phone asks the device for its services, characteristics and descriptors. Bonded does this as
part of `connect()`, so `connected` means ready to use.

### TX power level

The transmit power a device advertises, in dBm. Compared with RSSI, it gives a rough idea of how much signal is
lost on the way. `ScannedDevice.txPowerLevel`, with `fullAdvertisement: true`.

### UUID

The 128-bit identifier of a service, characteristic or descriptor, such as `0000180d-0000-1000-8000-00805f9b34fb`.
Standard ones also have a short form, such as `180D`, which stands for that full UUID. Bonded accepts both and
always returns full lowercase UUIDs.

### Write with response

A write the device confirms. `device.write()` resolves once it has, and rejects when the device refuses. The
default.

### Write without response

A write the device doesn't confirm: faster, but the app doesn't learn whether the device handled it.
`device.write(..., { withoutResponse: true })` resolves once the phone has sent the value. The value must fit the
[maximum write length](#maximum-write-length).

## Bonded terms

### Adapter state

The phone's Bluetooth radio as Bonded sees it (`BluetoothAdapterState`): `unknown`, `resetting`, `unsupported`,
`unauthorized`, `poweredOff`, `poweredOn`. `ble.getState()`, `ble.onStateChange()` and `useBluetoothState()` report
it.

### Android connection workarounds

Fixes for Android's known Bluetooth problems, all on by default and ignored on iOS: retrying transient errors such
as GATT status 133, closing the GATT client after a disconnection, waiting for pairing before service discovery, a
pause before discovery on a bonded device, high connection priority during setup, refreshing the service cache and
asking for a larger MTU. Set them for every connection with `ble.configure({ android })` or for one with
`connect({ android })` (`AndroidConnectionOptions`, decision A9).

### `attError`

The name of the [ATT error](#att-error) a device refused an operation with, on a `BleError`.

### `autoRequestPermission` and `autoEnableBluetooth`

Options of scanning and connecting, both on by default. Before starting, Bonded asks for permission when it can
still be asked, and asks the user to turn Bluetooth on (Android only; iOS can't). Switch them off to ask at a
moment of your choosing.

### `BleError`

The one error type every failure uses. Its `code` means the same on both platforms (`timeout`, `notConnected`,
`pairingRequired`, `valueTooLong` and others; the full list is `BleErrorCode` in
[`src/api/BleError.ts`](../src/api/BleError.ts) and decision 5). It can also carry `platformCode`, `deviceId`,
`operation`, `attError`, and for `valueTooLong` the `length` and `maxLength`.

### Chunked write

`device.writeChunked()` cuts data longer than the [maximum write length](#maximum-write-length) into chunks of that
size and sends them in order as one operation, so no other read or write on the device gets between them. The
first chunk that fails stops the write; its error carries `chunkIndex` (counting from 0), `chunkCount` and
`sentLength`. Splitting is explicit by name, because only the app knows whether its firmware puts the chunks back
together (decision A13).

### Connect target

Which device `ble.connect()` connects to (`ConnectTarget`): with no argument, the remembered device; `{ id }`, that
device; `{ name, services }`, the first device a scan finds that matches.

### Connected devices

The devices that are connected, connecting or disconnecting: `ble.getConnectedDevices()` and
`useConnectedDevices()`.

### Connection state

Where a device's connection stands (`ConnectionState`): `disconnected`, `connecting`, `connected` or
`disconnecting`. `connected` means services are discovered and the device is ready. Read it from
`device.connectionState`, or follow it with `device.onConnectionStateChange()`, `ble.onConnectionStateChange()` or
`useDevice()`.

### Device handle

The object `ble.device(id)` returns (`Device`). It holds only the ID and reads the live state, so it never goes
stale and every handle for the same ID agrees. Create one wherever you need it. Not to be confused with an ATT
handle, a number inside the device that Bonded never shows.

### Device ID

How the platform addresses a device. On Android it is the device's Bluetooth address; on iOS an identifier the
system assigns, different on every phone. Both can change: Android devices can rotate their address, and iOS
changes identifiers after a network reset. Bonded then finds a remembered device again by its
[identity](#identity).

### `device.android`

The device's calls that work on Android only: `getBondState()` (`bonded`, `bonding`, `none`), `removeBond()` and
`onBondStateChange()`. On iOS they reject with `unsupported`, because iOS doesn't let apps see or remove bonds; the
user removes a device in Settings › Bluetooth. Android's settings live under `android` the same way, as in
`ble.configure({ android })` (decision 7).

### Diagnostic event

One recorded event (`DiagnosticEvent`): its `type` (such as `connecting`, `disconnected` or `gattOperation`), the
time `at`, whether the app was in the `foreground` or `background`, and when relevant the device ID, the `BleError`
code, the platform code and a short `detail`. Metadata only, never characteristic values.

### Diagnostics

A record of what Bonded did, for debugging and bug reports. Off by default; turn it on with
`ble.configure({ diagnostics: { enabled: true } })`. Bonded keeps the newest events (1,000 by default, set with
`capacity`) in a file in the app's private storage, so they survive the app being killed. GATT operations are
recorded only with `gatt: true`. Read them with `ble.getDiagnostics()`, `ble.onDiagnosticEvent()` or
`useDiagnosticEvents()`; `ble.clearDiagnostics()` deletes them, and so does turning diagnostics off (decision 8).

### Disconnect reason

Why a device disconnected (`DisconnectReason`): `requested` (the app asked), `outOfRange`, `deviceDisconnected`
(the device ended it), `bluetoothOff` or `failed` (anything else, including a failed or timed-out connection
attempt). Every reason but `requested` comes with a `BleError`.

### Forget

`device.forget()` disconnects the device, ends its subscriptions and stops remembering it. The system's pairing
stays; the user removes it in the system's Bluetooth settings.

### Hooks

React hooks, imported from `react-native-ble-bonded/hooks`, that re-render a component when something changes:
`useBluetoothState`, `useBluetoothPermission`, `useScan`, `useDevice`, `useRememberedDevices`,
`useConnectedDevices`, `useNotifications` and `useDiagnosticEvents`. Outside components, the matching `ble` methods
and listeners do the same.

### Identity

How a device was found: its advertised name, service UUIDs and manufacturer company ID (`DeviceIdentity`). Bonded
keeps it with a remembered device. When the platform says a remembered device's ID is unknown, Bonded scans for the
identity, connects to the one match and remembers it under the new ID; with several matches it rejects rather than
guess. A timeout never starts this search (decision A10).

### `lastDisconnect`

The last time a device disconnected and why: a `reason` and, for every reason but `requested`, an `error`. On
`device.lastDisconnect` and in each connection change.

### Listener

A callback Bonded calls with the current value at once, then with every change, such as `ble.onStateChange(cb)`. It
returns a [subscription](#subscription) to stop it. `ble.onDiagnosticEvent()` is the exception: it sends only new
events.

### Maximum write length

The most one write carries without being split: MTU − 3 bytes, agreed each time the phone and the device connect,
so it differs between devices and phones. `device.getMaxWriteLength()` reads it. A `write` with response still
takes up to 512 bytes, Bluetooth's limit, as a long write; a `write` without response longer than this rejects with
`valueTooLong`, giving both lengths. `write` never splits a value; [`writeChunked`](#chunked-write) does, when the
app asks (decisions A11 and A13).

### Operation timeout

How long a read or a write waits for the device: 10 seconds by default, set with `{ timeout }`. After a timeout the
caller stops waiting at once; the device's next operation still waits for the late answer, so no answer reaches the
wrong caller (decision A11). Connecting and scanning have their own timeouts, 30 seconds by default, where `0`
means no limit.

### Permission status

Whether the app may use Bluetooth (`BluetoothPermission`): `granted`, `denied` (can still ask), `blocked` (only the
system settings can change it) or `unavailable` (no Bluetooth Low Energy). Read with `ble.getPermissionStatus()`
and `useBluetoothPermission()`; ask with `ble.requestPermissions()`; open settings with `ble.openSettings()`.

### `platformCode`

The platform's own number for a failure (`CBError`, `CBATTError`, an Android GATT status or scan error), kept on a
`BleError` and a diagnostic event for debugging. Branch on `code`, not on it.

### Remembered device

A device Bonded keeps across app launches after it connected, until the app forgets it. The app stores no IDs:
`ble.connect()` connects the remembered device. `connect({ remember: false })` connects without remembering. List
them with `ble.getRememberedDevices()`, `useRememberedDevices()` and `device.isRemembered` (decision A10).

### Scan

A running search for devices: `ble.startScan(options, onDevice)` returns `{ stop(), stopped }`, and `useScan()`
scans while a component is mounted. One scan runs at a time; a new one replaces it. It stops after 30 seconds by
default. `stopped` resolves with a reason: `stopped`, `replaced`, `timeout` or `failed`. In the background a scan
needs a service filter.

### Scan filter

What a scan reports: `services` (service UUIDs the device advertises), `name` (exact, or `startsWith`, `endsWith`,
`includes`) and `filter`, a JavaScript function for anything else, such as a company ID or an RSSI threshold. All
given filters must match. `allowDuplicates` reports every advertisement instead of each device once;
`fullAdvertisement` adds every field the platform reports.

### Subscription

Two things share the name. A listener's subscription (`Subscription`) is the `{ remove() }` every listener returns.
A notification subscription is what `device.subscribe(service, characteristic, onValue)` creates: one per
characteristic (a second rejects with `alreadySubscribed`), it lasts until `remove()`, survives disconnects (Bonded
switches notifications back on after each reconnect) and ends when the app forgets the device, calling `onEnd`.
`onError` reports when notifications couldn't be switched back on. Values arrive in order, in batches about every
16 ms (decision A12).

### Trigger characteristic

The characteristic `device.createBond({ trigger })` reads to pair: one that needs an encrypted connection. iOS has
no call to pair and shows its dialog only when such a read asks for encryption; Android bonds first, then reads it
to confirm. So `createBond` resolves only once encrypted reads work, on both platforms (decision 7).

### Value

Bonded passes characteristic values as `ArrayBuffer`. Writes also accept a `Uint8Array`.
