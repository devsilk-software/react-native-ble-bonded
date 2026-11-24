import { useState } from 'react'
import { ScrollView, StatusBar, StyleSheet, useColorScheme, View } from 'react-native'
import { Appbar, Button, Chip, MD3DarkTheme, MD3LightTheme, PaperProvider, SegmentedButtons, useTheme } from 'react-native-paper'
import { SafeAreaProvider, SafeAreaView } from 'react-native-safe-area-context'
import { ble } from 'react-native-ble-bonded'
import { useBluetoothPermission, useBluetoothState } from 'react-native-ble-bonded/hooks'
import { DeviceScreen } from './screens/DeviceScreen'
import { DiagnosticsScreen } from './screens/DiagnosticsScreen'
import { ScanScreen } from './screens/ScanScreen'

type Tab = 'scan' | 'device' | 'diagnostics'

// The device-test app: every feature gets a screen here as it lands, for the manual checks on a phone.
export default function App() {
  const isDark = useColorScheme() === 'dark'
  return (
    <SafeAreaProvider>
      {/* No icon font: the app shows text only, so nothing needs registering natively. */}
      <PaperProvider theme={isDark ? MD3DarkTheme : MD3LightTheme} settings={{ icon: () => null }}>
        <StatusBar barStyle={isDark ? 'light-content' : 'dark-content'} />
        <Main />
      </PaperProvider>
    </SafeAreaProvider>
  )
}

function Main() {
  const theme = useTheme()
  const [tab, setTab] = useState<Tab>('scan')
  const [selected, setSelected] = useState<string>()

  const select = (id: string) => {
    setSelected(id)
    setTab('device')
  }

  return (
    <SafeAreaView style={[styles.screen, { backgroundColor: theme.colors.background }]} edges={['left', 'right', 'bottom']}>
      <Appbar.Header elevated>
        <Appbar.Content title='Bonded' />
      </Appbar.Header>
      <Status />
      <SegmentedButtons
        style={styles.tabs}
        value={tab}
        onValueChange={(value) => setTab(value as Tab)}
        buttons={[
          { value: 'scan', label: 'Scan', testID: 'tab-scan' },
          { value: 'device', label: 'Device', disabled: selected == null, testID: 'tab-device' },
          { value: 'diagnostics', label: 'Diagnostics', testID: 'tab-diagnostics' },
        ]}
      />
      <ScrollView contentContainerStyle={styles.content} keyboardShouldPersistTaps='handled'>
        {tab === 'scan' && <ScanScreen onSelect={select} />}
        {tab === 'device' && selected != null && <DeviceScreen id={selected} />}
        {tab === 'diagnostics' && <DiagnosticsScreen />}
      </ScrollView>
    </SafeAreaView>
  )
}

// Bluetooth's state and the permission, with the one action that fixes each when needed.
function Status() {
  const state = useBluetoothState()
  const permission = useBluetoothPermission()
  return (
    <View style={styles.status}>
      <Chip compact testID='bluetooth-state'>
        Bluetooth: {state}
      </Chip>
      <Chip compact testID='bluetooth-permission'>
        Permission: {permission ?? '…'}
      </Chip>
      {permission === 'denied' && (
        <Button compact mode='text' onPress={() => ble.requestPermissions()}>
          Request permission
        </Button>
      )}
      {permission === 'blocked' && (
        <Button compact mode='text' onPress={() => ble.openSettings()}>
          Open settings
        </Button>
      )}
      {state === 'poweredOff' && (
        <Button compact mode='text' onPress={() => ble.requestBluetoothOn()}>
          Turn Bluetooth on
        </Button>
      )}
    </View>
  )
}

const styles = StyleSheet.create({
  screen: { flex: 1 },
  status: { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: 8, paddingHorizontal: 16, paddingTop: 12 },
  tabs: { marginHorizontal: 16, marginTop: 12 },
  content: { padding: 16, paddingBottom: 48 },
})
