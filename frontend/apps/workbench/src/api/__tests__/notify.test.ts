import { afterEach, describe, expect, it, vi } from 'vitest'
import { dispatchLog, getChannel, listChannels } from '../notify'
import { get } from '../core'

vi.mock('../core', () => ({ get: vi.fn(), put: vi.fn(), del: vi.fn(), post: vi.fn() }))
afterEach(() => vi.resetAllMocks())
describe('notification pagination API boundary', () => {
  it('preserves exact receipt filters and cancellation while requesting a bounded page', async () => {
    const options = { signal: new AbortController().signal }
    await dispatchLog(options, { page: 3, size: 20, status: 'logged', alarmId: 'alarm/1', channel: 'Ops & Sec' })
    expect(get).toHaveBeenCalledWith('/notify-web/api/v1/dispatch-log?page=3&size=20&status=logged&alarmId=alarm%2F1&channel=Ops+%26+Sec', options)
    await dispatchLog()
    expect(get).toHaveBeenLastCalledWith('/notify-web/api/v1/dispatch-log?page=1&size=20', undefined)
  })

  it('supports channel paging and exact off-page lookup without a catalogue scan', async () => {
    const options = { signal: new AbortController().signal }
    await listChannels(options, 4, 10)
    expect(get).toHaveBeenLastCalledWith('/notify-web/api/v1/channels?page=4&size=10', options)
    await getChannel('channel/1')
    expect(get).toHaveBeenLastCalledWith('/notify-web/api/v1/channels/channel%2F1')
  })
})
