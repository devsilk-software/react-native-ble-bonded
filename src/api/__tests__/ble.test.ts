import { Linking } from 'react-native'
import { ble } from '../ble'
import { BluetoothState } from '../../specs/BluetoothState.nitro'
import { Permissions } from '../../specs/Permissions.nitro'

jest.mock('react-native', () => ({ Linking: { openSettings: jest.fn().mockResolvedValue(undefined) } }))
jest.mock('../../specs/BluetoothState.nitro', () => ({
  BluetoothState: { getState: jest.fn(), addStateListener: jest.fn(), removeStateListener: jest.fn() },
}))
jest.mock('../../specs/Permissions.nitro', () => ({
  Permissions: { getStatus: jest.fn(), request: jest.fn(), addListener: jest.fn(), removeListener: jest.fn() },
}))

const nativeState = jest.mocked(BluetoothState)
const nativePermissions = jest.mocked(Permissions)

beforeEach(() => jest.clearAllMocks())

describe('Bluetooth state', () => {
  it.each(['unknown', 'resetting', 'unsupported', 'unauthorized', 'poweredOff', 'poweredOn'] as const)(
    "reports the radio's current state (%s)",
    async (state) => {
      nativeState.getState.mockResolvedValue(state)

      await expect(ble.getState()).resolves.toBe(state)
    }
  )

  it('stops the native listener once, however often remove() is called', () => {
    nativeState.addStateListener.mockReturnValue(7)
    const onChange = jest.fn()

    const subscription = ble.onStateChange(onChange)
    subscription.remove()
    subscription.remove()

    expect(nativeState.addStateListener).toHaveBeenCalledWith(onChange)
    expect(nativeState.removeStateListener).toHaveBeenCalledTimes(1)
    expect(nativeState.removeStateListener).toHaveBeenCalledWith(7)
  })
})

describe('Permissions', () => {
  it.each(['unavailable', 'denied', 'granted', 'blocked'] as const)('reports the permission status without asking (%s)', async (status) => {
    nativePermissions.getStatus.mockResolvedValue(status)

    await expect(ble.getPermissionStatus()).resolves.toBe(status)
    expect(nativePermissions.request).not.toHaveBeenCalled()
  })

  it.each(['denied', 'granted', 'blocked'] as const)('reports the status the user chose after asking (%s)', async (status) => {
    nativePermissions.request.mockResolvedValue(status)

    await expect(ble.requestPermissions()).resolves.toBe(status)
  })

  it('stops the native permission listener once, however often remove() is called', () => {
    nativePermissions.addListener.mockReturnValue(3)

    const subscription = ble.onPermissionChange(jest.fn())
    subscription.remove()
    subscription.remove()

    expect(nativePermissions.removeListener).toHaveBeenCalledTimes(1)
    expect(nativePermissions.removeListener).toHaveBeenCalledWith(3)
  })

  it("opens the app's system settings", async () => {
    await ble.openSettings()

    expect(Linking.openSettings).toHaveBeenCalledTimes(1)
  })
})
