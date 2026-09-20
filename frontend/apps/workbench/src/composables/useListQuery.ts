import { computed, nextTick, ref, type Ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

// NEW-FILE MUST-READ. URL-synced list-query composables (this file and
// useAlarmQuery.ts) share a locked contract, guarded by
// build/verify-frontend-conventions.py (a standalone gate the review
// consolidator wires into verify-repository). Any new composable that reads
// route.query and calls router.replace({ query }) must satisfy all four:
//   1. page token parsed with Number.isInteger (never isFinite) so '2.5'
//      cannot reach the backend @RequestParam Integer and surface as a 500;
//   2. the applyingRouteQuery echo guard is reset on nextTick, so the
//      pre-flush page/size watcher cannot re-issue a replace() that clobbers
//      the route it just applied;
//   3. the URL write starts from `{ ...route.query }` to preserve unmanaged
//      but functionally-consumed keys (alarmId deep links in particular);
//   4. every read/write is scoped by `route.name !== <routeName>` so a global
//      watcher cannot leak a page reset onto an unrelated route.

// Structural stand-ins for the vue-router objects so the composable can be
// exercised with plain fakes in tests while the call sites keep the real hooks.
export interface ListQueryRoute {
  name: unknown
  query: Record<string, unknown>
}

export interface ListQueryRouter {
  replace: (to: { query: Record<string, unknown> }) => unknown
}

export interface ListQueryStringField {
  key: string
  // Whitelist guard mirroring useAlarmQuery: keep only an accepted token.
  validate?: (raw: string) => string
}

export interface UseListQueryOptions {
  // Guard: only read/apply while the live route still owns this list.
  routeName: string
  total: Ref<number>
  size: Ref<number>
  fields?: ListQueryStringField[]
  route?: ListQueryRoute
  router?: ListQueryRouter
}

const readString = (value: unknown): string => typeof value === 'string' ? value : ''

export function useListQuery(options: UseListQueryOptions) {
  const route = (options.route ?? useRoute()) as ListQueryRoute
  const router = options.router ?? (useRouter() as unknown as ListQueryRouter)
  const fields = options.fields ?? []

  const page = ref(1)
  const keyword = ref('')
  const filters: Record<string, Ref<string>> = {}
  for (const field of fields) filters[field.key] = ref('')

  // The trimmed keyword is the single source of truth for both the request and
  // the URL so the two can never disagree.
  const trimmedKeyword = computed(() => keyword.value.trim())
  const keywordParam = computed<string | undefined>(() => trimmedKeyword.value || undefined)

  let applyingRouteQuery = false

  function read(): void {
    const query = route.query
    keyword.value = readString(query.q)
    for (const field of fields) {
      const raw = readString(query[field.key])
      filters[field.key].value = field.validate ? field.validate(raw) : raw
    }
    const nextPage = Number(query.page)
    page.value = Number.isInteger(nextPage) && nextPage >= 1 ? nextPage : 1
  }

  // A page number only reaches the URL after it is an integer >= 1 and is
  // clamped to the last real page; illegal or out-of-range values fall back.
  function clampPageForUrl(): number {
    const current = Number(page.value)
    if (!Number.isInteger(current) || current < 1) return 1
    const sizeValue = Number(options.size.value)
    const totalValue = Number(options.total.value)
    if (sizeValue > 0 && totalValue > 0) {
      const maxPage = Math.ceil(totalValue / sizeValue)
      if (current > maxPage) return maxPage >= 1 ? maxPage : 1
    }
    return current
  }

  function sync(): void {
    if (applyingRouteQuery || route.name !== options.routeName) return
    const query: Record<string, unknown> = { ...route.query }
    query.q = trimmedKeyword.value || undefined
    for (const field of fields) query[field.key] = filters[field.key].value || undefined
    const clamped = clampPageForUrl()
    query.page = clamped > 1 ? String(clamped) : undefined
    void router.replace({ query })
  }

  // Apply an externally driven query (deep link, browser back/forward): read the
  // route values while suppressing the echo write that the resulting ref changes
  // would otherwise trigger through the caller's sync watcher.
  function applyRouteQuery(): void {
    if (route.name !== options.routeName) return
    applyingRouteQuery = true
    read()
    void nextTick(() => { applyingRouteQuery = false })
  }

  read()

  return { page, keyword, filters, keywordParam, read, sync, applyRouteQuery }
}
