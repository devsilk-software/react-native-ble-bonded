import js from '@eslint/js'
import boundaries from 'eslint-plugin-boundaries'
import prettier from 'eslint-plugin-prettier/recommended'
import tseslint from 'typescript-eslint'

// The TypeScript layers and what each may import (decision A5):
// specs ← api ← hooks; plugin stands alone.
export default tseslint.config(
  {
    ignores: ['lib/', 'nitrogen/generated/', 'node_modules/', 'example/', 'android/', 'ios/', '*.config.js'],
  },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  prettier,
  {
    files: ['src/**/*.ts', 'src/**/*.tsx'],
    plugins: { boundaries },
    settings: {
      'boundaries/elements': [
        { type: 'specs', pattern: 'src/specs/**/*', mode: 'file' },
        { type: 'api', pattern: 'src/api/**/*', mode: 'file' },
        { type: 'hooks', pattern: 'src/hooks/**/*', mode: 'file' },
        { type: 'plugin', pattern: 'src/plugin/**/*', mode: 'file' },
        { type: 'entry', pattern: 'src/index.ts', mode: 'file' },
      ],
      'boundaries/include': ['src/**/*'],
      'import/resolver': { typescript: { project: './tsconfig.test.json' } },
    },
    rules: {
      'boundaries/element-types': [
        'error',
        {
          default: 'disallow',
          rules: [
            { from: 'specs', allow: [] },
            { from: 'api', allow: ['specs', 'api'] },
            { from: 'hooks', allow: ['api', 'hooks'] },
            { from: 'plugin', allow: ['plugin'] },
            { from: 'entry', allow: ['api', 'specs', 'hooks'] },
          ],
        },
      ],
    },
  }
)
