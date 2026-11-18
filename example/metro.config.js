const path = require('path')
const { getDefaultConfig, mergeConfig } = require('@react-native/metro-config')

const root = path.resolve(__dirname, '..')

/**
 * The example app loads react-native-ble-bonded from the repository root (linked in package.json), so Metro
 * watches the root and resolves modules from both node_modules folders.
 *
 * @type {import('@react-native/metro-config').MetroConfig}
 */
const config = {
  watchFolders: [root],
  resolver: {
    nodeModulesPaths: [path.resolve(__dirname, 'node_modules'), path.resolve(root, 'node_modules')],
  },
}

module.exports = mergeConfig(getDefaultConfig(__dirname), config)
