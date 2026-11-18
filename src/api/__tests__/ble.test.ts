import { ble } from '../ble'
import { BluetoothState } from '../../specs/BluetoothState.nitro'

jest.mock('../../specs/BluetoothState.nitro', () => ({
  BluetoothState: { getState: jest.fn() },
}))

const getState = BluetoothState.getState as jest.MockedFunction<typeof BluetoothState.getState>

describe('Bluetooth state', () => {
  it.each(['unknown', 'resetting', 'unsupported', 'unauthorized', 'poweredOff', 'poweredOn'] as const)(
    "reports the radio's current state (%s)",
    async (state) => {
      getState.mockResolvedValue(state)

      await expect(ble.getState()).resolves.toBe(state)
    }
  )
})
