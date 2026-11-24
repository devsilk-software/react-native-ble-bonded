/** Which characteristic of which device, as one string. */
export function subscriptionKey(id: string, service: string, characteristic: string): string {
  return `${id}\n${service.toLowerCase()}\n${characteristic.toLowerCase()}`
}

/** How often the device has connected while the hook was mounted. */
export interface ConnectionCount {
  isConnected: boolean
  count: number
}

/** Counts a connection when the device turns connected. */
export function countConnection(previous: ConnectionCount, isConnected: boolean): ConnectionCount {
  if (previous.isConnected === isConnected) return previous
  return { isConnected, count: previous.count + (isConnected ? 1 : 0) }
}

/** A subscription the hook started or stopped, and during which connection. */
export interface Attempt {
  key: string
  connection: number
}

/**
 * Whether `useNotifications` should subscribe now: when the device is connected and the hook isn't already subscribed
 * for this characteristic. After a subscription failed or ended, it waits for the next connection, so a failure
 * doesn't retry in a loop and a reconnect tries again.
 */
export function shouldSubscribe(
  key: string | undefined,
  connection: ConnectionCount,
  subscribed: Attempt | undefined,
  stopped: Attempt | undefined
): key is string {
  if (key == null || !connection.isConnected || subscribed?.key === key) return false
  return !(stopped?.key === key && stopped.connection === connection.count)
}
