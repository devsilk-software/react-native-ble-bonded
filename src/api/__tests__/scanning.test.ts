import { ble, BleError } from '../ble'
import { toBleError } from '../BleError'
import { toNativeOptions } from '../scanning'
import { BluetoothState } from '../../specs/BluetoothState.nitro'
import { Permissions } from '../../specs/Permissions.nitro'
import { Scanner, type NativeScannedDevice, type NativeScanStop } from '../../specs/Scanner.nitro'

jest.mock('react-native', () => ({ Linking: { openSettings: jest.fn() } }))
jest.mock('../../specs/BluetoothState.nitro', () => ({
  BluetoothState: { getState: jest.fn(), requestEnable: jest.fn(), addStateListener: jest.fn(), removeStateListener: jest.fn() },
}))
jest.mock('../../specs/Permissions.nitro', () => ({
  Permissions: { getStatus: jest.fn(), request: jest.fn(), addListener: jest.fn(), removeListener: jest.fn() },
}))
jest.mock('../../specs/Gatt.nitro', () => ({ Gatt: { read: jest.fn(), write: jest.fn() } }))
jest.mock('../../specs/Diagnostics.nitro', () => ({ Diagnostics: {} }))
jest.mock('../../specs/Connection.nitro', () => ({
  Connection: { addListener: jest.fn().mockResolvedValue(1), getConnections: jest.fn().mockResolvedValue([]), configureAndroid: jest.fn() },
}))
jest.mock('../../specs/Scanner.nitro', () => ({ Scanner: { start: jest.fn(), stop: jest.fn() } }))

const nativeState = jest.mocked(BluetoothState)
const nativePermissions = jest.mocked(Permissions)
const nativeScanner = jest.mocked(Scanner)

/** Makes the native scanner start and hands the test its callbacks. */
function startsScanning() {
  const callbacks: { onDevice?: (device: NativeScannedDevice) => void; onStop?: (stop: NativeScanStop) => void } = {}
  nativeScanner.start.mockImplementation(async (_options, onDevice, onStop) => {
    callbacks.onDevice = onDevice
    callbacks.onStop = onStop
    return 4
  })
  return callbacks
}

beforeEach(() => {
  jest.clearAllMocks()
  nativePermissions.getStatus.mockResolvedValue('granted')
  nativeState.getState.mockResolvedValue('poweredOn')
})

describe('Scanning', () => {
  it('asks for permission and to turn Bluetooth on before scanning', async () => {
    nativePermissions.getStatus.mockResolvedValue('denied')
    nativeState.getState.mockResolvedValue('poweredOff')
    startsScanning()

    await ble.startScan({}, jest.fn())

    expect(nativePermissions.request).toHaveBeenCalledTimes(1)
    expect(nativeState.requestEnable).toHaveBeenCalledTimes(1)
    expect(nativeScanner.start).toHaveBeenCalledTimes(1)
  })

  it('asks for neither when the app has switched that off', async () => {
    nativePermissions.getStatus.mockResolvedValue('denied')
    nativeState.getState.mockResolvedValue('poweredOff')
    startsScanning()

    await ble.startScan({ autoRequestPermission: false, autoEnableBluetooth: false }, jest.fn())

    expect(nativePermissions.request).not.toHaveBeenCalled()
    expect(nativeState.requestEnable).not.toHaveBeenCalled()
  })

  it('rejects with a BleError when the phone can not scan', async () => {
    nativeScanner.start.mockRejectedValue(new Error('Scanner.start(...): [bluetoothOff] Bluetooth is off'))

    const failure = ble.startScan({ autoEnableBluetooth: false }, jest.fn())

    await expect(failure).rejects.toBeInstanceOf(BleError)
    await expect(failure).rejects.toMatchObject({ code: 'bluetoothOff', message: 'Bluetooth is off', operation: 'scan' })
  })

  it('passes only the devices the JavaScript filter accepts', async () => {
    const native = startsScanning()
    const onDevice = jest.fn()

    await ble.startScan({ filter: (device) => device.rssi > -70 }, onDevice)
    native.onDevice?.({ id: 'A', rssi: -60, serviceUuids: [] })
    native.onDevice?.({ id: 'B', rssi: -90, serviceUuids: [] })

    expect(onDevice.mock.calls.map(([device]) => device.id)).toEqual(['A'])
  })

  it('lets the filter see every report, then reports each accepted device once', async () => {
    const native = startsScanning()
    const onDevice = jest.fn()

    await ble.startScan({ filter: (device) => device.rssi > -70 }, onDevice)
    native.onDevice?.({ id: 'A', rssi: -90, serviceUuids: [] })
    native.onDevice?.({ id: 'A', rssi: -60, serviceUuids: [] })
    native.onDevice?.({ id: 'A', rssi: -55, serviceUuids: [] })

    expect(nativeScanner.start.mock.calls[0]?.[0].allowDuplicates).toBe(true)
    expect(onDevice.mock.calls.map(([device]) => device.rssi)).toEqual([-60])
  })

  it('turns native service data into a record by UUID', async () => {
    const native = startsScanning()
    const onDevice = jest.fn()
    const data = new ArrayBuffer(2)

    await ble.startScan({ fullAdvertisement: true }, onDevice)
    native.onDevice?.({ id: 'A', rssi: -60, serviceUuids: [], serviceData: [{ uuid: 'feaa', data }], txPowerLevel: 4 })

    expect(onDevice).toHaveBeenCalledWith({ id: 'A', rssi: -60, serviceUuids: [], serviceData: { feaa: data }, txPowerLevel: 4 })
  })

  it('resolves stopped with the reason and the error a scan ended with', async () => {
    const native = startsScanning()

    const scan = await ble.startScan({}, jest.fn())
    native.onStop?.({ reason: 'failed', errorCode: 'bluetoothOff', errorMessage: 'Bluetooth is off' })

    const stop = await scan.stopped
    expect(stop.reason).toBe('failed')
    expect(stop.error).toBeInstanceOf(BleError)
    expect(stop.error?.code).toBe('bluetoothOff')
  })

  it('stops the native scan it started', async () => {
    startsScanning()

    const scan = await ble.startScan({}, jest.fn())
    scan.stop()

    expect(nativeScanner.stop).toHaveBeenCalledWith(4)
  })

  it('passes the name filter and timeout to native code', () => {
    expect(toNativeOptions({ name: 'Thermo-1' })).toMatchObject({ nameEquals: 'Thermo-1', timeoutMs: 30_000 })
    expect(toNativeOptions({ name: { startsWith: 'Thermo-', endsWith: 'A' }, timeout: 0 })).toMatchObject({
      nameStartsWith: 'Thermo-',
      nameEndsWith: 'A',
      nameIncludes: undefined,
      timeoutMs: 0,
    })
    expect(() => toNativeOptions({ timeout: -1 })).toThrow(RangeError)
    expect(() => toNativeOptions({ timeout: Number.NaN })).toThrow(RangeError)
  })

  it('asks native code to turn Bluetooth on', async () => {
    nativeState.requestEnable.mockResolvedValue('poweredOn')

    await expect(ble.requestBluetoothOn()).resolves.toBe('poweredOn')
  })
})

describe('BleError from native code', () => {
  it('reads the code and the platform code from the message', () => {
    const error = toBleError(new Error('[unknown:6] Android refused to scan (error 6)'), 'scan')

    expect(error).toMatchObject({ code: 'unknown', platformCode: 6, message: 'Android refused to scan (error 6)', operation: 'scan' })
  })

  it('leaves other errors unchanged', () => {
    const error = new Error('something else')

    expect(toBleError(error)).toBe(error)
  })
})
