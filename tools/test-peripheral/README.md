# Test peripheral

A Bluetooth Low Energy peripheral on the Mac, for trying Bonded's example app on a phone without other hardware.

```sh
cd tools/test-peripheral
swift run TestPeripheral            # advertises "Bonded Test"
swift run TestPeripheral --name Thermo-1
```

macOS asks once whether the terminal may use Bluetooth. Type `quit` and Enter to stop advertising and quit.
Quitting removes the service, but macOS may keep the radio link up, so the phone doesn't always see a
disconnection; to test one, turn Bluetooth off on the phone or move it out of range. To test a connection that
waits for the device, quit, tap Connect on the phone, then start the tool again.

| Characteristic | UUID | Does |
|---|---|---|
| Service | `b0ed0001-0000-4000-8000-00000000b0ed` | advertised, so a scan can filter on it |
| Read | `b0ed0002-0000-4000-8000-00000000b0ed` | a counter that grows on every read |
| Write | `b0ed0003-0000-4000-8000-00000000b0ed` | prints every write, with or without response; for the example app's chunked write (0, 1, 2, …) it prints how many bytes arrived in order, or `OUT OF ORDER` |
| Secure | `b0ed0006-0000-4000-8000-00000000b0ed` | needs an encrypted connection; reads `bonded` once paired. It shows `device.createBond()` failing with `pairingRequired`, not pairing: see below |
| Notify | `b0ed0004-0000-4000-8000-00000000b0ed` | every second while subscribed, the seconds since the tool started; it never resets. Its user description descriptor (`2901`) reads `Counter` and is read-only |

## Not for bonding

This tool can't test pairing or bonding. Use a peripheral that pairs for that: an nRF52 board, or an Android phone
running nRF Connect's GATT server with an encrypted characteristic.

What happens with it: when an iPhone reads the Secure characteristic, the read fails at once with ATT error 5
("insufficient authentication") and no pairing dialog appears on either device. An Android phone's reads didn't
reach the tool at all. The characteristic is set up as Apple documents for encryption
([`readEncryptionRequired`](https://developer.apple.com/documentation/corebluetooth/cbattributepermissions/readencryptionrequired)),
and iOS pairs only when the app touches a characteristic that needs encryption, which this one does
([InfiniTime #920](https://github.com/InfiniTimeOrg/InfiniTime/issues/920)).

Why: Apple doesn't document how a Mac pairs as a peripheral. An Apple Developer Forums thread reports the same
symptom: a Mac peripheral run outside a regular app shows no pairing request, and the central gets "Authentication
is insufficient" (error 5). A Core Bluetooth engineer replies there that "it is possible that the pairing process in
different app contexts are behaving differently", and the thread ends without a fix
([thread 808121](https://developer.apple.com/forums/thread/808121)). This tool runs as a command-line program from
`swift run`, not as an app, which may be why; it isn't confirmed.

