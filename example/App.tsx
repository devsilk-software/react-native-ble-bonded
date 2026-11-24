import { useState } from 'react'
import { Button, FlatList, Pressable, SafeAreaView, StatusBar, StyleSheet, Switch, Text, View } from 'react-native'
import { ble, BleError, type DiagnosticEvent, type ScannedDevice, type Service } from 'react-native-ble-bonded'
import {
  useBluetoothPermission,
  useBluetoothState,
  useDevice,
  useDiagnosticEvents,
  useRememberedDevices,
  useScan,
} from 'react-native-ble-bonded/hooks'

type Tab = 'scan' | 'device' | 'diagnostics'

// The device-test app: every feature gets a screen here as it lands, for the manual checklist.
export default function App() {
  const state = useBluetoothState()
  const permission = useBluetoothPermission()
  const [tab, setTab] = useState<Tab>('scan')
  const [selected, setSelected] = useState<string>()

  return (
    <SafeAreaView style={styles.screen}>
      <StatusBar barStyle='dark-content' />
      <View style={styles.status}>
        <Text style={styles.label}>
          Bluetooth{' '}
          <Text style={styles.value} testID='bluetooth-state'>
            {state}
          </Text>
        </Text>
        <Text style={styles.label}>
          Permission{' '}
          <Text style={styles.value} testID='bluetooth-permission'>
            {permission ?? '…'}
          </Text>
        </Text>
      </View>
      <View style={styles.row}>
        {permission === 'denied' && <Button title='Request permission' onPress={() => ble.requestPermissions()} />}
        {permission === 'blocked' && <Button title='Open settings' onPress={() => ble.openSettings()} />}
        {state === 'poweredOff' && <Button title='Turn Bluetooth on' onPress={() => ble.requestBluetoothOn()} />}
      </View>
      <View style={styles.tabs}>
        <Button title='Scan' onPress={() => setTab('scan')} disabled={tab === 'scan'} testID='tab-scan' />
        <Button title='Device' onPress={() => setTab('device')} disabled={tab === 'device' || selected == null} testID='tab-device' />
        <Button title='Diagnostics' onPress={() => setTab('diagnostics')} disabled={tab === 'diagnostics'} testID='tab-diagnostics' />
      </View>
      {tab === 'scan' && (
        <Scanning
          onSelect={(id) => {
            setSelected(id)
            setTab('device')
          }}
        />
      )}
      {tab === 'device' && selected != null && <Connection id={selected} />}
      {tab === 'diagnostics' && <Diagnostics />}
    </SafeAreaView>
  )
}

// The service the Mac test peripheral advertises (tools/test-peripheral).
const testService = 'b0ed0001-0000-4000-8000-00000000b0ed'
const testRead = 'b0ed0002-0000-4000-8000-00000000b0ed'
const testWrite = 'b0ed0003-0000-4000-8000-00000000b0ed'

// useScan scans while mounted, so the list mounts only while scanning is wanted.
function Scanning({ onSelect }: { onSelect: (id: string) => void }) {
  const [isWanted, setIsWanted] = useState(false)
  const [isTestOnly, setIsTestOnly] = useState(true)
  return (
    <View style={styles.section}>
      <View style={styles.row}>
        <Text style={styles.label}>Scanning {isWanted ? 'wanted' : 'off'}</Text>
        <Button title={isWanted ? 'Stop' : 'Scan'} onPress={() => setIsWanted(!isWanted)} testID='scan-toggle' />
      </View>
      <View style={styles.row}>
        <Text style={styles.label}>{isTestOnly ? 'Test peripheral only' : 'Every device'}</Text>
        <Switch value={isTestOnly} onValueChange={setIsTestOnly} testID='scan-test-only' />
      </View>
      <Remembered onSelect={onSelect} />
      {isWanted && <DeviceList onSelect={onSelect} services={isTestOnly ? [testService] : []} />}
    </View>
  )
}

