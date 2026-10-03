import { ESLint } from 'eslint'
import { fileURLToPath } from 'node:url'

// Parse real Vue and TypeScript syntax, including type-aware checks. Comments
// and strings that happen to contain console.log/debugger are not statements.
const cwd = fileURLToPath(new URL('../', import.meta.url))
const eslint = new ESLint({ cwd })
const results = await eslint.lintFiles(['src/**/*.{ts,vue}'])
const formatter = await eslint.loadFormatter('stylish')
const output = formatter.format(results)
if (output) process.stdout.write(output)
if (results.some(result => result.errorCount > 0)) process.exitCode = 1
else console.log('frontend semantic lint passed')
