import { useState } from 'react'
import { Button, FlatList, SafeAreaView, StatusBar, StyleSheet, Text, View } from 'react-native'
import { ble, type DiagnosticEvent, type ScannedDevice } from 'react-native-ble-bonded'
import { useBluetoothPermission, useBluetoothState, useDiagnosticEvents, useScan } from 'react-native-ble-bonded/hooks'

type Tab = 'scan' | 'diagnostics'

// The device-test app: every feature gets a screen here as it lands, for the manual checklist.
export default function App() {
  const state = useBluetoothState()
  const permission = useBluetoothPermission()
  const [tab, setTab] = useState<Tab>('scan')

  return (
    <SafeAreaView style={styles.screen}>
      <StatusBar barStyle="dark-content" />
      <View style={styles.status}>
        <Text style={styles.label}>
          Bluetooth{' '}
          <Text style={styles.value} testID="bluetooth-state">
            {state}
          </Text>
        </Text>
        <Text style={styles.label}>
          Permission{' '}
          <Text style={styles.value} testID="bluetooth-permission">
            {permission ?? '…'}
          </Text>
        </Text>
      </View>
      <View style={styles.row}>
        {permission === 'denied' && <Button title="Request permission" onPress={() => ble.requestPermissions()} />}
        {permission === 'blocked' && <Button title="Open settings" onPress={() => ble.openSettings()} />}
        {state === 'poweredOff' && <Button title="Turn Bluetooth on" onPress={() => ble.requestBluetoothOn()} />}
      </View>
      <View style={styles.tabs}>
        <Button title="Scan" onPress={() => setTab('scan')} disabled={tab === 'scan'} testID="tab-scan" />
        <Button title="Diagnostics" onPress={() => setTab('diagnostics')} disabled={tab === 'diagnostics'} testID="tab-diagnostics" />
      </View>
      {tab === 'scan' ? <Scanning /> : <Diagnostics />}
    </SafeAreaView>
  )
}

// useScan scans while mounted, so the list mounts only while scanning is wanted.
function Scanning() {
  const [isWanted, setIsWanted] = useState(false)
  return (
    <View style={styles.section}>
      <View style={styles.row}>
        <Text style={styles.label}>Scanning {isWanted ? 'wanted' : 'off'}</Text>
        <Button title={isWanted ? 'Stop' : 'Scan'} onPress={() => setIsWanted(!isWanted)} testID="scan-toggle" />
      </View>
      {isWanted && <DeviceList />}
    </View>
  )
}

function DeviceList() {
  const { devices, isScanning, error, restart } = useScan({ timeout: 30_000 })
  return (
    <>
      <View style={styles.row}>
        <Text style={styles.note} testID="scan-status">
          {isScanning ? `scanning, ${devices.length} found` : `stopped, ${devices.length} found`}
          {error != null ? ` (${error.code}: ${error.message})` : ''}
        </Text>
        <Button title="Restart" onPress={restart} disabled={isScanning} testID="scan-restart" />
      </View>
      <FlatList
        testID="scanned-devices"
        data={devices}
        keyExtractor={(device) => device.id}
        renderItem={({ item }) => <Text style={styles.event}>{describeDevice(item)}</Text>}
        ListEmptyComponent={<Text style={styles.note}>No devices yet</Text>}
      />
    </>
  )
}

function describeDevice(device: ScannedDevice): string {
  const company = device.manufacturerData == null ? '' : `  company 0x${device.manufacturerData.companyId.toString(16).padStart(4, '0')}`
  return `${device.rssi} dBm  ${device.name ?? '(no name)'}  ${device.id}${company}`
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
        <Button title={isEnabled ? 'Turn off' : 'Turn on'} onPress={toggle} testID="diagnostics-toggle" />
        <Button title="Clear" onPress={() => ble.clearDiagnostics()} disabled={!isEnabled} />
      </View>
      <EventList />
    </View>
  )
}

function EventList() {
  const events = useDiagnosticEvents({ limit: 50 })
  return (
    <FlatList
      testID="diagnostic-events"
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
})