// The remembered devices, and ble.connect() without arguments: what a one-device app calls at startup.
function Remembered({ onSelect }: { onSelect: (id: string) => void }) {
  const remembered = useRememberedDevices()
  const [status, setStatus] = useState<string>()

  const connectRemembered = async () => {
    setStatus('connecting…')
    try {
      const device = await ble.connect()
      setStatus(undefined)
      onSelect(device.id)
    } catch (error) {
      setStatus(error instanceof Error ? error.message : String(error))
    }
  }

  return (
    <View>
      <View style={styles.row}>
        <Text style={styles.label} testID='remembered-count'>
          Remembered: {remembered.map((device) => device.name ?? device.id).join(', ') || 'none'}
        </Text>
        <Button title='Connect remembered' onPress={connectRemembered} testID='connect-remembered' />
      </View>
      {status != null && <Text style={styles.note}>{status}</Text>}
    </View>
  )
}

function DeviceList({ onSelect, services }: { onSelect: (id: string) => void; services: string[] }) {
  const { devices, isScanning, error, restart } = useScan({ services, timeout: 30_000 })
  return (
    <>
      <View style={styles.row}>
        <Text style={styles.note} testID='scan-status'>
          {isScanning ? `scanning, ${devices.length} found` : `stopped, ${devices.length} found`}
          {error != null ? ` (${error.code}: ${error.message})` : ''}
        </Text>
        <Button title='Restart' onPress={restart} disabled={isScanning} testID='scan-restart' />
      </View>
      <FlatList
        testID='scanned-devices'
        data={devices}
        keyExtractor={(device) => device.id}
        renderItem={({ item }) => (
          <Pressable
            onPress={() => onSelect(item.id)}
            style={({ pressed }) => [styles.device, pressed && styles.pressed]}
            testID={`device-${item.name ?? item.id}`}
          >
            <Text style={styles.deviceName}>{item.name ?? '(no name)'}</Text>
            <Text style={styles.event}>{describeDevice(item)}</Text>
          </Pressable>
        )}
        ListEmptyComponent={<Text style={styles.note}>No devices yet</Text>}
      />
    </>
  )
}

function describeDevice(device: ScannedDevice): string {
  const company = device.manufacturerData == null ? '' : `  company 0x${device.manufacturerData.companyId.toString(16).padStart(4, '0')}`
  return `${device.rssi} dBm  ${device.id}${company}`
}

// One device: connect, disconnect, and the services discovered on connecting.
function Connection({ id }: { id: string }) {
  const device = useDevice(id)
  const [services, setServices] = useState<Service[]>()
  const [failure, setFailure] = useState<string>()
  const [result, setResult] = useState<string>()

  const run = async (action: () => Promise<unknown>) => {
    setFailure(undefined)
    setResult(undefined)
    try {
      await action()
    } catch (error) {
      const code = error instanceof BleError ? `${error.code}: ` : ''
      setFailure(code + (error instanceof Error ? error.message : String(error)))
    }
  }

  const read = async () => {
    const value = await device.read(testService, testRead)
    setResult(`read "${String.fromCharCode(...new Uint8Array(value))}"`)
  }

  const write = async (withoutResponse: boolean) => {
    const text = withoutResponse ? 'no response' : 'hello'
    await device.write(
      testService,
      testWrite,
      Uint8Array.from(text, (character) => character.charCodeAt(0)),
      { withoutResponse }
    )
    setResult(`wrote "${text}"`)
  }

  const last = device.lastDisconnect
  return (
    <View style={styles.section}>
      <Text style={styles.label}>
        {device.name ?? '(no name)'}{' '}
        <Text style={styles.value} testID='connection-state'>
          {device.connectionState}
        </Text>
        {device.isRemembered ? '  (remembered)' : ''}
      </Text>
      <Text style={styles.event}>{id}</Text>
      {last != null && (
        <Text style={styles.note} testID='last-disconnect'>
          last disconnect: {last.reason}
          {last.error != null ? ` (${last.error.code}${last.error.platformCode != null ? ` ${last.error.platformCode}` : ''})` : ''}
        </Text>
      )}
      {failure != null && (
        <Text style={styles.note} testID='connection-failure'>
          {failure}
        </Text>
      )}
      <View style={styles.row}>
        <Button title='Connect' onPress={() => run(() => device.connect())} testID='connect' />
        <Button title='Disconnect' onPress={() => run(() => device.disconnect())} testID='disconnect' />
        <Button title='Services' onPress={() => run(async () => setServices(await device.getServices()))} testID='services' />
        <Button title='Forget' onPress={() => run(() => device.forget())} disabled={!device.isRemembered} testID='forget' />
      </View>
      <View style={styles.row}>
        <Button title='Read' onPress={() => run(read)} testID='read' />
        <Button title='Write' onPress={() => run(() => write(false))} testID='write' />
        <Button title='Write NR' onPress={() => run(() => write(true))} testID='write-no-response' />
        <Button
          title='Too long'
          onPress={() => run(() => device.write(testService, testWrite, new Uint8Array(600), { withoutResponse: true }))}
          testID='write-too-long'
        />
        <Button title='Wrong op' onPress={() => run(() => device.read(testService, testWrite))} testID='read-write-only' />
      </View>
      {result != null && (
        <Text style={styles.note} testID='operation-result'>
          {result}
        </Text>
      )}
      <FlatList
        data={services ?? []}
        keyExtractor={(service) => service.uuid}
        renderItem={({ item }) => (
          <Text style={styles.event}>
            {item.uuid}
            {item.characteristics.map((characteristic) => `\n  ${characteristic.uuid} ${characteristic.properties.join(',')}`).join('')}
          </Text>
        )}
      />
    </View>
  )
}

