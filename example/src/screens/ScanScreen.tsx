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

  const connect = async () => {
    setIsConnecting(true)
    setOutcome(undefined)
    try {
      const device = await ble.connect()
      onSelect(device.id)
    } catch (error) {
      setOutcome(outcomeOf(error))
    } finally {
      setIsConnecting(false)
    }
  }

  return (
    <Section title='Remembered'>
      <Text variant='bodyMedium' testID='remembered-count'>
        {remembered.length === 0
          ? 'None yet: a device is remembered once it connects.'
          : remembered.map((device) => device.name ?? device.id).join(', ')}
      </Text>
      <ButtonRow>
        <Button
          mode='contained'
          onPress={connect}
          loading={isConnecting}
          disabled={remembered.length === 0 || isConnecting}
          testID='connect-remembered'
        >
          Connect remembered
        </Button>
      </ButtonRow>
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
        <Button compact onPress={restart} disabled={isScanning} testID='scan-restart'>
          Restart
        </Button>
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
