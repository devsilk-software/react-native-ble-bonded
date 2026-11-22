import { Linking } from 'react-native'
import { ble } from '../ble'
import { BluetoothState } from '../../specs/BluetoothState.nitro'
import { Permissions } from '../../specs/Permissions.nitro'
import { Diagnostics } from '../../specs/Diagnostics.nitro'

jest.mock('react-native', () => ({ Linking: { openSettings: jest.fn().mockResolvedValue(undefined) } }))
jest.mock('../../specs/BluetoothState.nitro', () => ({
  BluetoothState: { getState: jest.fn(), addStateListener: jest.fn(), removeStateListener: jest.fn() },
}))
jest.mock('../../specs/Permissions.nitro', () => ({
  Permissions: { getStatus: jest.fn(), request: jest.fn(), addListener: jest.fn(), removeListener: jest.fn() },
}))

jest.mock('../../specs/Diagnostics.nitro', () => ({
  Diagnostics: { configure: jest.fn(), getEvents: jest.fn(), clear: jest.fn(), addListener: jest.fn(), removeListener: jest.fn() },
}))

const nativeDiagnostics = jest.mocked(Diagnostics)
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

describe('Diagnostics', () => {
  it('passes the diagnostics settings to native code', async () => {
    await ble.configure({ diagnostics: { enabled: true, capacity: 5000 } })

    expect(nativeDiagnostics.configure).toHaveBeenCalledWith({ enabled: true, capacity: 5000 })
  })

  it.each([0, -1, 2.5, Number.NaN])('refuses a capacity that is not a positive whole number (%s)', async (capacity) => {
    await expect(ble.configure({ diagnostics: { capacity } })).rejects.toThrow(RangeError)
    expect(nativeDiagnostics.configure).not.toHaveBeenCalled()
  })

  it('returns the events at or after a given time', async () => {
    const event = { type: 'stateChanged', at: 1002, appState: 'foreground', detail: 'poweredOn' } as const
    nativeDiagnostics.getEvents.mockResolvedValue([event])

    await expect(ble.getDiagnostics({ since: 1002 })).resolves.toEqual([event])
    expect(nativeDiagnostics.getEvents).toHaveBeenCalledWith(1002)
  })

  it('stops the native diagnostics listener once, however often remove() is called', () => {
    nativeDiagnostics.addListener.mockReturnValue(5)

    const subscription = ble.onDiagnosticEvent(jest.fn())
    subscription.remove()
    subscription.remove()

    expect(nativeDiagnostics.removeListener).toHaveBeenCalledTimes(1)
    expect(nativeDiagnostics.removeListener).toHaveBeenCalledWith(5)
  })
})
