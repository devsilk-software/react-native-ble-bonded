import { countConnection, shouldSubscribe, subscriptionKey, type ConnectionCount } from '../notificationsArming'

const key = subscriptionKey('A', 'b0ed0001', 'b0ed0004')
const disconnected: ConnectionCount = { isConnected: false, count: 0 }
const connected = countConnection(disconnected, true)

describe('When useNotifications subscribes', () => {
  it('subscribes once the device is connected, and not again while subscribed', () => {
    expect(shouldSubscribe(key, disconnected, undefined, undefined)).toBe(false)
    expect(shouldSubscribe(key, connected, undefined, undefined)).toBe(true)
    expect(shouldSubscribe(key, connected, { key, connection: 1 }, undefined)).toBe(false)
  })

  it('keeps the subscription across a disconnect', () => {
    const afterDrop = countConnection(connected, false)

    expect(shouldSubscribe(key, afterDrop, { key, connection: 1 }, undefined)).toBe(false)
  })

  it('after a failed subscribe, tries again on the next connection, not in a loop', () => {
    const failed = { key, connection: connected.count }
    const reconnected = countConnection(countConnection(connected, false), true)

    expect(shouldSubscribe(key, connected, undefined, failed)).toBe(false)
    expect(shouldSubscribe(key, reconnected, undefined, failed)).toBe(true)
  })

  it('waits for a connection before subscribing to another characteristic', () => {
    const other = subscriptionKey('B', 'b0ed0001', 'b0ed0004')
    const afterDrop = countConnection(connected, false)

    expect(shouldSubscribe(other, afterDrop, { key, connection: 1 }, undefined)).toBe(false)
    expect(shouldSubscribe(other, connected, { key, connection: 1 }, undefined)).toBe(true)
  })

  it('treats UUIDs alike whatever their case', () => {
    expect(subscriptionKey('A', 'B0ED0001', 'B0ED0004')).toBe(key)
  })
})
