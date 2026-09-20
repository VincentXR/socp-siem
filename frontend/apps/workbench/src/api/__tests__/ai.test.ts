import { afterEach, describe, expect, it, vi } from 'vitest'
import { investigateAlert } from '../ai'
import { get, post } from '../core'

vi.mock('../core', async importOriginal => ({
  ...await importOriginal<typeof import('../core')>(), get: vi.fn(), post: vi.fn(),
}))

afterEach(() => { vi.useRealTimers(); vi.resetAllMocks() })

describe('durable investigation polling', () => {
  it('waits through queued and running receipts and returns the final result', async () => {
    vi.useFakeTimers()
    vi.mocked(post).mockResolvedValue({ jobId: 'job/1' })
    vi.mocked(get).mockResolvedValueOnce({ status: 'NEW' }).mockResolvedValueOnce({ status: 'RUNNING' })
      .mockResolvedValueOnce({ status: 'PARTIAL', investigationId: 'job/1', analysis: 'evidence' })
    const result = investigateAlert('alarm-1')
    await vi.advanceTimersByTimeAsync(2000)
    expect(await result).toMatchObject({ status: 'PARTIAL', analysis: 'evidence' })
    expect(post).toHaveBeenCalledWith('/ai-assistant/api/v1/ai/investigations/async', { alertId: 'alarm-1' }, {})
    expect(get).toHaveBeenLastCalledWith('/ai-assistant/api/v1/ai/investigations/job%2F1', {})
  })

  it('cancels polling without submitting another task', async () => {
    vi.useFakeTimers()
    vi.mocked(post).mockResolvedValue({ jobId: 'job' })
    vi.mocked(get).mockResolvedValue({ status: 'RUNNING' })
    const controller = new AbortController()
    const result = investigateAlert('alarm', { signal: controller.signal })
    const rejected = expect(result).rejects.toMatchObject({ name: 'AbortError' })
    await vi.advanceTimersByTimeAsync(0)
    controller.abort()
    await rejected
    await vi.advanceTimersByTimeAsync(5000)
    expect(get).toHaveBeenCalledTimes(1)
    expect(post).toHaveBeenCalledTimes(1)
  })
})
