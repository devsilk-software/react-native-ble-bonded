import type { ScannedDevice } from '../api/ble'

/** How long a device stays in the list without being heard. */
export const staleAfter = 10_000

/**
 * The devices a scan has heard: each once, with its latest report, strongest signal first, without those
 * unheard for `staleAfter` milliseconds.
 */
export class DeviceList {
  private readonly devices = new Map<string, { device: ScannedDevice; heardAt: number }>()
  private hasChanged = false

  add(device: ScannedDevice, now: number): void {
    this.devices.set(device.id, { device, heardAt: now })
    this.hasChanged = true
  }

  clear(): void {
    this.devices.clear()
    this.hasChanged = true
  }

  /** The current list, or `undefined` when nothing changed since the last call. */
  takeChanges(now: number): ScannedDevice[] | undefined {
    for (const [id, entry] of this.devices) {
      if (now - entry.heardAt > staleAfter) {
        this.devices.delete(id)
        this.hasChanged = true
      }
    }
    if (!this.hasChanged) return undefined
    this.hasChanged = false
    return [...this.devices.values()].map((entry) => entry.device).sort((a, b) => b.rssi - a.rssi)
  }
}
