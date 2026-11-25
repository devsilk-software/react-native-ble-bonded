import { ble } from '../ble'
import { Bonding } from '../../specs/Bonding.nitro'
import type { NativeConnectionChange } from '../../specs/Connection.nitro'

const platform = { OS: 'android' }
jest.mock('react-native', () => ({ Linking: { openSettings: jest.fn() }, Platform: platform }))
jest.mock('../../specs/BluetoothState.nitro', () => ({ BluetoothState: {} }))
jest.mock('../../specs/Permissions.nitro', () => ({ Permissions: {} }))
jest.mock('../../specs/Diagnostics.nitro', () => ({ Diagnostics: {} }))
jest.mock('../../specs/Scanner.nitro', () => ({ Scanner: {} }))
jest.mock('../../specs/Gatt.nitro', () => ({ Gatt: {} }))
jest.mock('../../specs/Bonding.nitro', () => ({
  Bonding: {
    createBond: jest.fn(),
    getBondState: jest.fn(),
    removeBond: jest.fn(),
    addBondStateListener: jest.fn(),
    removeBondStateListener: jest.fn(),
  },
}))

let report: (change: Omit<NativeConnectionChange, 'isRemembered'>) => void = () => {}
jest.mock('../../specs/Connection.nitro', () => ({
  Connection: {
    addListener: jest.fn((onChange: (change: NativeConnectionChange) => void) => {
      report = (change) => onChange({ isRemembered: false, ...change })
      return Promise.resolve(1)
    }),
    getConnections: jest.fn().mockResolvedValue([]),
  },
}))

const native = jest.mocked(Bonding)
const service = 'b0ed0001-0000-4000-8000-00000000b0ed'
const secure = 'b0ed0006-0000-4000-8000-00000000b0ed'

beforeEach(async () => {
  jest.clearAllMocks()
  platform.OS = 'android'
  await ble.getConnectedDevices()
  report({ id: 'A', state: 'connected' })
})

describe('Bonding', () => {
  it('pairs with the trigger the app gives', async () => {
    native.createBond.mockResolvedValue(undefined)

    await ble.device('A').createBond({ trigger: { service, characteristic: secure } })

    expect(native.createBond).toHaveBeenCalledWith('A', service, secure)
  })

  it('names a declined pairing with pairingRequired', async () => {
    native.createBond.mockRejectedValue(new Error('[pairingRequired] Pairing was declined or failed.'))

    await expect(ble.device('A').createBond({ trigger: { service, characteristic: secure } })).rejects.toMatchObject({
      code: 'pairingRequired',
      operation: 'createBond',
      deviceId: 'A',
    })
  })

  it('rejects createBond at once with notConnected when the device is not connected', async () => {
    report({ id: 'A', state: 'disconnected' })

    await expect(ble.device('A').createBond({ trigger: { service, characteristic: secure } })).rejects.toMatchObject({
      code: 'notConnected',
      operation: 'createBond',
      message: 'The device is disconnected; connect it before you pair.',
    })
    expect(native.createBond).not.toHaveBeenCalled()
  })

  it('reads and removes the Android bond without a connection', async () => {
    report({ id: 'A', state: 'disconnected' })
    native.getBondState.mockResolvedValue('bonded')
    native.removeBond.mockResolvedValue(undefined)

    await expect(ble.device('A').android.getBondState()).resolves.toBe('bonded')
    await ble.device('A').android.removeBond()
    expect(native.removeBond).toHaveBeenCalledWith('A')
  })

  it('reports bond changes until removed, and stops the native listener once', async () => {
    let onChange: (state: 'bonded' | 'bonding' | 'none') => void = () => {}
    native.addBondStateListener.mockImplementation((_id, callback) => {
      onChange = callback
      return Promise.resolve(7)
    })
    const states: string[] = []

    const subscription = ble.device('A').android.onBondStateChange((state) => states.push(state))
    await Promise.resolve()
    onChange('none')
    onChange('bonded')
    subscription.remove()
    subscription.remove()
    onChange('none')

    expect(states).toEqual(['none', 'bonded'])
    expect(native.removeBondStateListener).toHaveBeenCalledTimes(1)
    expect(native.removeBondStateListener).toHaveBeenCalledWith(7)
  })

  it('throws unsupported for the bond listener on iOS, which has no bond state', () => {
    platform.OS = 'ios'

    expect(() => ble.device('A').android.onBondStateChange(() => {})).toThrow(
      expect.objectContaining({ code: 'unsupported', operation: 'onBondStateChange' })
    )
    expect(native.addBondStateListener).not.toHaveBeenCalled()
  })
})
