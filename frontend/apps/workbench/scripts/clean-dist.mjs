import assert from 'node:assert/strict'
import { rm } from 'node:fs/promises'
import { basename, dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const dist = resolve(root, 'dist')

assert.equal(dirname(dist), root, 'dist cleanup must remain inside the workbench root')
assert.equal(basename(dist), 'dist', 'dist cleanup target must be the generated dist directory')
await rm(dist, { recursive: true, force: true })
