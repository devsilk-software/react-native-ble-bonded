import { Android, Apple, type HybridObject } from 'nitro-core'

export * from '#nitro/Diagnostics'

/** What happened. The list grows as features land (scans, connections, bonds). */
export type DiagnosticEventType =
  | 'stateChanged'
  | 'permissionChanged'
  | 'scanStarted'
  | 'scanStopped'
  | 'connecting'
  | 'connected'
  | 'disconnected'
  | 'connectionRetried'
  | 'gattOperation'
  | 'forgotten'
  | 'bondStateChanged'

/** Whether the app was in the foreground or the background when the event happened. */
export type DiagnosticAppState = 'foreground' | 'background'

/** One recorded event: metadata only, never characteristic values. */
export interface DiagnosticEvent {
  type: DiagnosticEventType
  /** Milliseconds since the Unix epoch. */
  at: number
  appState: DiagnosticAppState
  deviceId?: string
  /** A `BleError` code, for events about a failure. */
  code?: string
  /** The platform's own code, as in `BleError`. */
  platformCode?: number
  /** The new value or other short detail, such as `poweredOff`. */
  detail?: string
}

/** Diagnostics settings. Every field is optional; omitted fields keep their current value. */
export interface DiagnosticsConfig {
  /** Off by default: nothing is recorded or written until this is true. */
  enabled?: boolean
  /** How many events to keep, in memory and in the file. Default 1,000. */
  capacity?: number
  /** Also record GATT operations with their durations. Default false. */
  gatt?: boolean
}

@Apple({
  language: 'swift',
  implementation: { className: 'HybridDiagnostics' },
})
@Android({
  language: 'kotlin',
  implementation: { className: 'HybridDiagnostics' },
})
export declare class Diagnostics extends HybridObject {
  /** Changes the settings. Turning diagnostics off also deletes the stored events. */
  static configure(config: DiagnosticsConfig): Promise<void>

  /** The stored events, oldest first; only those at or after `since` (milliseconds) when given. */
  static getEvents(since?: number): Promise<DiagnosticEvent[]>

  /** Deletes the stored events. */
  static clear(): Promise<void>

  /**
   * Calls `onEvent` with every new event while JavaScript runs. Resolves with an ID for `removeListener` once the
   * listener is registered, so no event recorded after that is missed.
   */
  static addListener(onEvent: (event: DiagnosticEvent) => void): Promise<number>

  /** Stops the listener `addListener` returned this ID for. */
  static removeListener(id: number): void
}
