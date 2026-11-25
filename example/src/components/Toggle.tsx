import { Switch } from 'react-native-paper'
import { theme } from '../theme'

/** The off track's grey: the theme's light grey nearly disappears on white. */
const offTrack = '#BDBDBD'

/** A switch that reads clearly in both states: grey when off, the action colour when on. */
export function Toggle(props: { value: boolean; onValueChange: (value: boolean) => void; testID?: string }) {
  return <Switch {...props} trackColor={{ false: offTrack, true: theme.colors.primary }} ios_backgroundColor={offTrack} />
}
