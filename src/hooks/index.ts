import { useCallback, useEffect, useMemo, useRef, useState, useSyncExternalStore } from 'react'
import {
  ble,
  BleError,
  type BluetoothAdapterState,
  type BluetoothPermission,
  Device,
  type DiagnosticEvent,
  type ScannedDevice,
  type ScanOptions,
} from '../api/ble'
import { connectionStore } from '../api/connections'
import { onDiagnosticsReset, subscribeToDiagnostics } from '../api/diagnostics'
import { DeviceList, type ScanSort } from './deviceList'
import { createExternalStore } from './externalStore'
import { latestEvents } from './latestEvents'

const bluetoothState = createExternalStore<BluetoothAdapterState, 'unknown'>((onChange) => ble.onStateChange(onChange), 'unknown')
const bluetoothPermission = createExternalStore<BluetoothPermission, undefined>((onChange) => ble.onPermissionChange(onChange), undefined)

/**
 * The phone's Bluetooth radio state, re-rendering on every change. `unknown` until the first report. Never shows
 * the iOS permission prompt.
 */
export function useBluetoothState(): BluetoothAdapterState {
  return useSyncExternalStore(bluetoothState.subscribe, bluetoothState.getSnapshot)
}

/**
 * Whether the app may use Bluetooth, re-rendering on every change, including changes made in the system
 * settings. `undefined` until the first report.
 */
export function useBluetoothPermission(): BluetoothPermission | undefined {
  return useSyncExternalStore(bluetoothPermission.subscribe, bluetoothPermission.getSnapshot)
}

/**
 * The newest recorded diagnostic events, oldest first: those stored so far, then each new one as it is recorded.
 * Keeps at most `limit` (100 by default). Empty while diagnostics are off; reloads when diagnostics are turned on
 * or off or cleared.
 */
export function useDiagnosticEvents({ limit = 100 }: { limit?: number } = {}): DiagnosticEvent[] {
  const [events, setEvents] = useState<DiagnosticEvent[]>([])

  useEffect(() => {
    let isActive = true
    let generation = 0
    let stored: DiagnosticEvent[] | undefined
    let live: DiagnosticEvent[] = []
    const update = () => {
      if (isActive && stored != null) setEvents(latestEvents(stored, live, limit))
    }
    // Loads the stored events; a load overtaken by a newer one is dropped.
    const load = () => {
      const current = ++generation
      stored = undefined
      live = []
      const apply = (events: DiagnosticEvent[]) => {
        if (current !== generation) return
        stored = events
        update()
      }
      ble.getDiagnostics().then(apply, () => apply([]))
    }

    const { subscription, isReady } = subscribeToDiagnostics((event) => {
      live = latestEvents([], [...live, event], limit)
      update()
    })
    // Load only once the listener is registered, so no event falls between the stored and the live ones.
    isReady.then(load, load)
    const reset = onDiagnosticsReset(() => {
      if (isActive) setEvents([])
      load()
    })
    return () => {
      isActive = false
      subscription.remove()
      reset.remove()
    }
  }, [limit])

  return events
}

function subscribeToConnections(onStoreChange: () => void): () => void {
  const subscription = connectionStore.subscribe(onStoreChange)
  return () => subscription.remove()
}

/**
 * The device with this ID, re-rendering when its connection changes. The returned handle's `name`,
 * `connectionState`, `lastDisconnect` and `isRemembered` are current, and its methods (`connect()`, `forget()`…)
 * work as on `ble.device(id)`.
 *
 * Without an ID, the one remembered device, connected or not, or `undefined` while none is. With several remembered
 * it can't know which, so it gives `undefined` and warns in development; pass an ID then.
 */
export function useDevice(id: string): Device
export function useDevice(): Device | undefined
export function useDevice(id?: string): Device | undefined {
  const all = useSyncExternalStore(subscribeToConnections, () => connectionStore.all())
  // A new handle for each change, so components that compare the device see it change.
  return useMemo(() => (id == null ? onlyRemembered() : new Device(id)), [id, all])
}

/** The one remembered device; with several, `undefined` and a warning in development, never a crash in render. */
function onlyRemembered(): Device | undefined {
  try {
    return ble.device()
  } catch (error) {
    if (typeof __DEV__ !== 'undefined' && __DEV__) {
      console.warn(`useDevice() without an ID: ${error instanceof Error ? error.message : String(error)}`)
    }
    return undefined
  }
}

declare const __DEV__: boolean | undefined

/** The remembered devices, most recently connected first, re-rendering when one is remembered or forgotten. */
export function useRememberedDevices(): Device[] {
  const all = useSyncExternalStore(subscribeToConnections, () => connectionStore.all())
  return useMemo(
    () =>
      all
        .filter((connection) => connection.isRemembered)
        .sort((a, b) => (b.lastConnectedAt ?? 0) - (a.lastConnectedAt ?? 0))
        .map((connection) => new Device(connection.id)),
    [all]
  )
}

