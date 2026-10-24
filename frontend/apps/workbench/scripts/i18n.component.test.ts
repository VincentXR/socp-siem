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

  it('keeps an explicit local choice ahead of profile defaults', () => {
    localStorage.setItem('socp-locale', 'en-US')
    localStorage.setItem('socp-profile-locale', 'zh-CN')
    expect(resolveInitialLocale()).toBe('en-US')
    expect(resolveInitialLocale('zh-CN')).toBe('en-US')
    localStorage.removeItem('socp-locale')
    expect(resolveInitialLocale()).toBe('zh-CN')
    expect(resolveInitialLocale('en-US')).toBe('en-US')
    expect(normalizeLocale('EN_us')).toBe('en-US')
    expect(normalizeLocale('fr-FR')).toBeNull()
  })

  it('persists explicit changes without storing initial defaults as a user choice', () => {
    expect(initializeLocale('en-US')).toBe('en-US')
    expect(document.documentElement.lang).toBe('en-US')
    expect(document.title).toBe('SOCP Workbench')
    expect(localStorage.getItem('socp-locale')).toBeNull()

    expect(toggleLocale()).toBe('zh-CN')
    expect(document.documentElement.lang).toBe('zh-CN')
    expect(document.title).toBe('SOCP 控制台')
    expect(localStorage.getItem('socp-locale')).toBe('zh-CN')
    expect(initializeLocale('en-US')).toBe('zh-CN')
    expect(document.documentElement.lang).toBe('zh-CN')
  })

  it('supports interpolation, fallback and locale-aware formatting', () => {
    setLocale('zh-CN', false)
    expect(translate('soar.status.PUBLISHED')).toBe('已发布')
    expect(translate('soar.status.WAITING_INPUT')).toBe('等待输入')
    expect(translate('statuses.UNKNOWN')).toBe('未知')
    expect(translate('assets.importSkipped', { imported: 2, skipped: 1 })).toContain('2')
    expect(translate('assets.importSkipped', { imported: 2, skipped: 1 })).toContain('1')
    expect(formatNumber(1234, 'integer')).toContain('1')
    expect(formatDate('2026-08-30T12:34:56Z', 'dateTime')).toContain('2026')

    setLocale('en-US', false)
    expect(translate('assets.importSuccess', { count: 3 })).toContain('3')
    expect(translate('errors.UNAUTHORIZED')).toContain('Unauthorized')
    expect(translate('soar.createBlankTitle')).toBe('Create blank playbook')
    expect(translate('soar.queueRun')).toBe('Queue run')
    expect(translate('soar.rerunRun')).toBe('Rerun')
    expect(translate('soar.status.PUBLISHED')).toBe('Published')
    expect(translate('soar.status.WAITING_INPUT')).toBe('Waiting for input')
    expect(translate('statuses.UNKNOWN')).toBe('Unknown')
  })

  it('describes detection rule deletion as an asynchronous drain, not an instant hot reload', () => {
    // The rule engine applies deletions by draining per-replica generations, so the
    // confirm copy must not promise an immediate, synchronous engine reload.
    setLocale('zh-CN', false)
    const zh = translate('detect.deleteRuleConfirm')
    expect(zh).toContain('异步')
    expect(zh).not.toContain('立即热更新')
    expect(zh).not.toContain('热更新引擎')

    setLocale('en-US', false)
    const en = translate('detect.deleteRuleConfirm')
    expect(en.toLowerCase()).toContain('asynchronously')
    expect(en.toLowerCase()).not.toContain('hot reload')
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
