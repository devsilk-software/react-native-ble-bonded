// https://github.com/react-native-community/cli/blob/main/docs/dependencies.md

module.exports = {
  dependency: {
    platforms: {
      /**
       * @type {import('@react-native-community/cli-types').IOSDependencyParams}
       */
      ios: {},
      /**
       * Bonded has no native components. Declaring none stops the CLI from scanning the whole package for them:
       * in this repository that scan would walk into example/ and follow its links back to the package root
       * without end.
       *
       * @type {import('@react-native-community/cli-types').AndroidDependencyParams}
       */
      android: {
        componentDescriptors: [],
      },
    },
  },
}
