import { beforeEach, expect, it, vi } from 'vitest'
import { createCaseFromAlarm } from './incidents'
import type { Alarm } from './models'

const requests = vi.hoisted(() => ({ post: vi.fn(), get: vi.fn(), downloadFile: vi.fn() }))
vi.mock('./core', () => requests)

beforeEach(() => vi.clearAllMocks())

it('admits an alarm by ID without submitting editable alarm facts to the service-only endpoint', async () => {
  const receipt = { caseId: 'case-1', created: true }
  requests.post.mockResolvedValueOnce(receipt)

  expect(await createCaseFromAlarm({ id: 'alarm 1', entity: 'untrusted-host', severity: 'LOW' } as Alarm)).toEqual(receipt)
  expect(requests.post).toHaveBeenCalledExactlyOnceWith('/incident-web/api/v1/incidents/from-alarm/alarm%201')
})
