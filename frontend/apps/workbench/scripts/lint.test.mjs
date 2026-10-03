import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, mkdir, writeFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { ESLint } from 'eslint'
import configuration from '../eslint.config.mjs'

test('semantic lint catches real Vue and typed TypeScript mutations, not strings/comments', async () => {
  const cwd = await mkdtemp(join(tmpdir(), 'socp-lint-'))
  try {
    await mkdir(join(cwd, 'src'))
    await writeFile(join(cwd, 'tsconfig.json'), JSON.stringify({ compilerOptions: { strict: true, target: 'ES2022' }, include: ['src'] }))
    await writeFile(join(cwd, 'src/probe.ts'), 'export async function probe() { await 42; console.log("x"); debugger }\n')
    await writeFile(join(cwd, 'src/GoodProbe.vue'), '<script setup lang="ts">const note = "console.log is mentioned, not called"</script><template><div>{{ note }}</div></template>\n')
    await writeFile(join(cwd, 'src/BadProbe.vue'), '<script setup lang="ts">const props = defineProps<{ value: string }>(); props.value = "changed"</script><template><div id="a" id="b" /></template>\n')
    const eslint = new ESLint({ cwd, overrideConfigFile: true, overrideConfig: [...configuration,
      { files: ['src/**/*.{ts,vue}'], languageOptions: { parserOptions: { project: './tsconfig.json', tsconfigRootDir: cwd } } },
    ] })
    const results = await eslint.lintFiles(['src/**/*.{ts,vue}'])
    const rules = results.flatMap(result => result.messages.map(message => message.ruleId))
    for (const rule of ['@typescript-eslint/await-thenable', 'no-console', 'no-debugger', 'vue/no-duplicate-attributes', 'vue/no-mutating-props']) {
      assert.ok(rules.includes(rule), `expected ${rule}: ${JSON.stringify(results.map(result => result.messages))}`)
    }
    assert.equal(results.find(result => result.filePath.endsWith('GoodProbe.vue')).errorCount, 0)
  } finally { await rm(cwd, { recursive: true, force: true }) }
})
