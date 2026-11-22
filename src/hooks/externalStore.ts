import type { Subscription } from '../api/ble'

/**
 * A store `useSyncExternalStore` can read, fed by one of Bonded's listeners. The native listener starts with
 * the first component that subscribes and stops with the last, however many components use the hook.
 */
export interface ExternalStore<T> {
  subscribe(onStoreChange: () => void): () => void
  getSnapshot(): T
}

export function createExternalStore<T, Initial>(
  listen: (onChange: (value: T) => void) => Subscription,
  initial: Initial
): ExternalStore<T | Initial> {
  let value: T | Initial = initial
  let nativeSubscription: Subscription | undefined
  const components = new Set<() => void>()

  return {
    subscribe(onStoreChange) {
      components.add(onStoreChange)
      if (nativeSubscription == null) {
        nativeSubscription = listen((next) => {
          if (Object.is(next, value)) return
          value = next
          components.forEach((notify) => notify())
        })
      }
      return () => {
        components.delete(onStoreChange)
        if (components.size === 0) {
          nativeSubscription?.remove()
          nativeSubscription = undefined
          value = initial
        }
      }
    },
    getSnapshot() {
      return value
    },
  }
}
