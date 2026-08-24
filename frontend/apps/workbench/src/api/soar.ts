import { del, get, post } from './core'
import type { Playbook, PlaybookExecution } from './models'

export const listPlaybooks = () => get<Playbook[]>('/soar-web/api/v1/playbooks')
export const listPlaybookExecutions = () => get<PlaybookExecution[]>('/soar-web/api/v1/playbooks/executions')
export const createPlaybook = (p: { name: string; trigger: string; actions: string[]; enabled: boolean }) => post<Playbook>('/soar-web/api/v1/playbooks', p)
export const deletePlaybook = (id: string) => del(`/soar-web/api/v1/playbooks/${encodeURIComponent(id)}`)
export const togglePlaybook = (id: string) => post<Playbook>(`/soar-web/api/v1/playbooks/${encodeURIComponent(id)}/toggle`)
