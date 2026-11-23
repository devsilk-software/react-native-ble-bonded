import type { ScannedDevice } from '../../api/ble'
import { DeviceList, staleAfter } from '../deviceList'

const device = (id: string, rssi: number, name?: string): ScannedDevice => ({ id, rssi, name, serviceUuids: [] })

describe('scanned device list', () => {
  it('lists each device once with its latest report, in the order first heard', () => {
    const list = new DeviceList()
    list.add(device('A', -80), 0)
    list.add(device('B', -60), 0)
    list.add(device('A', -50, 'Thermo'), 100)
    list.add(device('B', -90), 100)

    expect(list.takeChanges(100)).toEqual([device('A', -50, 'Thermo'), device('B', -90)])
  })

  it('lists the strongest signal first when asked', () => {
    const list = new DeviceList()
    list.add(device('A', -80), 0)
    list.add(device('B', -60), 0)

    expect(list.takeChanges(0, 'signal')).toEqual([device('B', -60), device('A', -80)])
  })

  it('reports nothing new when nothing changed', () => {
    const list = new DeviceList()
    list.add(device('A', -80), 0)
    list.takeChanges(0)

    expect(list.takeChanges(100)).toBeUndefined()
  })

  it('drops devices not heard for ten seconds', () => {
    const list = new DeviceList()
    list.add(device('A', -80), 0)
    list.add(device('B', -60), 5_000)
    list.takeChanges(5_000)

    expect(list.takeChanges(staleAfter + 1)).toEqual([device('B', -60)])
  })
})