// Diagnostics are off by default; this section turns them on for testing and shows what Bonded recorded.
function Diagnostics() {
  const [isEnabled, setIsEnabled] = useState(false)

  const toggle = async () => {
    await ble.configure({ diagnostics: { enabled: !isEnabled } })
    setIsEnabled(!isEnabled)
  }

  return (
    <View style={styles.section}>
      <View style={styles.row}>
        <Text style={styles.label}>Diagnostics {isEnabled ? 'on' : 'off'}</Text>
        <Button title={isEnabled ? 'Turn off' : 'Turn on'} onPress={toggle} testID='diagnostics-toggle' />
        <Button title='Clear' onPress={() => ble.clearDiagnostics()} disabled={!isEnabled} />
      </View>
      <EventList />
    </View>
  )
}

function EventList() {
  const events = useDiagnosticEvents({ limit: 50 })
  return (
    <FlatList
      testID='diagnostic-events'
      data={[...events].reverse()}
      keyExtractor={(event, index) => `${event.at}-${index}`}
      renderItem={({ item }) => <Text style={styles.event}>{describe(item)}</Text>}
      ListEmptyComponent={<Text style={styles.note}>No events recorded</Text>}
    />
  )
}

function describe(event: DiagnosticEvent): string {
  const time = new Date(event.at).toLocaleTimeString()
  return `${time}  ${event.type}  ${event.detail ?? ''}  (${event.appState})`
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: '#fff' },
  status: { flexDirection: 'row', justifyContent: 'space-around', paddingTop: 16 },
  label: { fontSize: 14, color: '#5a6577' },
  value: { fontSize: 16, fontWeight: '600', color: '#000' },
  note: { fontSize: 13, color: '#5a6577', textAlign: 'center', paddingVertical: 8, flexShrink: 1 },
  tabs: { flexDirection: 'row', justifyContent: 'center', gap: 16, paddingVertical: 4 },
  section: { flex: 1, borderTopWidth: StyleSheet.hairlineWidth, borderColor: '#c9ced6', paddingHorizontal: 16 },
  row: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingVertical: 8, gap: 8 },
  event: { fontSize: 12, fontFamily: 'Menlo', paddingVertical: 2 },
  device: { paddingVertical: 12, borderBottomWidth: StyleSheet.hairlineWidth, borderColor: '#c9ced6' },
  pressed: { backgroundColor: '#eef1f5' },
  deviceName: { fontSize: 16, fontWeight: '600' },
})
