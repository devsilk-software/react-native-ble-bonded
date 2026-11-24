import { ble, BleError } from '../ble'
import { toBleError } from '../BleError'
import { Gatt } from '../../specs/Gatt.nitro'
import type { NativeConnectionChange } from '../../specs/Connection.nitro'

jest.mock('react-native', () => ({ Linking: { openSettings: jest.fn() } }))
jest.mock('../../specs/BluetoothState.nitro', () => ({ BluetoothState: {} }))
jest.mock('../../specs/Permissions.nitro', () => ({ Permissions: {} }))
jest.mock('../../specs/Diagnostics.nitro', () => ({ Diagnostics: {} }))
jest.mock('../../specs/Scanner.nitro', () => ({ Scanner: {} }))
jest.mock('../../specs/Gatt.nitro', () => ({ Gatt: { read: jest.fn(), write: jest.fn(), subscribe: jest.fn(), unsubscribe: jest.fn() } }))

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

const native = jest.mocked(Gatt)
const service = 'b0ed0001-0000-4000-8000-00000000b0ed'

beforeEach(async () => {
  jest.clearAllMocks()
  await ble.getConnectedDevices()
  report({ id: 'A', state: 'connected' })
})

describe('Read and write', () => {
  it('reads a value from the connected device, waiting 10 s by default', async () => {
    const value = new Uint8Array([1, 2, 3]).buffer
    native.read.mockResolvedValue(value)

    await expect(ble.device('A').read(service, '2a37')).resolves.toBe(value)
    expect(native.read).toHaveBeenCalledWith('A', service, '2a37', 10_000)
  })

  it('rejects at once with notConnected when the device is not connected, even while connecting', async () => {
    report({ id: 'A', state: 'connecting' })

    await expect(ble.device('A').read(service, '2a37')).rejects.toMatchObject({
      code: 'notConnected',
      message: 'The device is connecting; connect it before you read.',
    })
    await expect(ble.device('B').write(service, '2a37', new Uint8Array([1]))).rejects.toMatchObject({ code: 'notConnected' })
    expect(native.read).not.toHaveBeenCalled()
    expect(native.write).not.toHaveBeenCalled()
  })

  it('writes with response unless told otherwise, sending only the bytes a view covers', async () => {
    native.write.mockResolvedValue(undefined)
    const larger = new Uint8Array([9, 1, 2, 9])

    await ble.device('A').write(service, '2a37', larger.subarray(1, 3))
    await ble.device('A').write(service, '2a37', new Uint8Array([3]).buffer, { withoutResponse: true, timeout: 2_000 })

    const [first, second] = native.write.mock.calls
    expect(Array.from(new Uint8Array(first![3]))).toEqual([1, 2])
    expect(first![4]).toBe(true)
    expect(second![4]).toBe(false)
    expect(second![5]).toBe(2_000)
  })

  it('carries how long a value was and the most the write allows', async () => {
    native.write.mockRejectedValue(
      new Error(
        '[valueTooLong {"length":600,"maxLength":182}] Value is 600 bytes; a write without response to this device allows at most 182.'
      )
    )

    const failure = ble.device('A').write(service, '2a37', new Uint8Array(600), { withoutResponse: true })

    await expect(failure).rejects.toBeInstanceOf(BleError)
    await expect(failure).rejects.toMatchObject({ code: 'valueTooLong', length: 600, maxLength: 182, deviceId: 'A' })
  })

  it("names the device's own error and keeps its number", () => {
    const error = toBleError(new Error('[gattError:13 {"attError":"invalidAttributeValueLength"}] The device refused the value\'s length.'))

    expect(error).toMatchObject({ code: 'gattError', platformCode: 13, attError: 'invalidAttributeValueLength' })
  })

  it('refuses a timeout that is not more than 0 milliseconds', async () => {
    await expect(ble.device('A').read(service, '2a37', { timeout: 0 })).rejects.toThrow(RangeError)
  })
})

describe('Subscriptions', () => {
  /** Makes the native side subscribe and hands the test its callbacks. */
  function subscribing() {
    const callbacks: { onValues?: (values: ArrayBuffer[]) => void; onError?: (message: string) => void } = {}
    native.subscribe.mockImplementation(async (_id, _service, _characteristic, onValues, onError) => {
      callbacks.onValues = onValues
      callbacks.onError = onError
      return 3
    })
    native.unsubscribe.mockResolvedValue(undefined)
    return callbacks
  }

  it('calls onValue once per value, in order, though values arrive in batches', async () => {
    const native = subscribing()
    const onValue = jest.fn()

    await ble.device('A').subscribe(service, '2a37', onValue)
    const [one, two, three] = [new ArrayBuffer(1), new ArrayBuffer(2), new ArrayBuffer(3)]
    native.onValues?.([one, two])
    native.onValues?.([three])

    expect(onValue.mock.calls.map(([value]) => value)).toEqual([one, two, three])
  })

  it('stops calling back and unsubscribes once, however often remove() is called', async () => {
    const callbacks = subscribing()
    const onValue = jest.fn()

    const subscription = await ble.device('A').subscribe(service, '2a37', onValue)
    subscription.remove()
    subscription.remove()
    callbacks.onValues?.([new ArrayBuffer(1)])

    expect(native.unsubscribe).toHaveBeenCalledTimes(1)
    expect(native.unsubscribe).toHaveBeenCalledWith(3)
    expect(onValue).not.toHaveBeenCalled()
  })

  it('reports a failure to switch notifications back on as a BleError', async () => {
    const callbacks = subscribing()
    const onError = jest.fn()

    await ble.device('A').subscribe(service, '2a37', jest.fn(), { onError })
    callbacks.onError?.('[pairingRequired:5 {"attError":"insufficientAuthentication"}] The device refused the subscribe.')

    expect(onError.mock.calls[0]![0]).toMatchObject({ code: 'pairingRequired', attError: 'insufficientAuthentication', deviceId: 'A' })
  })

  it('rejects a second subscription with alreadySubscribed', async () => {
    native.subscribe.mockRejectedValue(
      new Error('[alreadySubscribed] Characteristic 2a37 already has a subscription. Remove it first, or share its values in your app.')
    )

    await expect(ble.device('A').subscribe(service, '2a37', jest.fn())).rejects.toMatchObject({ code: 'alreadySubscribed' })
  })

  it('subscribes again only once the previous subscription to the characteristic is removed', async () => {
    subscribing()
    let finishRemoval: () => void = () => {}
    native.unsubscribe.mockReturnValue(new Promise<void>((resolve) => (finishRemoval = resolve)))

    const first = await ble.device('A').subscribe(service, '2a37', jest.fn())
    first.remove()
    const second = ble.device('A').subscribe(service, '2A37', jest.fn())
    await Promise.resolve()
    expect(native.subscribe).toHaveBeenCalledTimes(1)

    finishRemoval()
    await second
    expect(native.subscribe).toHaveBeenCalledTimes(2)
  })

  it('rejects at once with notConnected when the device is not connected', async () => {
    report({ id: 'A', state: 'disconnected' })

    await expect(ble.device('A').subscribe(service, '2a37', jest.fn())).rejects.toMatchObject({ code: 'notConnected' })
    expect(native.subscribe).not.toHaveBeenCalled()
  })
})
