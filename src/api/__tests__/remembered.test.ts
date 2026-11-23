import { ble, BleError } from '../ble'
import { Connection, type NativeConnectionChange } from '../../specs/Connection.nitro'
import { Scanner, type NativeScannedDevice } from '../../specs/Scanner.nitro'
import { Permissions } from '../../specs/Permissions.nitro'
import { BluetoothState } from '../../specs/BluetoothState.nitro'

jest.mock('react-native', () => ({ Linking: { openSettings: jest.fn() } }))
jest.mock('../../specs/BluetoothState.nitro', () => ({
  BluetoothState: { getState: jest.fn(), requestEnable: jest.fn(), addStateListener: jest.fn(), removeStateListener: jest.fn() },
}))
jest.mock('../../specs/Permissions.nitro', () => ({
  Permissions: { getStatus: jest.fn(), request: jest.fn(), addListener: jest.fn(), removeListener: jest.fn() },
}))
jest.mock('../../specs/Diagnostics.nitro', () => ({ Diagnostics: {} }))
jest.mock('../../specs/Scanner.nitro', () => ({ Scanner: { start: jest.fn(), stop: jest.fn() } }))

type Change = Omit<NativeConnectionChange, 'isRemembered'> & { isRemembered?: boolean }
let report: (change: Change) => void = () => {}
jest.mock('../../specs/Connection.nitro', () => ({
  Connection: {
    connect: jest.fn(),
    disconnect: jest.fn(),
    forget: jest.fn(),
    getConnections: jest.fn().mockResolvedValue([]),
    getServices: jest.fn(),
    addListener: jest.fn((onChange: (change: NativeConnectionChange) => void) => {
      report = (change) => onChange({ isRemembered: false, ...change })
      return Promise.resolve(1)
    }),
    removeListener: jest.fn(),
    configureAndroid: jest.fn(),
  },
}))

const native = jest.mocked(Connection)
const scanner = jest.mocked(Scanner)

/** Devices the native side remembers, as it reports them after a relaunch. */
function remembered(
  id: string,
  lastConnectedAt: number,
  identity = { name: `Thermo-${id}`, services: ['b0ed0001-0000-4000-8000-00000000b0ed'] }
) {
  report({ id, name: identity.name, state: 'disconnected', isRemembered: true, identity, lastConnectedAt })
}

/** The native scanner reports these devices once a scan starts, then keeps scanning. */
function advertising(devices: NativeScannedDevice[]) {
  scanner.start.mockImplementation(async (_options, onDevice) => {
    setTimeout(() => devices.forEach(onDevice), 0)
    return 7
  })
}

const knownIds = ['A', 'B', 'C', 'D', 'E']

beforeEach(async () => {
  jest.clearAllMocks()
  jest.mocked(Permissions.getStatus).mockResolvedValue('granted')
  jest.mocked(BluetoothState.getState).mockResolvedValue('poweredOn')
  await ble.getRememberedDevices()
  knownIds.forEach((id) => report({ id, state: 'disconnected' }))
})

