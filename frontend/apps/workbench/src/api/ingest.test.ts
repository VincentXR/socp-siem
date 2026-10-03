import { beforeEach, describe, expect, it, vi } from 'vitest'
const core = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn() }))
vi.mock('./core', () => core)
import { getSourceSetup, previewSource, renderSourceConfig, updateOutput, validateOutputConfig } from './ingest'
beforeEach(() => vi.clearAllMocks())
describe('ingestion setup API contracts', () => {
  it('encodes source identity and previews without using the event injection endpoint', () => {
    const options = { signal: new AbortController().signal }
    getSourceSetup('a/b', options)
    previewSource('a/b', 'real sample', options)
    renderSourceConfig('a/b', options)
    expect(core.get).toHaveBeenCalledWith('/search-config/api/v1/sources/a%2Fb/setup', options)
    expect(core.post).toHaveBeenCalledExactlyOnceWith('/search-config/api/v1/sources/a%2Fb/preview', { sample: 'real sample' }, options)
    expect(core.get).toHaveBeenCalledWith('/search-config/api/v1/sources/a%2Fb/vector-config', options)
  })
  it('requires explicit credential intent and never confuses a blank read with a clear', () => {
    const body = { name: 'Destination', type: 'HTTP', uri: 'https://example.test/ingest', enabled: true, authToken: null }
    updateOutput('a/b', body, 'KEEP')
    validateOutputConfig({ ...body, authToken: '' })
    expect(core.put).toHaveBeenCalledWith('/search-config/api/v1/outputs/a%2Fb', { target: body, credentialAction: 'KEEP' })
    updateOutput('a/b', { ...body, authToken: 'replacement' }, 'REPLACE')
    expect(core.put).toHaveBeenLastCalledWith('/search-config/api/v1/outputs/a%2Fb', { target: { ...body, authToken: 'replacement' }, credentialAction: 'REPLACE' })
    updateOutput('a/b', body, 'CLEAR')
    expect(core.put).toHaveBeenLastCalledWith('/search-config/api/v1/outputs/a%2Fb', { target: body, credentialAction: 'CLEAR' })
    expect(core.post).toHaveBeenCalledWith('/search-config/api/v1/outputs/validate', { ...body, authToken: '' })
  })
})
