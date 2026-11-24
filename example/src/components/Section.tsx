import type { ReactNode } from 'react'
import { StyleSheet, View } from 'react-native'
import { Card, Text } from 'react-native-paper'

/** A titled card: the building block of every screen. */
export function Section({ title, right, children }: { title: string; right?: ReactNode; children?: ReactNode }) {
  return (
    <Card mode='outlined' style={styles.card}>
      <Card.Content>
        <View style={styles.header}>
          <Text variant='titleMedium'>{title}</Text>
          {right}
        </View>
        {children}
      </Card.Content>
    </Card>
  )
}

const styles = StyleSheet.create({
  card: { marginBottom: 12 },
  header: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', minHeight: 36 },
})