/** The devices that are connected, connecting or disconnecting, re-rendering when any of them changes. */
export function useConnectedDevices(): Device[] {
  const all = useSyncExternalStore(subscribeToConnections, () => connectionStore.all())
  return useMemo(
    () => all.filter((connection) => connection.state !== 'disconnected').map((connection) => new Device(connection.id)),
    [all]
  )
}

/** How often `useScan` re-renders at most while devices are reported. */
const scanRenderInterval = 250

export interface UseScanOptions extends ScanOptions {
  /**
   * `firstSeen` (default) keeps devices in the order first heard, so rows never move and stay easy to tap;
   * `signal` puts the strongest signal first, re-ordering as signals change.
   */
  sort?: ScanSort
}

export interface ScanResult {
  /** Each device once, with its latest RSSI and name; devices unheard for 10 s drop off. */
  devices: ScannedDevice[]
  isScanning: boolean
  /** Why the scan isn't running: Bluetooth off, permission blocked, or a failure. */
  error?: BleError
  /** Scans again, for example after the timeout or for pull to refresh. */
  restart(): void
}

/**
 * Scans while the component is mounted and returns the devices found. Waits while Bluetooth is off or permission
 * isn't granted, and starts by itself once both are fine; unless switched off, it asks for both first. Never
 * throws: problems are in `error`. Stops after 30 seconds unless `timeout` says otherwise; `restart()` scans again.
 */
export function useScan(options: UseScanOptions = {}): ScanResult {
  const state = useBluetoothState()
  const permission = useBluetoothPermission()
  const [devices, setDevices] = useState<ScannedDevice[]>([])
  const [isScanning, setIsScanning] = useState(false)
  const [scanError, setScanError] = useState<BleError>()
  const [generation, setGeneration] = useState(0)
  const filter = useRef(options.filter)
  filter.current = options.filter
  const sort = useRef(options.sort)
  sort.current = options.sort
  const hasAsked = useRef({ permission: false, bluetooth: false })

  const { services, name, fullAdvertisement, timeout, autoRequestPermission, autoEnableBluetooth } = options
  const key = JSON.stringify({ services, name, fullAdvertisement, timeout })

  useEffect(() => {
    if (autoRequestPermission !== false && permission === 'denied' && !hasAsked.current.permission) {
      hasAsked.current.permission = true
      ble.requestPermissions().catch(() => {})
    }
  }, [permission, autoRequestPermission])

  useEffect(() => {
    if (autoEnableBluetooth !== false && state === 'poweredOff' && permission === 'granted' && !hasAsked.current.bluetooth) {
      hasAsked.current.bluetooth = true
      ble.requestBluetoothOn().catch(() => {})
    }
  }, [state, permission, autoEnableBluetooth])

  const isReady = state === 'poweredOn' && permission === 'granted'

  useEffect(() => {
    if (!isReady) return
    let isActive = true
    let scan: { stop(): void } | undefined
    const list = new DeviceList()
    const flush = () => {
      const changes = list.takeChanges(Date.now(), sort.current)
      if (changes != null) setDevices(changes)
    }
    const interval = setInterval(flush, scanRenderInterval)
    setDevices([])
    setScanError(undefined)
    setIsScanning(true)
    const scanOptions: ScanOptions = JSON.parse(key)
    ble
      .startScan(
        {
          ...scanOptions,
          filter: (device) => filter.current?.(device) ?? true,
          allowDuplicates: true,
          autoRequestPermission: false,
          autoEnableBluetooth: false,
        },
        (device) => list.add(device, Date.now())
      )
      .then((started) => {
        if (!isActive) return started.stop()
        scan = started
        started.stopped.then((stop) => {
          if (!isActive) return
          flush()
          setIsScanning(false)
          if (stop.error != null) setScanError(stop.error)
        })
      })
      .catch((error: unknown) => {
        if (!isActive) return
        setIsScanning(false)
        setScanError(error instanceof BleError ? error : new BleError('unknown', String(error), { operation: 'scan' }))
      })
    return () => {
      isActive = false
      clearInterval(interval)
      scan?.stop()
      setIsScanning(false)
    }
  }, [isReady, key, generation])

  const restart = useCallback(() => setGeneration((value) => value + 1), [])
  const error = isReady ? scanError : waitingError(state, permission)
  return { devices, isScanning: isReady && isScanning, error, restart }
}

/** Why `useScan` is waiting, when the reason is one the user has to fix. */
function waitingError(state: BluetoothAdapterState, permission: BluetoothPermission | undefined): BleError | undefined {
  if (permission === 'blocked') return new BleError('unauthorized', 'Bluetooth permission is blocked', { operation: 'scan' })
  if (permission === 'unavailable' || state === 'unsupported') {
    return new BleError('unsupported', 'This phone has no Bluetooth Low Energy', { operation: 'scan' })
  }
  if (permission === 'granted' && state === 'poweredOff') return new BleError('bluetoothOff', 'Bluetooth is off', { operation: 'scan' })
  return undefined
}

export type { BluetoothAdapterState, BluetoothPermission, DiagnosticEvent, ScannedDevice, ScanOptions, ScanSort }
export { Device }
