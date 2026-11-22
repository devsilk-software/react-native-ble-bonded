/** The codes every `BleError` uses; they mean the same on iOS and Android (decision 5). */
export type BleErrorCode =
  | 'bluetoothOff'
  | 'unauthorized'
  | 'deviceNotFound'
  | 'notConnected'
  | 'connectionFailed'
  | 'disconnected'
  | 'timeout'
  | 'pairingRequired'
  | 'gattError'
  | 'cancelled'
  | 'unsupported'
  | 'unknown'

const codes: readonly BleErrorCode[] = [
  'bluetoothOff',
  'unauthorized',
  'deviceNotFound',
  'notConnected',
  'connectionFailed',
  'disconnected',
  'timeout',
  'pairingRequired',
  'gattError',
  'cancelled',
  'unsupported',
  'unknown',
]

/** Every failure Bonded reports: a shared `code`, plus the platform's own code for whoever is debugging. */
export class BleError extends Error {
  readonly code: BleErrorCode
  /** The platform's own code (`CBError`, Android GATT status or scan error), when it gave one. */
  readonly platformCode?: number
  readonly deviceId?: string
  /** The operation that failed, such as `scan` or `connect`. */
  readonly operation?: string

  constructor(code: BleErrorCode, message: string, details: { platformCode?: number; deviceId?: string; operation?: string } = {}) {
    super(message)
    this.name = 'BleError'
    this.code = code
    this.platformCode = details.platformCode
    this.deviceId = details.deviceId
    this.operation = details.operation
  }
}

const nativeMessage = /\[(\w+)(?::(-?\d+))?\] ([\s\S]*)$/

/**
 * Turns an error from native code into a `BleError`. Native code writes `[code] message` or
 * `[code:platformCode] message`; anything else is returned unchanged.
 */
export function toBleError(error: unknown, operation?: string): unknown {
  if (error instanceof BleError) return error
  const message = error instanceof Error ? error.message : typeof error === 'string' ? error : undefined
  const match = message?.match(nativeMessage)
  if (match == null) return error
  const [, code = 'unknown', platformCode, text = ''] = match
  return new BleError(isCode(code) ? code : 'unknown', text, {
    platformCode: platformCode == null ? undefined : Number(platformCode),
    operation,
  })
}

function isCode(code: string): code is BleErrorCode {
  return (codes as readonly string[]).includes(code)
}
