import type { InjectionKey, Ref } from 'vue'
import type { Alarm } from '../api'
import type { useAlarmQuery } from '../composables/useAlarmQuery'
import type { useOverview } from '../composables/useOverview'
import type { Theme } from '../composables/useTheme'

/** State shared by the authenticated shell and route-level page components. */
export interface WorkbenchState {
  theme: Ref<Theme>
  overview: ReturnType<typeof useOverview>
  alarmQuery: ReturnType<typeof useAlarmQuery>
  alarms: Ref<Alarm[]>
  navigate: (menu: string) => void
  exportAlarms: (format: 'csv' | 'json') => Promise<void>
  logout: () => Promise<void>
}

export const WORKBENCH_STATE: InjectionKey<WorkbenchState> = Symbol('socp.workbench.state')
