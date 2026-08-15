import { computed, ref, type Ref } from 'vue'

export interface ResourceListOptions<T> {
  searchFields: (item: T) => unknown[]
  filter?: (item: T) => boolean
  pageSize?: number
  sortValue?: (item: T, prop: string) => unknown
}

export type ResourceSortOrder = 'ascending' | 'descending'

export interface ResourceSortChange {
  prop?: string | null
  order?: ResourceSortOrder | null
}

/** Shared client-side list state for resource pages with search and pagination. */
export function useResourceList<T>(options: ResourceListOptions<T>) {
  const items: Ref<T[]> = ref([])
  const page = ref(1)
  const size = ref(options.pageSize ?? 10)
  const keyword = ref('')
  const loading = ref(false)
  const sortProp = ref('')
  const sortOrder = ref<ResourceSortOrder | null>(null)

  const filtered = computed(() => {
    const query = keyword.value.trim().toLowerCase()
    return items.value.filter(item => {
      if (options.filter && !options.filter(item)) return false
      if (!query) return true
      return options.searchFields(item).some(value => String(value ?? '').toLowerCase().includes(query))
    })
  })
  const sorted = computed(() => {
    const result = filtered.value.slice()
    if (!sortProp.value || !sortOrder.value) return result

    const valueOf = (item: T) => options.sortValue
      ? options.sortValue(item, sortProp.value)
      : (item as Record<string, unknown>)[sortProp.value]
    result.sort((left, right) => {
      const leftValue = valueOf(left)
      const rightValue = valueOf(right)
      if (leftValue === rightValue) return 0
      if (leftValue === null || leftValue === undefined || leftValue === '') return -1
      if (rightValue === null || rightValue === undefined || rightValue === '') return 1
      const comparison = typeof leftValue === 'number' && typeof rightValue === 'number'
        ? leftValue - rightValue
        : String(leftValue).localeCompare(String(rightValue), 'zh-CN', { numeric: true, sensitivity: 'base' })
      return sortOrder.value === 'ascending' ? comparison : -comparison
    })
    return result
  })
  const paged = computed(() => sorted.value.slice((page.value - 1) * size.value, page.value * size.value))

  function setItems(next: T[]) {
    items.value = next
    if (page.value > 1 && paged.value.length === 0) page.value = 1
  }

  function resetPage() {
    page.value = 1
  }

  function onSortChange(change: ResourceSortChange) {
    sortProp.value = change.prop ?? ''
    sortOrder.value = change.order ?? null
    resetPage()
  }

  return { items, page, size, keyword, loading, filtered, sorted, paged, setItems, resetPage, onSortChange }
}
