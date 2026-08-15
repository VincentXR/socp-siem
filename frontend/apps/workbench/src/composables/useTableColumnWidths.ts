import { onMounted, ref } from 'vue'

interface TableColumnLike {
  property?: string
  columnKey?: string
}

type StoredWidths = Record<string, number>

/** Keeps user-adjusted table widths stable across refreshes for operational tables. */
export function useTableColumnWidths(storageKey: string) {
  const widths = ref<StoredWidths>({})
  const key = `socp_table_widths:${storageKey}`

  function readWidths() {
    try {
      const stored = JSON.parse(localStorage.getItem(key) || '{}') as unknown
      if (!stored || typeof stored !== 'object' || Array.isArray(stored)) return
      widths.value = Object.fromEntries(
        Object.entries(stored).filter(([, value]) => typeof value === 'number' && value >= 40 && value <= 2_000),
      )
    } catch {
      widths.value = {}
    }
  }

  function persistWidths() {
    try { localStorage.setItem(key, JSON.stringify(widths.value)) } catch { /* ignore */ }
  }

  function columnWidth(columnKey: string, fallback?: number | string) {
    return widths.value[columnKey] ?? fallback
  }

  function onHeaderDragEnd(newWidth: number, _oldWidth: number, column: TableColumnLike) {
    const columnKey = column.columnKey || column.property
    if (!columnKey || !Number.isFinite(newWidth)) return
    widths.value[columnKey] = Math.round(newWidth)
    persistWidths()
  }

  function resetWidths() {
    widths.value = {}
    try { localStorage.removeItem(key) } catch { /* ignore */ }
  }

  onMounted(readWidths)

  return { widths, columnWidth, onHeaderDragEnd, resetWidths }
}
