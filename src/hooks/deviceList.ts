import type { ScannedDevice } from '../api/ble'

/** How long a device stays in the list without being heard. */
export const staleAfter = 10_000

/** How `useScan` orders devices: in the order first heard, so rows never move, or strongest signal first. */
export type ScanSort = 'firstSeen' | 'signal'

/**
 * The devices a scan has heard: each once, with its latest report, without those unheard for `staleAfter`
 * milliseconds. A device keeps its place when it reports again.
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
  takeChanges(now: number, sort: ScanSort = 'firstSeen'): ScannedDevice[] | undefined {
    for (const [id, entry] of this.devices) {
      if (now - entry.heardAt > staleAfter) {
        this.devices.delete(id)
        this.hasChanged = true
      }
    }
    if (!this.hasChanged) return undefined
    this.hasChanged = false
    const devices = [...this.devices.values()].map((entry) => entry.device)
    return sort === 'signal' ? devices.sort((a, b) => b.rssi - a.rssi) : devices
  }
}
