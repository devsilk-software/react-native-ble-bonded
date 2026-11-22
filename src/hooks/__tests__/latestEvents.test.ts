import type { DiagnosticEvent } from '../../api/ble'
import { latestEvents } from '../latestEvents'

const event = (at: number, detail: string): DiagnosticEvent => ({ type: 'stateChanged', at, appState: 'foreground', detail })

describe('latest diagnostic events', () => {
  it('shows the stored events, then the live ones', () => {
    expect(latestEvents([event(1, 'poweredOff')], [event(2, 'poweredOn')], 100)).toEqual([event(1, 'poweredOff'), event(2, 'poweredOn')])
  })

  it('shows an event once when it arrives both stored and live', () => {
    expect(latestEvents([event(1, 'poweredOff')], [event(1, 'poweredOff'), event(2, 'poweredOn')], 100)).toEqual([
      event(1, 'poweredOff'),
      event(2, 'poweredOn'),
    ])
  })

  it('keeps only the newest events up to the limit', () => {
    expect(latestEvents([event(1, 'unknown'), event(2, 'poweredOff')], [event(3, 'poweredOn')], 2)).toEqual([
      event(2, 'poweredOff'),
      event(3, 'poweredOn'),
    ])
  })
})
