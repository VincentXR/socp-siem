import { afterEach, describe, expect, it, vi } from 'vitest'
import { addCaseNote, claimCase, exportCaseSummary, listCases, saveCaseChanges } from '../incidents'
import { downloadFile, get, post } from '../core'

vi.mock('../core', () => ({ downloadFile: vi.fn(), get: vi.fn(), post: vi.fn() }))
afterEach(() => vi.resetAllMocks())
describe('case workspace API boundary', () => {
  it('sends version and idempotency in JSON, and encodes the exact case id', async () => {
    const changes = { status: 'CONTAINED', assignee: '', expectedVersion: 7, idempotencyKey: 'change-1' }
    await saveCaseChanges('case/1', changes)
    expect(post).toHaveBeenLastCalledWith('/incident-web/api/v1/incidents/case%2F1/changes', changes)
    await claimCase('case/1', 7, 'claim-1')
    expect(post).toHaveBeenLastCalledWith('/incident-web/api/v1/incidents/case%2F1/claim', { expectedVersion: 7, idempotencyKey: 'claim-1' })
    await addCaseNote('case/1', 'Private evidence', 'note-1')
    expect(post).toHaveBeenLastCalledWith('/incident-web/api/v1/incidents/case%2F1/notes', { content: 'Private evidence', idempotencyKey: 'note-1' })
  })
  it('retains queue and abort semantics without transmitting a caller-supplied owner', async () => {
    const controller = new AbortController()
    await listCases(2, 20, 'host', 'OPEN', { signal: controller.signal }, 'mine')
    expect(get).toHaveBeenCalledWith('/incident-web/api/v1/incidents?page=2&size=20&q=host&status=OPEN&queue=mine', { signal: controller.signal })
    await exportCaseSummary('case/1')
    expect(downloadFile).toHaveBeenCalledWith('/incident-web/api/v1/incidents/case%2F1/export', 'case-summary.json')
  })
})
