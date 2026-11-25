import { useEffect, useRef, useState } from 'react'
import { Platform, StyleSheet, View } from 'react-native'
import { Button, Chip, Divider, List, Text } from 'react-native-paper'
import { ble, type BondState, type Service } from 'react-native-ble-bonded'
import { useDevice, useNotifications, useRssi } from 'react-native-ble-bonded/hooks'
import { OutcomeCard, outcomeOf, type Outcome } from '../components/Outcome'
import { ButtonRow } from '../components/Row'
import { Section } from '../components/Section'
import { testNotify, testRead, testSecure, testService, testWrite } from '../testPeripheral'

const ascii = (text: string) => Uint8Array.from(text, (character) => character.charCodeAt(0))

// 0, 1, 2, … 255, 0, …: the test peripheral checks that the chunks arrive in this order.
const countingPattern = Uint8Array.from({ length: 2_000 }, (_, index) => index % 256)

export function DeviceScreen({ id }: { id: string }) {
  const device = useDevice(id)
  const [services, setServices] = useState<Service[]>()
  // Where the last action's outcome shows: next to the buttons that caused it.
  const [outcome, setOutcome] = useState<{ section: Area; outcome: Outcome }>()
  const [busy, setBusy] = useState<string>()

  const run = (name: string, section: Area, action: () => Promise<string | undefined>) => async () => {
    setBusy(name)
    setOutcome(undefined)
    try {
      const text = await action()
      if (text != null) setOutcome({ section, outcome: { kind: 'result', text } })
    } catch (error) {
      setOutcome({ section, outcome: outcomeOf(error) })
    } finally {
      setBusy(undefined)
    }
  }

  const isConnected = device.connectionState === 'connected'
  const bondState = useBondState(id)
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
          {isConnected && <SignalChip id={id} />}
        </View>
        {last != null && (
          <Text variant='bodySmall' style={styles.last} testID='last-disconnect'>
            Last disconnect: {last.reason}
            {last.error != null
              ? ` (${last.error.code}${last.error.platformCode != null ? `, platform code ${last.error.platformCode}` : ''})`
              : ''}
          </Text>
        )}
        {/* Only the actions that can work now: no Connect while connected, no Disconnect while disconnected. */}
        <ButtonRow>
          {device.connectionState === 'disconnected' && (
            <Button
              mode='contained'
              onPress={run('connect', 'connection', async () => (await device.connect(), 'Connected'))}
              testID='connect'
            >
              Connect
            </Button>
          )}
          {device.connectionState === 'connecting' && (
            <Button
              mode='outlined'
              onPress={run('disconnect', 'connection', async () => (await device.disconnect(), 'Cancelled'))}
              testID='disconnect'
            >
              Cancel connecting
            </Button>
          )}
          {isConnected && (
            <Button
              mode='outlined'
              onPress={run('disconnect', 'connection', async () => (await device.disconnect(), 'Disconnected'))}
              testID='disconnect'
            >
              Disconnect
            </Button>
          )}
          {device.isRemembered && device.connectionState !== 'disconnecting' && (
            <Button mode='outlined' onPress={run('forget', 'connection', async () => (await device.forget(), 'Forgotten'))} testID='forget'>
              Forget
            </Button>
          )}
        </ButtonRow>
        {outcome?.section === 'connection' && <OutcomeCard outcome={outcome.outcome} testID='connection-outcome' />}
      </Section>

      {isConnected && (
        <Section title='Read and write'>
          <Text variant='bodySmall' style={styles.hint}>
            Against the Mac test peripheral.
          </Text>
          <ButtonRow>
            <Button
              mode='contained-tonal'
              onPress={run(
                'read',
                'gatt',
                async () => `Read "${String.fromCharCode(...new Uint8Array(await device.read(testService, testRead)))}"`
              )}
              loading={busy === 'read'}
              testID='read'
            >
              Read
            </Button>
            <Button
              mode='contained-tonal'
              onPress={run('write', 'gatt', async () => (await device.write(testService, testWrite, ascii('hello')), 'Wrote "hello"'))}
              loading={busy === 'write'}
              testID='write'
            >
              Write
            </Button>
            <Button
              mode='contained-tonal'
              onPress={run(
                'writeNoResponse',
                'gatt',
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
            Longer than one write
          </Text>
          <ButtonRow>
            <Button
              mode='contained-tonal'
              onPress={run('maxWriteLength', 'gatt', async () => `One write carries at most ${await device.getMaxWriteLength()} bytes`)}
              loading={busy === 'maxWriteLength'}
              testID='max-write-length'
            >
              Max write length
            </Button>
            <Button
              mode='contained-tonal'
              onPress={run('chunked', 'gatt', async () => {
                const size = await device.getMaxWriteLength()
                await device.writeChunked(testService, testWrite, countingPattern, { withoutResponse: true })
                return `Wrote ${countingPattern.length} bytes in ${Math.ceil(countingPattern.length / size)} chunks of up to ${size}`
              })}
              loading={busy === 'chunked'}
              testID='write-chunked'
            >
              Write 2,000 bytes in chunks
            </Button>
          </ButtonRow>
          <Text variant='bodySmall' style={styles.subtitle}>
            Descriptors
          </Text>
          <ButtonRow>
            <Button
              mode='contained-tonal'
              onPress={run(
                'readDescriptor',
                'gatt',
                async () =>
                  `Read the counter's description: "${String.fromCharCode(...new Uint8Array(await device.readDescriptor(testService, testNotify, '2901')))}"`
              )}
              loading={busy === 'readDescriptor'}
              testID='read-descriptor'
            >
              Read the counter's description
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
                'gatt',
                async () => (await device.write(testService, testWrite, new Uint8Array(600), { withoutResponse: true }), undefined)
              )}
              testID='write-too-long'
            >
              600 bytes without response
            </Button>
            <Button
              mode='outlined'
              onPress={run('wrongOp', 'gatt', async () => (await device.read(testService, testWrite), undefined))}
              testID='read-write-only'
            >
              Read a write-only characteristic
            </Button>
            <Button
              mode='outlined'
              onPress={run(
                'writeCccd',
                'gatt',
                async () => (await device.writeDescriptor(testService, testNotify, '2902', new Uint8Array([1, 0])), undefined)
              )}
              testID='write-notification-descriptor'
            >
              Write the notification descriptor
            </Button>
            <Button
              mode='outlined'
              onPress={run(
                'writeReadOnly',
                'gatt',
                async () => (await device.writeDescriptor(testService, testNotify, '2901', ascii('Count')), undefined)
              )}
              testID='write-read-only-descriptor'
            >
              Write a read-only descriptor
            </Button>
          </ButtonRow>
          {outcome?.section === 'gatt' && <OutcomeCard outcome={outcome.outcome} testID='operation-result' />}
        </Section>
      )}

      <Notifications id={id} isConnected={isConnected} />

      {isConnected && (
        <Section
          title='Bonding'
          right={
            Platform.OS === 'android' && bondState != null ? (
              <Chip compact testID='bond-state'>
                {bondState}
              </Chip>
            ) : undefined
          }
        >
          <Text variant='bodySmall' style={styles.hint}>
            Pairs by reading the test peripheral's secure characteristic, which needs encryption.
          </Text>
          <ButtonRow>
            {bondState !== 'bonded' && (
              <Button
                mode='contained-tonal'
                onPress={run(
                  'pair',
                  'bonding',
                  async () => (
                    await device.createBond({ trigger: { service: testService, characteristic: testSecure } }),
                    'Paired: encrypted reads work'
                  )
                )}
                loading={busy === 'pair'}
                testID='create-bond'
              >
                Pair
              </Button>
            )}
            {bondState === 'bonded' && (
              <Button
                mode='outlined'
                onPress={run('unpair', 'bonding', async () => (await device.android.removeBond(), 'Bond removed'))}
                loading={busy === 'unpair'}
                testID='remove-bond'
              >
                Remove bond
              </Button>
            )}
          </ButtonRow>
          {outcome?.section === 'bonding' && <OutcomeCard outcome={outcome.outcome} testID='bonding-outcome' />}
        </Section>
      )}

      <Section
        title='Services'
        right={
          isConnected && (
            <Button
              compact
              onPress={run('services', 'services', async () => (setServices(await device.getServices({ descriptors: true })), undefined))}
              testID='services'
            >
              Load
            </Button>
          )
        }
      >
        {outcome?.section === 'services' && <OutcomeCard outcome={outcome.outcome} />}
        {(isConnected ? services : undefined)?.map((service) => (
          <View key={service.uuid}>
            <Divider />
            <List.Item title={service.uuid} titleNumberOfLines={2} titleStyle={styles.mono} />
            {service.characteristics.map((characteristic) => (
              <List.Item
                key={characteristic.uuid}
                title={characteristic.uuid}
                titleNumberOfLines={2}
                titleStyle={styles.mono}
                description={[
                  characteristic.properties.join(', '),
                  ...(characteristic.descriptors ?? []).map((uuid) => `descriptor ${uuid}`),
                ].join('\n')}
                descriptionNumberOfLines={6}
                style={styles.characteristic}
              />
            ))}
          </View>
        )) ?? (
          <Text variant='bodySmall' style={styles.hint}>
            {isConnected ? 'Load them to see what the device offers.' : 'Connect to see what the device offers and to read and write.'}
          </Text>
        )}
      </Section>
    </View>
  )
}

