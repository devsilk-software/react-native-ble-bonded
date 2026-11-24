import { useState } from 'react'
import { StyleSheet, View } from 'react-native'
import { Button, Chip, Divider, List, Text } from 'react-native-paper'
import type { Service } from 'react-native-ble-bonded'
import { useDevice } from 'react-native-ble-bonded/hooks'
import { OutcomeCard, outcomeOf, type Outcome } from '../components/Outcome'
import { ButtonRow } from '../components/Row'
import { Section } from '../components/Section'
import { testRead, testService, testWrite } from '../testPeripheral'

const ascii = (text: string) => Uint8Array.from(text, (character) => character.charCodeAt(0))

export function DeviceScreen({ id }: { id: string }) {
  const device = useDevice(id)
  const [services, setServices] = useState<Service[]>()
  const [outcome, setOutcome] = useState<Outcome>()
  const [busy, setBusy] = useState<string>()

  const run = (name: string, action: () => Promise<string | undefined>) => async () => {
    setBusy(name)
    setOutcome(undefined)
    try {
      const text = await action()
      if (text != null) setOutcome({ kind: 'result', text })
    } catch (error) {
      setOutcome(outcomeOf(error))
    } finally {
      setBusy(undefined)
    }
  }

  const isConnected = device.connectionState === 'connected'
  const last = device.lastDisconnect

  return (
    <View>
      <Section title={device.name ?? '(no name)'}>
        <Text variant='bodySmall' selectable style={styles.id}>
          {id}
        </Text>
        <View style={styles.chips}>
          <Chip compact testID='connection-state'>
            {device.connectionState}
          </Chip>
          {device.isRemembered && <Chip compact>remembered</Chip>}
        </View>
        {last != null && (
          <Text variant='bodySmall' style={styles.last} testID='last-disconnect'>
            Last disconnect: {last.reason}
            {last.error != null
              ? ` (${last.error.code}${last.error.platformCode != null ? `, platform code ${last.error.platformCode}` : ''})`
              : ''}
          </Text>
        )}
        <ButtonRow>
          <Button
            mode='contained'
            onPress={run('connect', async () => (await device.connect(), 'Connected'))}
            loading={busy === 'connect'}
            testID='connect'
          >
            Connect
          </Button>
          <Button mode='outlined' onPress={run('disconnect', async () => (await device.disconnect(), 'Disconnected'))} testID='disconnect'>
            Disconnect
          </Button>
          <Button
            mode='outlined'
            onPress={run('forget', async () => (await device.forget(), 'Forgotten'))}
            disabled={!device.isRemembered}
            testID='forget'
          >
            Forget
          </Button>
        </ButtonRow>
      </Section>

      <Section title='Read and write'>
        <Text variant='bodySmall' style={styles.hint}>
          Against the Mac test peripheral.
        </Text>
        <ButtonRow>
          <Button
            mode='contained-tonal'
            onPress={run('read', async () => `Read "${String.fromCharCode(...new Uint8Array(await device.read(testService, testRead)))}"`)}
            loading={busy === 'read'}
            testID='read'
          >
            Read
          </Button>
          <Button
            mode='contained-tonal'
            onPress={run('write', async () => (await device.write(testService, testWrite, ascii('hello')), 'Wrote "hello"'))}
            loading={busy === 'write'}
            testID='write'
          >
            Write
          </Button>
          <Button
            mode='contained-tonal'
            onPress={run(
              'writeNoResponse',
              async () => (
                await device.write(testService, testWrite, ascii('no response'), { withoutResponse: true }),
                'Wrote "no response" without response'
              )
            )}
            loading={busy === 'writeNoResponse'}
            testID='write-no-response'
          >
            Write without response
          </Button>
        </ButtonRow>
        <Text variant='bodySmall' style={styles.subtitle}>
          Errors on purpose
        </Text>
        <ButtonRow>
          <Button
            mode='outlined'
            onPress={run(
              'tooLong',
              async () => (await device.write(testService, testWrite, new Uint8Array(600), { withoutResponse: true }), undefined)
            )}
            testID='write-too-long'
          >
            600 bytes without response
          </Button>
          <Button
            mode='outlined'
            onPress={run('wrongOp', async () => (await device.read(testService, testWrite), undefined))}
            testID='read-write-only'
          >
            Read a write-only characteristic
          </Button>
        </ButtonRow>
        {outcome != null && <OutcomeCard outcome={outcome} testID='operation-result' />}
      </Section>

      <Section
        title='Services'
        right={
          <Button
            compact
            onPress={run('services', async () => (setServices(await device.getServices()), undefined))}
            disabled={!isConnected}
            testID='services'
          >
            Load
          </Button>
        }
      >
        {services?.map((service) => (
          <View key={service.uuid}>
            <Divider />
            <List.Item title={service.uuid} titleNumberOfLines={2} titleStyle={styles.mono} />
            {service.characteristics.map((characteristic) => (
              <List.Item
                key={characteristic.uuid}
                title={characteristic.uuid}
                titleNumberOfLines={2}
                titleStyle={styles.mono}
                description={characteristic.properties.join(', ')}
                style={styles.characteristic}
              />
            ))}
          </View>
        )) ?? (
          <Text variant='bodySmall' style={styles.hint}>
            {isConnected ? 'Load them to see what the device offers.' : 'Connect to see what the device offers.'}
          </Text>
        )}
      </Section>
    </View>
  )
}

const styles = StyleSheet.create({
  id: { opacity: 0.7, marginTop: 2 },
  chips: { flexDirection: 'row', gap: 8, marginTop: 8 },
  last: { marginTop: 8 },
  hint: { opacity: 0.7 },
  subtitle: { marginTop: 16, opacity: 0.7 },
  mono: { fontFamily: 'Menlo', fontSize: 12 },
  characteristic: { paddingLeft: 16 },
})