describe('Remembered devices', () => {
  it('lists remembered devices, most recently connected first', async () => {
    remembered('A', 1000)
    remembered('B', 2000)

    const devices = await ble.getRememberedDevices()

    expect(devices.map((device) => device.id)).toEqual(['B', 'A'])
    expect(ble.device('A').isRemembered).toBe(true)
    expect(ble.device('C').isRemembered).toBe(false)
  })

  it('gives the one remembered device without an ID, and refuses to guess between several', async () => {
    expect(ble.device()).toBeUndefined()

    remembered('A', 1000)
    expect(ble.device()?.id).toBe('A')

    remembered('B', 2000)
    expect(() => ble.device()).toThrow('Several devices are remembered')
  })

  it('reconnects the remembered device when connect has no arguments', async () => {
    remembered('A', 1000)
    native.connect.mockResolvedValue(undefined)

    const device = await ble.connect()

    expect(device.id).toBe('A')
    expect(native.connect).toHaveBeenCalledWith('A', expect.objectContaining({ remember: true }))
  })

  it('rejects with deviceNotFound when no device is remembered', async () => {
    await expect(ble.connect()).rejects.toMatchObject({ code: 'deviceNotFound' })
  })

  it('connects to the first device that matches a filter, remembering how it was found', async () => {
    advertising([{ id: 'C', name: 'Thermo-C', rssi: -50, serviceUuids: ['b0ed0001-0000-4000-8000-00000000b0ed'] }])
    native.connect.mockResolvedValue(undefined)

    const device = await ble.connect({ name: { startsWith: 'Thermo-' } })

    expect(device.id).toBe('C')
    expect(scanner.stop).toHaveBeenCalledWith(7)
    expect(native.connect).toHaveBeenCalledWith(
      'C',
      expect.objectContaining({ identity: { name: 'Thermo-C', services: ['b0ed0001-0000-4000-8000-00000000b0ed'], companyId: undefined } })
    )
  })

  it("doesn't remember a device when asked not to", async () => {
    native.connect.mockResolvedValue(undefined)

    await ble.connect({ id: 'C' }, { remember: false })

    expect(native.connect).toHaveBeenCalledWith('C', expect.objectContaining({ remember: false }))
  })

  it('finds a remembered device again under a new ID, and forgets the old one', async () => {
    remembered('A', 1000)
    native.connect.mockImplementation(async (id) => {
      if (id === 'A') throw new Error('[deviceNotFound] No device with this ID is known')
    })
    advertising([{ id: 'D', name: 'Thermo-A', rssi: -50, serviceUuids: ['b0ed0001-0000-4000-8000-00000000b0ed'] }])
    native.forget.mockImplementation(async (id) => report({ id, state: 'disconnected', isRemembered: false }))

    const device = await ble.connect()

    expect(device.id).toBe('D')
    expect(native.connect).toHaveBeenLastCalledWith('D', expect.anything())
    expect(native.forget).toHaveBeenCalledWith('A')
  }, 10_000)

  it("doesn't guess when several devices match a remembered device", async () => {
    remembered('A', 1000)
    native.connect.mockRejectedValue(new Error('[deviceNotFound] No device with this ID is known'))
    advertising([
      { id: 'D', name: 'Thermo-A', rssi: -50, serviceUuids: ['b0ed0001-0000-4000-8000-00000000b0ed'] },
      { id: 'E', name: 'Thermo-A', rssi: -60, serviceUuids: ['b0ed0001-0000-4000-8000-00000000b0ed'] },
    ])

    const failure = ble.connect()

    await expect(failure).rejects.toBeInstanceOf(BleError)
    await expect(failure).rejects.toMatchObject({ code: 'deviceNotFound', message: expect.stringContaining('Several devices') })
    expect(native.connect).toHaveBeenCalledTimes(1)
  }, 10_000)

  it("doesn't look for another device when the remembered one only timed out", async () => {
    remembered('A', 1000)
    native.connect.mockRejectedValue(new Error("[timeout] Couldn't connect within 30 s"))

    await expect(ble.connect()).rejects.toMatchObject({ code: 'timeout' })
    expect(scanner.start).not.toHaveBeenCalled()
    expect(native.forget).not.toHaveBeenCalled()
  })

  it('keeps the old ID when a device found again should not be remembered', async () => {
    remembered('A', 1000)
    native.connect.mockImplementation(async (id) => {
      if (id === 'A') throw new Error('[deviceNotFound] No device with this ID is known')
    })
    advertising([{ id: 'D', name: 'Thermo-A', rssi: -50, serviceUuids: ['b0ed0001-0000-4000-8000-00000000b0ed'] }])

    const device = await ble.connect({ id: 'A' }, { remember: false })

    expect(device.id).toBe('D')
    expect(native.forget).not.toHaveBeenCalled()
  }, 10_000)

  it('stops finding a device again when the app disconnects it meanwhile', async () => {
    remembered('A', 1000)
    native.connect.mockImplementation(async (id) => {
      if (id === 'A') throw new Error('[deviceNotFound] No device with this ID is known')
    })
    advertising([{ id: 'D', name: 'Thermo-A', rssi: -50, serviceUuids: ['b0ed0001-0000-4000-8000-00000000b0ed'] }])

    const connecting = ble.connect()
    await new Promise<void>((resolve) => setTimeout(() => resolve(), 100))
    await ble.device('A').disconnect()

    await expect(connecting).rejects.toMatchObject({ code: 'cancelled' })
    expect(native.connect).not.toHaveBeenCalledWith('D', expect.anything())
  }, 10_000)

  it('refuses a filter with neither a name nor services', async () => {
    await expect(ble.connect({})).rejects.toThrow(RangeError)
    expect(scanner.start).not.toHaveBeenCalled()
  })

  it('gives the scan and the connection one timeout together', async () => {
    advertising([{ id: 'C', name: 'Thermo-C', rssi: -50, serviceUuids: [] }])
    native.connect.mockResolvedValue(undefined)

    await ble.connect({ name: 'Thermo-C' }, { timeout: 5_000 })

    expect(scanner.start.mock.calls[0]?.[0].timeoutMs).toBe(5_000)
    const connectTimeout = native.connect.mock.calls[0]?.[1].timeoutMs ?? 0
    expect(connectTimeout).toBeGreaterThan(0)
    expect(connectTimeout).toBeLessThanOrEqual(5_000)
  })

  it('knows the remembered devices as soon as it has started, before the listener reports', async () => {
    const { connectionStore } = await import('../connections')
    const store = connectionStore as unknown as { registration: Promise<void> | undefined; devices: Map<string, unknown> }
    store.devices.delete('E')
    store.registration = undefined
    native.addListener.mockResolvedValueOnce(2)
    native.getConnections.mockResolvedValueOnce([
      { id: 'E', name: 'Thermo-E', state: 'disconnected', isRemembered: true, identity: { services: [] }, lastConnectedAt: 3000 },
    ])

    const devices = await ble.getRememberedDevices()

    expect(devices.map((device) => device.id)).toContain('E')
  })

  it('forgets a device through native code', async () => {
    remembered('A', 1000)

    await ble.device('A').forget()

    expect(native.forget).toHaveBeenCalledWith('A')
  })
})
