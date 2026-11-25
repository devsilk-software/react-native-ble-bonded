import { ble, BleError } from '../ble'
import { Connection, type NativeConnectionChange } from '../../specs/Connection.nitro'
import { Permissions } from '../../specs/Permissions.nitro'
import { BluetoothState } from '../../specs/BluetoothState.nitro'

jest.mock('react-native', () => ({ Linking: { openSettings: jest.fn() } }))
jest.mock('../../specs/BluetoothState.nitro', () => ({
  BluetoothState: { getState: jest.fn(), requestEnable: jest.fn(), addStateListener: jest.fn(), removeStateListener: jest.fn() },
}))
jest.mock('../../specs/Permissions.nitro', () => ({
  Permissions: { getStatus: jest.fn(), request: jest.fn(), addListener: jest.fn(), removeListener: jest.fn() },
}))
jest.mock('../../specs/Gatt.nitro', () => ({ Gatt: { read: jest.fn(), write: jest.fn(), subscribe: jest.fn(), unsubscribe: jest.fn() } }))
jest.mock('../../specs/Diagnostics.nitro', () => ({ Diagnostics: {} }))
jest.mock('../../specs/Scanner.nitro', () => ({ Scanner: {} }))

// The store's one native listener is registered once, when the module first needs it; keep its callback.
type Change = Omit<NativeConnectionChange, 'isRemembered'> & { isRemembered?: boolean }
let report: (change: Change) => void = () => {}
jest.mock('../../specs/Connection.nitro', () => ({
  Connection: {
    connect: jest.fn(),
    disconnect: jest.fn(),
    getServices: jest.fn(),
    addListener: jest.fn((onChange: (change: NativeConnectionChange) => void) => {
      report = (change) => onChange({ isRemembered: false, ...change })
      return Promise.resolve(1)
    }),
    forget: jest.fn(),
    getConnections: jest.fn().mockResolvedValue([]),
    removeListener: jest.fn(),
    configureAndroid: jest.fn(),
  },
}))

const native = jest.mocked(Connection)

beforeEach(() => {
  native.connect.mockReset()
  native.getServices.mockReset()
  native.configureAndroid.mockClear()
  jest.mocked(Permissions.getStatus).mockResolvedValue('granted')
  jest.mocked(BluetoothState.getState).mockResolvedValue('poweredOn')
})

