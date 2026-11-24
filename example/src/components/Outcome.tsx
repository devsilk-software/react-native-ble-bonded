import { StyleSheet } from 'react-native'
import { Card, Text, useTheme } from 'react-native-paper'
import { BleError } from 'react-native-ble-bonded'

/** What the last action did: a result, or an error with its code. Wraps, so long messages stay on screen. */
export type Outcome = { kind: 'result'; text: string } | { kind: 'error'; text: string; code?: string }

export function outcomeOf(error: unknown): Outcome {
  if (error instanceof BleError) return { kind: 'error', code: error.code, text: error.message }
  return { kind: 'error', text: error instanceof Error ? error.message : String(error) }
}

export function OutcomeCard({ outcome, testID }: { outcome: Outcome; testID?: string }) {
  const theme = useTheme()
  const isError = outcome.kind === 'error'
  return (
    <Card
      mode='contained'
      style={[styles.card, { backgroundColor: isError ? theme.colors.errorContainer : theme.colors.secondaryContainer }]}
      testID={testID}
    >
      <Card.Content>
        {isError && outcome.code != null && (
          <Text variant='labelLarge' style={{ color: theme.colors.onErrorContainer }}>
            {outcome.code}
          </Text>
        )}
        <Text
          variant='bodyMedium'
          selectable
          style={{ color: isError ? theme.colors.onErrorContainer : theme.colors.onSecondaryContainer }}
        >
          {outcome.text}
        </Text>
      </Card.Content>
    </Card>
  )
}

const styles = StyleSheet.create({
  card: { marginTop: 12 },
})
