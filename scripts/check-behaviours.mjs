// Checks that docs/behaviours.md and the native tests name the same behaviours (decision A3):
// every listed behaviour has a test with exactly that name on each platform it applies to, and every test's
// name is listed.
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join } from 'node:path'

const root = new URL('..', import.meta.url).pathname
const platforms = {
  iOS: { dir: 'ios/BondedFeatures/Tests', ext: '.swift', pattern: /@Test\(\s*"([^"]+)"/g },
  Android: { dir: 'android/BondedFeatures/src/test', ext: '.kt', pattern: /\b(?:then|test)\(\s*"([^"]+)"/g },
}

function listed() {
  const markdown = readFileSync(join(root, 'docs/behaviours.md'), 'utf8')
  return markdown
    .split('\n')
    .filter((line) => line.startsWith('- '))
    .map((line) => {
      const [name, note] = line.slice(2).split(' — ')
      const only = note?.match(/^(iOS|Android) only$/)?.[1]
      return { name: name.trim(), platforms: only ? [only] : Object.keys(platforms) }
    })
}

function files(dir, ext) {
  return readdirSync(dir).flatMap((entry) => {
    const path = join(dir, entry)
    if (statSync(path).isDirectory()) return files(path, ext)
    return path.endsWith(ext) ? [path] : []
  })
}

function testNames({ dir, ext, pattern }) {
  const names = new Set()
  for (const file of files(join(root, dir), ext)) {
    for (const match of readFileSync(file, 'utf8').matchAll(pattern)) names.add(match[1])
  }
  return names
}

const behaviours = listed()
const problems = []
for (const [platform, config] of Object.entries(platforms)) {
  const names = testNames(config)
  for (const behaviour of behaviours) {
    if (behaviour.platforms.includes(platform) && !names.has(behaviour.name)) {
      problems.push(`${platform} has no test named "${behaviour.name}"`)
    }
  }
  for (const name of names) {
    if (!behaviours.some((behaviour) => behaviour.name === name)) {
      problems.push(`${platform} test "${name}" is not listed in docs/behaviours.md`)
    }
  }
}

if (problems.length > 0) {
  console.error(problems.join('\n'))
  process.exit(1)
}
console.log(`${behaviours.length} behaviours, each tested on every platform it applies to.`)
