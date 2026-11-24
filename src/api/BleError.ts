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
  | 'valueTooLong'
  | 'characteristicNotFound'

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
  'valueTooLong',
  'characteristicNotFound',
]

/** The details a `BleError` can carry beyond its code and message. */
export interface BleErrorDetails {
  platformCode?: number
  deviceId?: string
  operation?: string
  /** The Bluetooth error the device answered with, by name, such as `invalidAttributeValueLength`. */
  attError?: string
  /** For `valueTooLong`: the value's length in bytes. */
  length?: number
  /** For `valueTooLong`: the most the operation allows, in bytes. */
  maxLength?: number
}

/** Every failure Bonded reports: a shared `code`, plus the platform's own code for whoever is debugging. */
export class BleError extends Error {
  readonly code: BleErrorCode
  /** The platform's own code (`CBError`, Android GATT status or scan error), when it gave one. */
  readonly platformCode?: number
  readonly deviceId?: string
  /** The operation that failed, such as `scan`, `connect` or `read`. */
  readonly operation?: string
  /** The Bluetooth error the device answered with, by name, such as `invalidAttributeValueLength`. */
  readonly attError?: string
  /** For `valueTooLong`: the value's length in bytes. */
  readonly length?: number
  /** For `valueTooLong`: the most the operation allows, in bytes. */
  readonly maxLength?: number

  constructor(code: BleErrorCode, message: string, details: BleErrorDetails = {}) {
    super(message)
    this.name = 'BleError'
    this.code = code
    this.platformCode = details.platformCode
    this.deviceId = details.deviceId
    this.operation = details.operation
    this.attError = details.attError
    this.length = details.length
    this.maxLength = details.maxLength
  }
}

const nativeMessage = /\[(\w+)(?::(-?\d+))?(?: (\{[^\]]*\}))?\] ([\s\S]*)$/

/**
 * Turns an error from native code into a `BleError`. Native code writes `[code] message`,
 * `[code:platformCode] message`, and the other fields as JSON before the closing bracket
 * (`[valueTooLong {"length":600,"maxLength":182}] message`); anything else is returned unchanged.
 */
export function toBleError(error: unknown, operation?: string, deviceId?: string): unknown {
  if (error instanceof BleError) return error
  const message = error instanceof Error ? error.message : typeof error === 'string' ? error : undefined
  const match = message?.match(nativeMessage)
  if (match == null) return error
  const [, code = 'unknown', platformCode, json, text = ''] = match
  const fields = parseFields(json)
  return new BleError(isCode(code) ? code : 'unknown', text, {
    platformCode: platformCode == null ? undefined : Number(platformCode),
    operation,
    deviceId,
    attError: typeof fields.attError === 'string' ? fields.attError : undefined,
    length: typeof fields.length === 'number' ? fields.length : undefined,
    maxLength: typeof fields.maxLength === 'number' ? fields.maxLength : undefined,
  })
}

function parseFields(json: string | undefined): Record<string, unknown> {
  if (json == null) return {}
  try {
    const parsed: unknown = JSON.parse(json)
    return typeof parsed === 'object' && parsed != null ? (parsed as Record<string, unknown>) : {}
  } catch {
    return {}
  }
}

function isCode(code: string): code is BleErrorCode {
  return (codes as readonly string[]).includes(code)
}