type Area = 'connection' | 'gatt' | 'services' | 'bonding'

// Android's bond state, live; iOS doesn't let apps see bonds, so there it stays undefined.
function useBondState(id: string): BondState | undefined {
  const [state, setState] = useState<BondState>()
  useEffect(() => {
    if (Platform.OS !== 'android') return
    const subscription = ble.device(id).android.onBondStateChange(setState)
    return () => subscription.remove()
  }, [id])
  return state
}

// The Mac test peripheral's counter, live. The subscription outlives disconnects while this stays on, and ends
// when the device is forgotten.
function Notifications({ id, isConnected }: { id: string; isConnected: boolean }) {
  const [isOn, setIsOn] = useState(false)
  // Subscribe is offered only while connected; once on, it stays on across disconnects until Stop or Forget.
  if (!isOn && !isConnected) return null
  return (
    <Section title='Notifications'>
      {isOn ? (
        <LiveValue id={id} onEnd={() => setIsOn(false)} />
      ) : (
        <Text variant='bodySmall' style={styles.hint}>
          The Mac test peripheral counts up every second.
        </Text>
      )}
      <ButtonRow>
        <Button mode={isOn ? 'outlined' : 'contained-tonal'} onPress={() => setIsOn(!isOn)} testID='notifications-toggle'>
          {isOn ? 'Stop' : 'Subscribe'}
        </Button>
      </ButtonRow>
    </Section>
  )
}

