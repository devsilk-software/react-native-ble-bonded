import { useState } from 'react'
import { Button, FlatList, SafeAreaView, StatusBar, StyleSheet, Text, View } from 'react-native'
import { ble, type DiagnosticEvent } from 'react-native-ble-bonded'
import { useBluetoothPermission, useBluetoothState, useDiagnosticEvents } from 'react-native-ble-bonded/hooks'

// The device-test app: every feature gets a screen here as it lands, for the manual checklist.
export default function App() {
  const state = useBluetoothState()
  const permission = useBluetoothPermission()
  const [lastRequest, setLastRequest] = useState<string>()

  const request = async () => {
    setLastRequest(`request returned ${await ble.requestPermissions()}`)
  }

  return (
    <SafeAreaView style={styles.screen}>
      <StatusBar barStyle="dark-content" />
      <View style={styles.status}>
        <Text style={styles.label}>Bluetooth</Text>
        <Text style={styles.value} testID="bluetooth-state">
          {state}
        </Text>
        <Text style={styles.label}>Permission</Text>
        <Text style={styles.value} testID="bluetooth-permission">
          {permission ?? '…'}
        </Text>
        {lastRequest != null && <Text style={styles.note}>{lastRequest}</Text>}
        {permission === 'denied' && <Button title="Request permission" onPress={request} />}
        {permission === 'blocked' && <Button title="Open settings" onPress={() => ble.openSettings()} />}
      </View>
      <Diagnostics />
    </SafeAreaView>
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
    <View style={styles.diagnostics}>
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
  status: { alignItems: 'center', justifyContent: 'center', gap: 12, paddingVertical: 32 },
  label: { fontSize: 14, color: '#5a6577' },
  value: { fontSize: 28, fontWeight: '600' },
  note: { fontSize: 13, color: '#5a6577', textAlign: 'center', paddingVertical: 8 },
  diagnostics: { flex: 1, borderTopWidth: StyleSheet.hairlineWidth, borderColor: '#c9ced6', paddingHorizontal: 16 },
  row: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingVertical: 8 },
  event: { fontSize: 12, fontFamily: 'Menlo', paddingVertical: 2 },
})
