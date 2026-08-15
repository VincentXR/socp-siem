import { computed, ref, type Ref } from 'vue'

export interface ResourceListOptions<T> {
  searchFields: (item: T) => unknown[]
  filter?: (item: T) => boolean
  pageSize?: number
}

/** Shared client-side list state for resource pages with search and pagination. */
export function useResourceList<T>(options: ResourceListOptions<T>) {
  const items: Ref<T[]> = ref([])
  const page = ref(1)
  const size = ref(options.pageSize ?? 10)
  const keyword = ref('')
  const loading = ref(false)

  const filtered = computed(() => {
    const query = keyword.value.trim().toLowerCase()
    return items.value.filter(item => {
      if (options.filter && !options.filter(item)) return false
      if (!query) return true
      return options.searchFields(item).some(value => String(value ?? '').toLowerCase().includes(query))
    })
  })
  const paged = computed(() => filtered.value.slice((page.value - 1) * size.value, page.value * size.value))

  function setItems(next: T[]) {
    items.value = next
    if (page.value > 1 && paged.value.length === 0) page.value = 1
  }

  function resetPage() {
    page.value = 1
  }

  return { items, page, size, keyword, loading, filtered, paged, setItems, resetPage }
}
