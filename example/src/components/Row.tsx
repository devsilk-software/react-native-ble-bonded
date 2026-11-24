import type { ReactNode } from 'react'
import { StyleSheet, View } from 'react-native'

/** Buttons side by side, wrapping onto the next line instead of running off the screen. */
export function ButtonRow({ children }: { children: ReactNode }) {
  return <View style={styles.row}>{children}</View>
}

const styles = StyleSheet.create({
  row: { flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginTop: 12 },
})
