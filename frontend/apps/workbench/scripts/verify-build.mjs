import assert from 'node:assert/strict'
import { readdir, readFile } from 'node:fs/promises'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const dist = join(root, 'dist')
const indexHtml = await readFile(join(dist, 'index.html'), 'utf8')
const assets = await readdir(join(dist, 'assets'))
const initialScripts = [...indexHtml.matchAll(/(?:src|href)="\/assets\/([^"?]+\.js)"/g)].map(match => match[1])
const entryName = initialScripts.find(name => name.startsWith('index-'))

assert.ok(initialScripts.length > 0, 'production index must reference an entry module')
assert.ok(entryName, 'production index must reference the workbench entry module')
assert.ok(assets.some(name => /^element-plus-.*\.js$/.test(name)), 'Element Plus chunk is missing')
assert.equal(
  initialScripts.some(name => /^echarts-.*\.js$/.test(name)),
  false,
  'ECharts must not be part of the initial HTML module graph',
)
const entrySource = await readFile(join(dist, 'assets', entryName), 'utf8')
const lazyEchartsName = entrySource.match(/(?:\.\/|assets\/)(echarts-[A-Za-z0-9_-]+\.js)/u)?.[1]
assert.ok(lazyEchartsName && assets.includes(lazyEchartsName), 'entry must reference an emitted ECharts lazy chunk')
console.log(`Workbench build smoke check passed (${initialScripts.length} initial modules, ${assets.length} assets)`)
