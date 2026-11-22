import { useState } from 'react'
import { Button, StatusBar, StyleSheet, Text, View } from 'react-native'
import { ble } from 'react-native-ble-bonded'
import { useBluetoothPermission, useBluetoothState } from 'react-native-ble-bonded/hooks'

// The device-test app: every feature gets a screen here as it lands, for the manual checklist.
export default function App() {
  const state = useBluetoothState()
  const permission = useBluetoothPermission()
  const [lastRequest, setLastRequest] = useState<string>()

  const request = async () => {
    setLastRequest(`request returned ${await ble.requestPermissions()}`)
  }

  return (
    <View style={styles.container}>
      <StatusBar barStyle="dark-content" />
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
  )
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#fff', alignItems: 'center', justifyContent: 'center', gap: 12 },
  label: { fontSize: 14, color: '#5a6577' },
  value: { fontSize: 28, fontWeight: '600' },
  note: { fontSize: 13, color: '#5a6577' },
})
