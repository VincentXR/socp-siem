import { beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick, reactive } from 'vue'
import { useAlarmQuery } from '../src/composables/useAlarmQuery.ts'

const emptyPage = { items: [], total: 0, page: 1, size: 10, totalPages: 0 }

function harness(query: Record<string, unknown> = {}) {
  const route = reactive({ name: 'alarms', query })
  const replace = vi.fn()
  const fetchPage = vi.fn(async () => emptyPage)
  const alarmQuery = useAlarmQuery({ route, router: { replace }, fetchPage })
  return { route, replace, fetchPage, ...alarmQuery }
}

describe('useAlarmQuery route query contract', () => {
  beforeEach(() => {
    vi.resetAllMocks()
  })

  it('falls back to page 1 for every illegal page token (no 2.5 reaches the backend)', async () => {
    for (const raw of ['x', '0', '-2', '2.5', '']) {
      const { alarmPageNum } = harness({ page: raw })
      expect(alarmPageNum.value, `page ${JSON.stringify(raw)}`).toBe(1)
    }
  })

  it('applies an externally driven query without echoing a replace() back', async () => {
    const { route, replace, alarmPageNum } = harness()
    route.query = { page: '3' }
    await nextTick()
    await nextTick()
    expect(alarmPageNum.value).toBe(3)
    expect(replace).not.toHaveBeenCalled()
  })

  it('preserves unmanaged but consumed keys such as alarmId when writing', async () => {
    const { route, replace, alarmPageNum } = harness({ page: '2', alarmId: 'AL-9' })
    alarmPageNum.value = 5
    await nextTick()
    expect(replace).toHaveBeenCalledTimes(1)
    const written = replace.mock.calls[0][0].query
    expect(written.page).toBe('5')
    expect(written.alarmId).toBe('AL-9')
  })

  it('keeps a deep-linked alarmId alive when the applied query resets the page', async () => {
    // Real path: /alarms?page=4 → "关联告警" pushes { name:'alarms', query:{alarmId} }.
    // The applied read moves the page 4→1; the page watcher must not rewrite the
    // URL and erase the alarmId the detail drawer consumes.
    const { route, replace, alarmPageNum } = harness({ page: '4' })
    route.query = { alarmId: 'AL-7' }
    await nextTick()
    await nextTick()
    expect(alarmPageNum.value).toBe(1)
    expect(replace).not.toHaveBeenCalled()
  })

  it('drops managed keys set back to their defaults and keeps the size whitelist', async () => {
    const { replace, alarmPageNum, alarmPageSize } = harness({ page: '2', size: '50' })
    expect(alarmPageSize.value).toBe(50)
    alarmPageNum.value = 1
    alarmPageSize.value = 10
    await nextTick()
    expect(replace).toHaveBeenCalledTimes(1)
    const written = replace.mock.calls[0][0].query
    expect(written.page).toBeUndefined()
    expect(written.size).toBeUndefined()
  })

  it('ignores another route query and does not write or fetch from its watchers', async () => {
    const { route, replace, fetchPage, alarmPageNum, alarmKeyword } = harness({ q: 'original', page: '2' })
    route.name = 'search'
    route.query = { q: 'unrelated', page: '9' }
    await nextTick()
    expect(alarmPageNum.value).toBe(2)
    expect(alarmKeyword.value).toBe('original')
    alarmPageNum.value = 3
    await nextTick()
    expect(replace).not.toHaveBeenCalled()
    expect(fetchPage).not.toHaveBeenCalled()
  })
})
