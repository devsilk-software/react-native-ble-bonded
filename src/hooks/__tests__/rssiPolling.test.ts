import { pollRssi } from '../rssiPolling'

beforeEach(() => jest.useFakeTimers())
afterEach(() => jest.useRealTimers())

describe('When useRssi reads the signal strength', () => {
  it('reads now, then again an interval after each reading finishes', async () => {
    const readings: number[] = []
    let next = -60
    const read = jest.fn(() => Promise.resolve(next--))

    const stop = pollRssi(
      read,
      1_000,
      (rssi) => readings.push(rssi),
      () => {}
    )
    await jest.advanceTimersByTimeAsync(0)
    expect(readings).toEqual([-60])
    await jest.advanceTimersByTimeAsync(999)
    expect(read).toHaveBeenCalledTimes(1)
    await jest.advanceTimersByTimeAsync(1)
    expect(readings).toEqual([-60, -61])
    stop()
  })

  it('keeps reading after a failed reading, and reports the failure', async () => {
    const errors: unknown[] = []
    const read = jest.fn().mockRejectedValueOnce(new Error('timeout')).mockResolvedValue(-50)
    const readings: number[] = []

    const stop = pollRssi(
      read,
      500,
      (rssi) => readings.push(rssi),
      (error) => errors.push(error)
    )
    await jest.advanceTimersByTimeAsync(500)

    expect(errors).toHaveLength(1)
    expect(readings).toEqual([-50])
    stop()
  })

  it('reports nothing and reads no more once stopped, even when a reading was still running', async () => {
    let finish: (rssi: number) => void = () => {}
    const read = jest.fn(() => new Promise<number>((resolve) => (finish = resolve)))
    const onReading = jest.fn()

    const stop = pollRssi(read, 1_000, onReading, () => {})
    stop()
    finish(-40)
    await jest.advanceTimersByTimeAsync(5_000)

    expect(onReading).not.toHaveBeenCalled()
    expect(read).toHaveBeenCalledTimes(1)
  })
})
