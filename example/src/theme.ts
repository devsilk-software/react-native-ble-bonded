import { MD3LightTheme, type MD3Theme } from 'react-native-paper'

/** White background, black content, one colour for actions. */
const main = '#0082FC'
const black = '#111111'
const grey = '#F2F2F2'
const line = '#D9D9D9'

export const theme: MD3Theme = {
  ...MD3LightTheme,
  colors: {
    ...MD3LightTheme.colors,
    primary: main,
    onPrimary: '#FFFFFF',
    primaryContainer: '#E5F2FF',
    onPrimaryContainer: black,
    secondary: black,
    onSecondary: '#FFFFFF',
    // Tonal buttons and the selected tab: light blue with blue text, so they read as actions, not as disabled.
    secondaryContainer: '#E5F2FF',
    onSecondaryContainer: main,
    tertiary: main,
    background: '#FFFFFF',
    onBackground: black,
    surface: '#FFFFFF',
    onSurface: black,
    surfaceVariant: grey,
    onSurfaceVariant: '#555555',
    outline: line,
    outlineVariant: line,
    elevation: {
      level0: 'transparent',
      level1: '#FFFFFF',
      level2: '#FFFFFF',
      level3: '#FFFFFF',
      level4: '#FFFFFF',
      level5: '#FFFFFF',
    },
  },
}