describe('Connections', () => {
  it('reads the live state through any handle for the same ID', async () => {
    await ble.getConnectedDevices()
    report({ id: 'A', name: 'Thermo-1', state: 'connected' })

    const first = ble.device('A')
    const second = ble.device('A')

    expect(first.connectionState).toBe('connected')
    expect(second.name).toBe('Thermo-1')
    expect(ble.device('unknown').connectionState).toBe('disconnected')
  })

  it('returns the same state object until something changes, so React can compare it', async () => {
    await ble.getConnectedDevices()
    report({ id: 'H', state: 'connecting' })

    const { connectionStore } = await import('../connections')
    connectionStore.rememberName('I', 'Bonded Test')

    expect(connectionStore.get('H')).toBe(connectionStore.get('H'))
    expect(connectionStore.get('I')).toBe(connectionStore.get('I'))
    expect(connectionStore.get('I')?.name).toBe('Bonded Test')
    expect(connectionStore.all()).toBe(connectionStore.all())
  })

  it('reports the last disconnection with its BleError', async () => {
    await ble.getConnectedDevices()
    report({
      id: 'B',
      state: 'disconnected',
      reason: 'outOfRange',
      errorCode: 'disconnected',
      errorMessage: 'Out of range',
      platformCode: 8,
    })

    const lastDisconnect = ble.device('B').lastDisconnect
    expect(lastDisconnect?.reason).toBe('outOfRange')
    expect(lastDisconnect?.error).toBeInstanceOf(BleError)
    expect(lastDisconnect?.error?.platformCode).toBe(8)
  })

  it("calls a device's listener with its current state first, then only its own changes", async () => {
    await ble.getConnectedDevices()
    report({ id: 'C', state: 'connecting' })
    const onChange = jest.fn()

    const subscription = ble.device('C').onConnectionStateChange(onChange)
    report({ id: 'D', state: 'connected' })
    report({ id: 'C', state: 'connected' })
    subscription.remove()
    report({ id: 'C', state: 'disconnected', reason: 'requested' })

    expect(onChange.mock.calls.map(([change]) => change.state)).toEqual(['connecting', 'connected'])
  })

  it('tags every change with its device in the listener for all devices', async () => {
    await ble.getConnectedDevices()
    const onChange = jest.fn()

    const subscription = ble.onConnectionStateChange(onChange)
    report({ id: 'E', state: 'connecting' })
    subscription.remove()

    expect(onChange).toHaveBeenLastCalledWith('E', { state: 'connecting', lastDisconnect: undefined })
  })

  it('lists the devices that are connected, connecting or disconnecting', async () => {
    await ble.getConnectedDevices()
    report({ id: 'F', state: 'connected' })
    report({ id: 'G', state: 'disconnected' })

    const ids = (await ble.getConnectedDevices()).map((device) => device.id)

    expect(ids).toContain('F')
    expect(ids).not.toContain('G')
  })

  it('connects with the timeout and Android workarounds, asking for permission first', async () => {
    jest.mocked(Permissions.getStatus).mockResolvedValue('denied')
    native.connect.mockResolvedValue(undefined)

    await ble.device('A').connect({ timeout: 0, android: { discoveryDelay: 0 } })

    expect(Permissions.request).toHaveBeenCalled()
    expect(native.connect).toHaveBeenCalledWith(
      'A',
      expect.objectContaining({ timeoutMs: 0, android: expect.objectContaining({ discoveryDelayMs: 0 }) })
    )
  })

  it('connects with a 30 second timeout by default', async () => {
    native.connect.mockResolvedValue(undefined)

    await ble.device('A').connect()

    expect(native.connect).toHaveBeenCalledWith('A', { timeoutMs: 30_000, remember: true, identity: undefined, android: undefined })
  })

  it('rejects with a BleError when the connection fails', async () => {
    native.connect.mockRejectedValue(new Error("[timeout] Couldn't connect within 30 s"))

    await expect(ble.device('A').connect()).rejects.toMatchObject({ code: 'timeout', operation: 'connect' })
  })

  it('refuses a timeout that is not 0 or more milliseconds', async () => {
    await expect(ble.device('A').connect({ timeout: -1 })).rejects.toThrow(RangeError)
    expect(native.connect).not.toHaveBeenCalled()
  })

  it('lists descriptors only when asked', async () => {
    native.getServices.mockResolvedValue([])

    await ble.device('A').getServices()
    await ble.device('A').getServices({ descriptors: true })

    expect(native.getServices.mock.calls).toEqual([
      ['A', false],
      ['A', true],
    ])
  })

  it('rejects getServices with notConnected while the device is not connected', async () => {
    native.getServices.mockRejectedValue(new Error("[notConnected] The device isn't connected"))

    await expect(ble.device('A').getServices()).rejects.toMatchObject({ code: 'notConnected' })
  })

  it('passes the Android workarounds for every connection to native code', async () => {
    await ble.configure({ android: { refreshServiceCache: 'always', waitForBonding: false } })

    expect(native.configureAndroid).toHaveBeenCalledWith(expect.objectContaining({ refreshServiceCache: 'always', waitForBonding: false }))
  })
})

describe('Connection listener registration', () => {
  it('tries again after a failed registration instead of failing for good', async () => {
    const { connectionStore } = await import('../connections')
    // Simulate a store whose first registration fails, by replacing its state.
    const store = connectionStore as unknown as { registration: Promise<void> | undefined }
    const saved = store.registration
    store.registration = undefined
    native.addListener.mockRejectedValueOnce(new Error('not ready'))

    await expect(connectionStore.start()).rejects.toThrow('not ready')
    await expect(connectionStore.start()).resolves.toBeUndefined()

    store.registration = saved
  })
})
