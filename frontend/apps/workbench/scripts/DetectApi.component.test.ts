import { afterEach, expect, it, vi } from 'vitest'
import { activateGasRule, deleteGasRule, updateGasRule } from '../src/api/detect'

afterEach(() => vi.unstubAllGlobals())

it('sends the separately loaded rule token on every existing-rule mutation', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response(JSON.stringify({ code: 0, data: {} }), { status: 200 })))
  vi.stubGlobal('fetch', fetcher)
  const token = 'a'.repeat(64)
  await updateGasRule('rule/quoted', { name: 'draft', revisionToken: 'edited-body-value' }, token)
  await activateGasRule('rule/quoted', token)
  await deleteGasRule('rule/quoted', token)
  expect(fetcher.mock.calls.map(([, init]) => init.method)).toEqual(['PUT', 'POST', 'DELETE'])
  for (const [path, init] of fetcher.mock.calls) {
    expect(path).toContain('rule%2Fquoted')
    expect(init.headers.get('If-Match')).toBe(`"${token}"`)
    expect(init.credentials).toBe('same-origin')
  }
})
