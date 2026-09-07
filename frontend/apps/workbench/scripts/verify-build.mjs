import assert from 'node:assert/strict'
import { readdir, readFile } from 'node:fs/promises'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const dist = join(root, 'dist')
const indexHtml = await readFile(join(dist, 'index.html'), 'utf8')
const assets = await readdir(join(dist, 'assets'))
assert.ok(assets.length <= 200, `production output contains ${assets.length} assets; stale build files were not cleaned`)
const initialScripts = [...indexHtml.matchAll(/(?:src|href)="\/assets\/([^"?]+\.js)"/g)].map(match => match[1])
const initialStyles = [...indexHtml.matchAll(/href="\/assets\/([^"?]+\.css)"/g)].map(match => match[1])
const entryName = initialScripts.find(name => name.startsWith('index-'))

assert.ok(initialScripts.length > 0, 'production index must reference an entry module')
assert.ok(entryName, 'production index must reference the workbench entry module')
const uniqueInitialScripts = [...new Set(initialScripts)]
const uniqueInitialStyles = [...new Set(initialStyles)]
const initialScriptBytes = (await Promise.all(uniqueInitialScripts.map(async name =>
  (await readFile(join(dist, 'assets', name))).byteLength))).reduce((sum, bytes) => sum + bytes, 0)
const initialStyleBytes = (await Promise.all(uniqueInitialStyles.map(async name =>
  (await readFile(join(dist, 'assets', name))).byteLength))).reduce((sum, bytes) => sum + bytes, 0)

// Keep the first paint bounded as the workbench gains feature modules.  The
// per-chunk check below catches an individual regression; these aggregate
// budgets catch a collection of small eagerly-loaded dependencies.
assert.ok(
  initialScriptBytes <= 1_200 * 1024,
  `initial JavaScript is ${initialScriptBytes} bytes; keep it below 1.2 MiB`,
)
assert.ok(
  initialStyleBytes <= 350 * 1024,
  `initial CSS is ${initialStyleBytes} bytes; keep it below 350 KiB`,
)
for (const vendorName of uniqueInitialScripts.filter(name => name.startsWith('vendor-'))) {
  const vendor = await readFile(join(dist, 'assets', vendorName))
  assert.ok(vendor.byteLength < 500 * 1024, `initial vendor chunk ${vendorName} is ${vendor.byteLength} bytes; keep it below 500 KiB`)
}
assert.equal(
  initialScripts.some(name => /^echarts-.*\.js$/.test(name)),
  false,
  'ECharts must not be part of the initial HTML module graph',
)
const entrySource = await readFile(join(dist, 'assets', entryName), 'utf8')
const lazyEchartsName = entrySource.match(/(?:\.\/|assets\/)(echarts-[A-Za-z0-9_-]+\.js)/u)?.[1]
assert.ok(lazyEchartsName && assets.includes(lazyEchartsName), 'entry must reference an emitted ECharts lazy chunk')
console.log(`Workbench build smoke check passed (${uniqueInitialScripts.length} initial JS modules, ${uniqueInitialStyles.length} initial CSS files, ${assets.length} assets)`)
