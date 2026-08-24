import { post } from './core'
import type { AiResult } from './models'

export const aiAsk = (question: string) => post<AiResult>('/ai-assistant/api/v1/ai/ask', { question })
