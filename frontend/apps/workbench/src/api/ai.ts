import { ApiError, get, post, type ApiRequestOptions } from './core'
import type { AiResult, InvestigationResult } from './models'
import { translate } from '../i18n/index'

export const aiAsk = (question: string) => post<AiResult>('/ai-assistant/api/v1/ai/ask', { question })
export async function investigateAlert(alertId: string, options: ApiRequestOptions = {}): Promise<InvestigationResult> {
  const receipt = await post<{ jobId: string }>('/ai-assistant/api/v1/ai/investigations/async', { alertId }, options)
  const deadline = Date.now() + 180_000
  while (Date.now() < deadline) {
    options.signal?.throwIfAborted()
    const result = await get<InvestigationResult & { error?: string }>(
      `/ai-assistant/api/v1/ai/investigations/${encodeURIComponent(receipt.jobId)}`, options)
    if (result.status === 'COMPLETED' || result.status === 'PARTIAL') return result
    if (result.status === 'FAILED') throw new ApiError(502, translate('errors.SERVER_ERROR'), result.error)
    await waitForPoll(options.signal)
  }
  throw new DOMException('Investigation is still queued or running', 'TimeoutError')
}

function waitForPoll(signal?: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    const abort = () => { clearTimeout(timer); reject(signal?.reason) }
    const timer = setTimeout(() => { signal?.removeEventListener('abort', abort); resolve() }, 1000)
    if (signal?.aborted) abort()
    else signal?.addEventListener('abort', abort, { once: true })
  })
}
export const appendInvestigationToIncident = (investigationId: string, incidentId?: string) =>
  post<InvestigationResult>(`/ai-assistant/api/v1/ai/investigations/${encodeURIComponent(investigationId)}/append-to-incident`,
    incidentId?.trim() ? { incidentId: incidentId.trim() } : {})
