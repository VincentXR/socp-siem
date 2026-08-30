import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { formatDate, formatNumber, translate } from '../src/i18n'
import { requestRaw } from '../src/api/core'
import {
  initializeLocale,
  normalizeLocale,
  resolveInitialLocale,
  setLocale,
  toggleLocale,
} from '../src/i18n/locale-manager'

describe('workbench i18n', () => {
  const storage = new Map<string, string>()
  const localStorageMock = {
    getItem: (key: string) => storage.get(key) ?? null,
    setItem: (key: string, value: string) => { storage.set(key, value) },
    removeItem: (key: string) => { storage.delete(key) },
    clear: () => { storage.clear() },
  }

  beforeEach(() => {
    vi.stubGlobal('localStorage', localStorageMock)
    localStorageMock.clear()
    setLocale('zh-CN', false)
    document.title = 'SOCP Workbench'
    document.documentElement.lang = 'zh-CN'
  })

  afterEach(() => vi.unstubAllGlobals())

  it('resolves profile, storage, browser and default preferences in order', () => {
    localStorage.setItem('socp-locale', 'en-US')
    localStorage.setItem('socp-profile-locale', 'zh-CN')
    expect(resolveInitialLocale()).toBe('zh-CN')
    expect(resolveInitialLocale('en-US')).toBe('en-US')
    expect(normalizeLocale('EN_us')).toBe('en-US')
    expect(normalizeLocale('fr-FR')).toBeNull()
  })

  it('synchronizes the Vue, document and persisted locale state', () => {
    expect(initializeLocale('en-US')).toBe('en-US')
    expect(document.documentElement.lang).toBe('en-US')
    expect(document.title).toBe('SOCP Workbench')
    expect(localStorage.getItem('socp-locale')).toBe('en-US')

    expect(toggleLocale()).toBe('zh-CN')
    expect(document.documentElement.lang).toBe('zh-CN')
    expect(document.title).toBe('SOCP 控制台')
  })

  it('supports interpolation, fallback and locale-aware formatting', () => {
    setLocale('zh-CN', false)
    expect(translate('assets.importSkipped', { imported: 2, skipped: 1 })).toContain('2')
    expect(translate('assets.importSkipped', { imported: 2, skipped: 1 })).toContain('1')
    expect(formatNumber(1234, 'integer')).toContain('1')
    expect(formatDate('2026-08-30T12:34:56Z', 'dateTime')).toContain('2026')

    setLocale('en-US', false)
    expect(translate('assets.importSuccess', { count: 3 })).toContain('3')
    expect(translate('errors.UNAUTHORIZED')).toContain('Unauthorized')
  })

  it('sends the active locale to the API unless a request supplies one', async () => {
    const seen: string[] = []
    const fetchMock = vi.fn(async (_input: unknown, init?: RequestInit) => {
      const headers = new Headers(init?.headers)
      seen.push(headers.get('Accept-Language') ?? '')
      return new Response('', { status: 200 })
    })
    vi.stubGlobal('fetch', fetchMock)
    setLocale('en-US', false)
    const raw = await requestRaw('/health')
    raw.cleanup()
    expect(fetchMock).toHaveBeenCalledOnce()

    const explicit = await requestRaw('/health', { headers: { 'Accept-Language': 'zh-CN' } })
    explicit.cleanup()
    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(seen).toEqual(['en-US', 'zh-CN'])
  })
})
