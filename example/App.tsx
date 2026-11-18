import { StatusBar } from 'expo-status-bar'
import { useCallback, useEffect, useState } from 'react'
import { Button, StyleSheet, Text, View } from 'react-native'
import { ble, type BluetoothAdapterState } from 'react-native-ble-bonded'

// The device-test app (decision 10): every feature gets a screen here as it lands, for the manual checklist.
export default function App() {
  const [state, setState] = useState<BluetoothAdapterState | 'loading'>('loading')
  const [error, setError] = useState<string>()

  const refresh = useCallback(async () => {
    try {
      setError(undefined)
      setState(await ble.getState())
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    }
  }, [])

  useEffect(() => {
    refresh()
  }, [refresh])

  return (
    <View style={styles.container}>
      <Text style={styles.label}>Bluetooth</Text>
      <Text style={styles.value} testID="bluetooth-state">
        {state}
      </Text>
      {error != null && <Text style={styles.error}>{error}</Text>}
      <Button title="Refresh" onPress={refresh} />
      <StatusBar style="auto" />
    </View>
  )
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#fff', alignItems: 'center', justifyContent: 'center', gap: 12 },
  label: { fontSize: 14, color: '#5a6577' },
  value: { fontSize: 28, fontWeight: '600' },
  error: { color: '#b3261e', paddingHorizontal: 24, textAlign: 'center' },
})