// The signal strength, read every second while connected.
function SignalChip({ id }: { id: string }) {
  const { rssi } = useRssi(useDevice(id))
  if (rssi == null) return null
  return (
    <Chip compact testID='rssi'>
      {rssi} dBm
    </Chip>
  )
}

function LiveValue({ id, onEnd }: { id: string; onEnd: () => void }) {
  const device = useDevice(id)
  const { isSubscribed, value, error } = useNotifications(device, testService, testNotify)
  // The subscription ended without Stop: the device was forgotten.
  const wasSubscribed = useRef(false)
  useEffect(() => {
    if (isSubscribed) wasSubscribed.current = true
    else if (wasSubscribed.current) onEnd()
  }, [isSubscribed, onEnd])
  return (
    <>
      <Text variant='headlineSmall' testID='notified-value'>
        {value == null ? 'Waiting for a value…' : String.fromCharCode(...new Uint8Array(value))}
      </Text>
      {device.connectionState !== 'connected' && (
        <Text variant='bodySmall' style={styles.hint} testID='notifications-paused'>
          {device.connectionState === 'disconnected' ? 'Disconnected' : 'Not connected yet'}: notifications resume when the device
          reconnects.
        </Text>
      )}
      {error != null && <OutcomeCard outcome={outcomeOf(error)} />}
    </>
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
