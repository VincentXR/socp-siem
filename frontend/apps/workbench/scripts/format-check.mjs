import { readdir, readFile } from 'node:fs/promises'
import { join, relative } from 'node:path'
import { fileURLToPath } from 'node:url'

// URL.pathname is not a filesystem path on Windows (it contains a leading
// slash before the drive letter). Convert the module URL explicitly so the
// same gate behaves consistently on local Windows runs and GitHub runners.
const scriptRoot = fileURLToPath(new URL('../', import.meta.url))
const root = join(scriptRoot, 'src')
const extensions = new Set(['.ts', '.vue', '.css'])
const violations = []

async function walk(directory) {
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const path = join(directory, entry.name)
    if (entry.isDirectory()) await walk(path)
    else if (extensions.has(path.slice(path.lastIndexOf('.')))) {
      const source = await readFile(path, 'utf8')
      const name = relative(scriptRoot, path)
      const lines = source.split('\n')
      lines.forEach((line, index) => {
        if (/[ \t]+$/.test(line)) violations.push(`${name}:${index + 1}: trailing whitespace`)
        if (/\t/.test(line)) violations.push(`${name}:${index + 1}: tab indentation`)
      })
    }
  }
}

await walk(root)
if (violations.length) {
  console.error(violations.join('\n'))
  process.exitCode = 1
} else {
  console.log('frontend format check passed')
}
