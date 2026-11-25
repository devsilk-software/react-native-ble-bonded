import { useState } from 'react'
import { Share, StyleSheet, View } from 'react-native'
import { Button, Divider, List, Switch, Text } from 'react-native-paper'
import { ble, type DiagnosticEvent } from 'react-native-ble-bonded'
import { useDiagnosticEvents } from 'react-native-ble-bonded/hooks'
import { ButtonRow } from '../components/Row'
import { Section } from '../components/Section'

// Diagnostics are off by default; this screen turns them on for testing and shows what Bonded recorded.
export function DiagnosticsScreen() {
  const [isEnabled, setIsEnabled] = useState(false)
  const [isGatt, setIsGatt] = useState(false)
  const events = useDiagnosticEvents({ limit: 50 })

  const toggle = async (enabled: boolean) => {
    await ble.configure({ diagnostics: { enabled, gatt: isGatt } })
    setIsEnabled(enabled)
  }

  const toggleGatt = async (gatt: boolean) => {
    await ble.configure({ diagnostics: { gatt } })
    setIsGatt(gatt)
  }

  return (
    <View>
      <Section title='Diagnostics' right={<Switch value={isEnabled} onValueChange={toggle} testID='diagnostics-toggle' />}>
        {isEnabled ? (
          <>
            <View style={styles.setting}>
              <Text variant='bodyMedium'>Record reads and writes too</Text>
              <Switch value={isGatt} onValueChange={toggleGatt} />
            </View>
            {/* Only once something is recorded: there's nothing to share or clear before. */}
            {events.length > 0 && (
              <ButtonRow>
                <Button mode='contained-tonal' onPress={share} testID='diagnostics-share'>
                  Share
                </Button>
                <Button mode='outlined' onPress={() => ble.clearDiagnostics()} testID='diagnostics-clear'>
                  Clear
                </Button>
              </ButtonRow>
            )}
          </>
        ) : (
          <Text variant='bodySmall' style={styles.hint}>
            Off: nothing is recorded. Turn it on to record what happens, for a bug report.
          </Text>
        )}
      </Section>
      <Section title='Events'>
        <Events events={events} />
      </Section>
    </View>
  )
}

// The whole record as JSON, through the system share sheet: what a bug report attaches.
async function share() {
  const record = await ble.getDiagnostics()
  await Share.share({ title: 'Bonded diagnostics', message: JSON.stringify(record, null, 2) })
}

function Events({ events }: { events: DiagnosticEvent[] }) {
  if (events.length === 0)
    return (
      <Text variant='bodySmall' style={styles.hint}>
        No events recorded.
      </Text>
    )
  return (
    <View testID='diagnostic-events'>
      {[...events].reverse().map((event, index) => (
        <View key={`${event.at}-${index}`}>
          {index > 0 && <Divider />}
          <List.Item title={event.type} description={describe(event)} descriptionNumberOfLines={3} />
        </View>
      ))}
    </View>
  )
}

function describe(event: DiagnosticEvent): string {
  const time = new Date(event.at).toLocaleTimeString()
  const error = event.code != null ? `  ${event.code}${event.platformCode != null ? ` (${event.platformCode})` : ''}` : ''
  return `${time}  ${event.appState}${event.detail != null ? `  ${event.detail}` : ''}${error}`
}

const styles = StyleSheet.create({
  setting: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', marginTop: 8 },
  hint: { opacity: 0.7 },
})
