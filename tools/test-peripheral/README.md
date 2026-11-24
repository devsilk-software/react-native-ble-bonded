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
| Write | `b0ed0003-0000-4000-8000-00000000b0ed` | prints every write, with or without response |
| Notify | `b0ed0004-0000-4000-8000-00000000b0ed` | every second while subscribed, the seconds since the tool started; it never resets |
