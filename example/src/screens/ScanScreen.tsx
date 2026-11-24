import { useState } from 'react'
import { FlatList, StyleSheet, View } from 'react-native'
import { Button, Divider, List, Switch, Text } from 'react-native-paper'
import { ble, type ScannedDevice } from 'react-native-ble-bonded'
import { useRememberedDevices, useScan } from 'react-native-ble-bonded/hooks'
import { OutcomeCard, outcomeOf, type Outcome } from '../components/Outcome'
import { ButtonRow } from '../components/Row'
import { Section } from '../components/Section'
import { testService } from '../testPeripheral'

export function ScanScreen({ onSelect }: { onSelect: (id: string) => void }) {
  const [isScanning, setIsScanning] = useState(false)
  const [isTestOnly, setIsTestOnly] = useState(true)
  return (
    <View style={styles.screen}>
      <Remembered onSelect={onSelect} />
      <Section title='Scan'>
        <View style={styles.setting}>
          <Text variant='bodyMedium'>Mac test peripheral only</Text>
          <Switch value={isTestOnly} onValueChange={setIsTestOnly} testID='scan-test-only' />
        </View>
        <ButtonRow>
          <Button mode={isScanning ? 'outlined' : 'contained'} onPress={() => setIsScanning(!isScanning)} testID='scan-toggle'>
            {isScanning ? 'Stop' : 'Scan'}
          </Button>
        </ButtonRow>
      </Section>
      {isScanning ? (
        <Devices onSelect={onSelect} services={isTestOnly ? [testService] : []} />
      ) : (
        <Text variant='bodyMedium' style={styles.hint}>
          Tap Scan to look for devices.
        </Text>
      )}
    </View>
  )
}

// ble.connect() without arguments: what a one-device app calls at startup.
function Remembered({ onSelect }: { onSelect: (id: string) => void }) {
  const remembered = useRememberedDevices()
  const [isConnecting, setIsConnecting] = useState(false)
  const [outcome, setOutcome] = useState<Outcome>()

  // With one remembered device, ble.connect() without arguments, as a one-device app calls it at startup.
  const connect = async (id: string) => {
    setIsConnecting(true)
    setOutcome(undefined)
    try {
      const device = remembered.length === 1 ? await ble.connect() : await ble.connect({ id })
      onSelect(device.id)
    } catch (error) {
      setOutcome(outcomeOf(error))
    } finally {
      setIsConnecting(false)
    }
  }

  return (
    <Section title='Remembered'>
      {remembered.length === 0 && (
        <Text variant='bodyMedium' testID='remembered-count'>
          None yet: a device is remembered once it connects.
        </Text>
      )}
      {remembered.map((device, index) => (
        <View key={device.id}>
          {index > 0 && <Divider />}
          <List.Item
            title={device.name ?? device.id}
            description={device.connectionState}
            onPress={() => onSelect(device.id)}
            testID={`remembered-${device.name ?? device.id}`}
            right={() =>
              device.connectionState === 'disconnected' ? (
                <Button mode='contained' compact onPress={() => connect(device.id)} loading={isConnecting} testID='connect-remembered'>
                  Connect
                </Button>
              ) : null
            }
          />
        </View>
      ))}
      {outcome != null && <OutcomeCard outcome={outcome} />}
    </Section>
  )
}

function Devices({ onSelect, services }: { onSelect: (id: string) => void; services: string[] }) {
  const { devices, isScanning, error, restart } = useScan({ services, timeout: 30_000 })
  return (
    <Section
      title={isScanning ? `Scanning: ${devices.length} found` : `Stopped: ${devices.length} found`}
      right={
        !isScanning && (
          <Button compact onPress={restart} testID='scan-restart'>
            Restart
          </Button>
        )
      }
    >
      {error != null && <OutcomeCard outcome={outcomeOf(error)} testID='scan-status' />}
      <FlatList
        testID='scanned-devices'
        data={devices}
        scrollEnabled={false}
        keyExtractor={(device) => device.id}
        ItemSeparatorComponent={Divider}
        renderItem={({ item }) => (
          <List.Item
            title={item.name ?? '(no name)'}
            description={describe(item)}
            descriptionNumberOfLines={2}
            onPress={() => onSelect(item.id)}
            right={() => (
              <Text variant='labelLarge' style={styles.rssi}>
                {item.rssi} dBm
              </Text>
            )}
            testID={`device-${item.name ?? item.id}`}
          />
        )}
        ListEmptyComponent={
          <Text variant='bodyMedium' style={styles.hint}>
            No devices yet.
          </Text>
        }
      />
    </Section>
  )
}

function describe(device: ScannedDevice): string {
  const company = device.manufacturerData == null ? '' : `\ncompany 0x${device.manufacturerData.companyId.toString(16).padStart(4, '0')}`
  return `${device.id}${company}`
}

const styles = StyleSheet.create({
  screen: { gap: 0 },
  setting: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', marginTop: 8 },
  hint: { opacity: 0.7, paddingVertical: 8 },
  rssi: { alignSelf: 'center' },
})
