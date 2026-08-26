import { post } from './core'
import type { AiResult, InvestigationResult } from './models'

export const aiAsk = (question: string) => post<AiResult>('/ai-assistant/api/v1/ai/ask', { question })
export const investigateAlert = (alertId: string) =>
  post<InvestigationResult>('/ai-assistant/api/v1/ai/investigations', { alertId })
export const appendInvestigationToIncident = (investigationId: string, incidentId?: string) =>
  post<InvestigationResult>(`/ai-assistant/api/v1/ai/investigations/${encodeURIComponent(investigationId)}/append-to-incident`,
    incidentId?.trim() ? { incidentId: incidentId.trim() } : {})
