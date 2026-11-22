// Diagnostics plumbing shared by `ble.ts` and the hooks; not part of the public API.
import { Diagnostics, type DiagnosticEvent } from '../specs/Diagnostics.nitro'
import type { Subscription } from './ble'

/** The largest capacity both platforms can hold (Android stores it in a 32-bit integer). */
export const maxCapacity = 2_147_483_647

/**
 * Starts a native diagnostics listener. `subscription` is usable at once; `isReady` resolves when the native side
 * has registered the listener, from which point no recorded event is missed.
 */
export function subscribeToDiagnostics(onEvent: (event: DiagnosticEvent) => void): {
  subscription: Subscription
  isReady: Promise<void>
} {
  let id: number | undefined
  let isRemoved = false
  const isReady = Diagnostics.addListener((event) => {
    if (!isRemoved) onEvent(event)
  }).then((registered) => {
    if (isRemoved) Diagnostics.removeListener(registered)
    else id = registered
  })
  return {
    subscription: {
      remove() {
        if (isRemoved) return
        isRemoved = true
        if (id != null) Diagnostics.removeListener(id)
      },
    },
    isReady,
  }
}

const resetListeners = new Set<() => void>()

/** Calls `onReset` after diagnostics are turned on or off or cleared, when what was recorded has changed. */
export function onDiagnosticsReset(onReset: () => void): Subscription {
  resetListeners.add(onReset)
  return { remove: () => resetListeners.delete(onReset) }
}

export function notifyDiagnosticsReset(): void {
  resetListeners.forEach((onReset) => onReset())
}
