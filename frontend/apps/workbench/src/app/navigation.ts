export interface MenuItem {
  key: string
  label: string
  icon: string
}

export interface MenuGroup {
  group: string
  items: MenuItem[]
}

/** Navigation is kept outside the shell so views do not own layout concerns. */
export const MENU_GROUPS: MenuGroup[] = [
  {
    group: '总览',
    items: [
      { key: 'overview', label: '概览', icon: 'dashboard' },
      { key: 'situation', label: '实时态势', icon: 'radar' },
    ],
  },
  {
    group: '告警与事件',
    items: [
      { key: 'alarms', label: '告警查询', icon: 'alarm' },
      { key: 'case', label: '案件管理', icon: 'case' },
      { key: 'search', label: '日志检索', icon: 'search' },
      { key: 'notify', label: '通知集成', icon: 'notify' },
    ],
  },
  {
    group: '检测与响应',
    items: [
      { key: 'detect', label: '检测规则', icon: 'detect' },
      { key: 'ueba', label: 'UEBA 风险', icon: 'ueba' },
      { key: 'soar', label: '编排响应', icon: 'soar' },
      { key: 'attack', label: 'ATT&CK', icon: 'attack' },
    ],
  },
  {
    group: '资产与情报',
    items: [
      { key: 'assets', label: '资产管理', icon: 'assets' },
      { key: 'endpoints', label: '端点防护', icon: 'endpoints' },
      { key: 'threat-intel', label: '威胁情报', icon: 'threat' },
      { key: 'refset', label: '参考数据集', icon: 'refset' },
    ],
  },
  {
    group: '接入与配置',
    items: [
      { key: 'ingest', label: '日志接入', icon: 'ingest' },
      { key: 'meta', label: '元数据', icon: 'meta' },
      { key: 'compliance', label: '合规', icon: 'compliance' },
    ],
  },
  {
    group: '系统',
    items: [
      { key: 'report', label: '报表统计', icon: 'report' },
      { key: 'ai', label: 'AI 助手', icon: 'ai' },
      { key: 'health', label: '系统健康', icon: 'health' },
    ],
  },
]

// Preserve the current read-only navigation behavior while permissions are
// still enforced by the existing API/auth layer.
const MENU_VIEWER_HIDDEN = new Set(['ingest', 'meta', 'detect', 'soar', 'notify', 'refset'])

export function getVisibleMenuGroups(): MenuGroup[] {
  return MENU_GROUPS
    .map(group => ({
      ...group,
      items: group.items.filter(item => !MENU_VIEWER_HIDDEN.has(item.key)),
    }))
    .filter(group => group.items.length > 0)
}

/** Apple-style line icons used by the navigation shell. */
export const MENU_ICONS: Record<string, string> = {
  dashboard: '<path d="M4 13h6V4H4v9Zm0 7h6v-5H4v5Zm10 0h6v-9h-6v9Zm0-16v5h6V4h-6Z"/>',
  radar: '<circle cx="12" cy="12" r="8.5"/><circle cx="12" cy="12" r="3.5"/><path d="M12 3.5v4M12 16.5v4M3.5 12h4M16.5 12h4M6 6l2.8 2.8M15.2 15.2 18 18M18 6l-2.8 2.8M8.8 15.2 6 18"/>',
  alarm: '<path d="M12 3a8 8 0 0 0-8 8c0 3.3-1 5-2 6h20c-1-3.3-2-5.7-2-9a8 8 0 0 0-8-8Z"/><path d="M10 21h4"/>',
  search: '<circle cx="10.5" cy="10.5" r="6.5"/><path d="m15.5 15.5 5 5"/>',
  ingest: '<path d="M12 3v12M7 10l5 5 5-5"/><path d="M4 19h16"/>',
  meta: '<path d="M4 4h16v6H4zM4 14h16v6H4z"/>',
  detect: '<circle cx="12" cy="12" r="3"/><path d="M12 2v4M12 18v4M2 12h4M18 12h4M4.9 4.9l2.8 2.8M16.3 16.3l2.8 2.8M19.1 4.9l-2.8 2.8M7.7 16.3l-2.8 2.8"/>',
  ueba: '<circle cx="8" cy="9" r="4"/><path d="M2 20c1.2-3 3.4-4.5 6-4.5s4.8 1.5 6 4.5"/><path d="M17 5c2.5 1 4 3 4 6"/><path d="M18 3.5V7h-3.5"/>',
  soar: '<path d="M13 2 4 14h6l-1 8 9-12h-6l1-8Z"/>',
  report: '<path d="M4 4h16v16H4z"/><path d="M8 16V9M12 16V7M16 16v-4"/>',
  assets: '<rect x="3" y="5" width="18" height="14" rx="2"/><path d="M3 9h18M8 5v14"/>',
  endpoints: '<path d="M12 2 4 6v6c0 5 3.4 8.6 8 10 4.6-1.4 8-5 8-10V6l-8-4Z"/><path d="M9 12l2 2 4-4"/>',
  ai: '<rect x="4" y="7" width="16" height="12" rx="3"/><path d="M12 4v3M9 2v3M15 2v3M9.5 13h5M9.5 16h3"/>',
  threat: '<path d="M12 2c-3.5 2-6 5-6 9v5l6 4 6-4v-5c0-4-2.5-7-6-9Z"/><path d="M8 13c1.5-1 3-1.5 4-3 1 1.5 2.5 2 4 3"/>',
  attack: '<circle cx="12" cy="12" r="9"/><circle cx="12" cy="12" r="5"/><circle cx="12" cy="12" r="1.2" fill="currentColor" stroke="none"/>',
  notify: '<path d="M12 3a7 7 0 0 0-7 7c0 3-1 5-2.5 6.5h19C20 15 19 13 19 10a7 7 0 0 0-7-7Z"/><path d="M10 20h4"/>',
  case: '<path d="M4 8h16v12H4z"/><path d="M8 8V5h8v3M4 12h16M10 15h4"/>',
  refset: '<path d="M5 3h14a1 1 0 0 1 1 1v15l-3-2-3 2-3-2-3 2-3-2V4a1 1 0 0 1 1-1Z"/>',
  compliance: '<path d="M6 4h12v16l-6-3-6 3V4Z"/><path d="m9 11 2 2 4-4"/>',
  health: '<path d="M12 21C7 16.5 3 13 3 9a5 5 0 0 1 9-3 5 5 0 0 1 9 3c0 4-4 7.5-9 12Z"/><path d="M8.5 10h2l1.5-3 2 5 1.5-2h2"/>',
}
