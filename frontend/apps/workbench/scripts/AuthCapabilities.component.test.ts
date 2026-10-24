import { mount, shallowMount, flushPromises } from '@vue/test-utils'
import { defineComponent, h, provide, ref } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useAuth } from '../src/composables/useAuth'
import { useSoarAccess } from '../src/composables/useSoarAccess'
import { useWriteAccess } from '../src/composables/useWriteAccess'
import { WORKBENCH_STATE } from '../src/app/workbenchState'
import { getVisibleMenuGroups } from '../src/app/navigation'
import OverviewRoute from '../src/routes/OverviewRoute.vue'
import OverviewView from '../src/views/OverviewView.vue'
import { getCurrentLocale, initializeLocale, setLocale } from '../src/i18n/locale-manager'

const api = vi.hoisted(() => ({ currentSession: vi.fn(), listOperators: vi.fn(), logout: vi.fn(), setUnauthorizedHandler: vi.fn() }))
const router = vi.hoisted(() => ({ push: vi.fn() }))
vi.mock('../src/api', () => api)
vi.mock('@tanstack/vue-query', () => ({ useQueryClient: () => ({ cancelQueries: vi.fn() }) }))
vi.mock('vue-router', () => ({ useRouter: () => router }))

describe('authenticated capability contract', () => {
  beforeEach(() => {
    localStorage.clear()
    setLocale('zh-CN', false)
  })
  afterEach(() => {
    localStorage.clear()
    setLocale('zh-CN', false)
    vi.restoreAllMocks()
  })

  it.each([
    ['zh-CN', 'zh-CN'],
    [null, 'en-US'],
  ] as const)('restores the session without overwriting the explicit locale %s', async (saved, expected) => {
    vi.spyOn(navigator, 'languages', 'get').mockReturnValue(['zh-CN'])
    vi.spyOn(navigator, 'language', 'get').mockReturnValue('zh-CN')
    if (saved) localStorage.setItem('socp-locale', saved)
    initializeLocale()
    expect(getCurrentLocale()).toBe('zh-CN')
    api.currentSession.mockResolvedValue({ username: 'analyst', role: 'analyst', tenant: 'default', locale: 'en-US', permissions: [] })
    api.listOperators.mockResolvedValue({ items: [] })
    const wrapper = mount(defineComponent({ setup() {
      const auth = useAuth()
      void auth.initAuth()
      return () => h('p', { 'data-ready': auth.authReady.value })
    } }))
    await flushPromises()
    expect(wrapper.get('p').attributes('data-ready')).toBe('true')
    expect(getCurrentLocale()).toBe(expected)
    expect(document.documentElement.lang).toBe(expected)
    expect(localStorage.getItem('socp-locale')).toBe(saved)
    wrapper.unmount()
  })

  it('carries explicit session permissions to SOAR actions and navigation without granting incident writes', async () => {
    api.currentSession.mockResolvedValue({ username: 'reviewer', role: 'viewer', tenant: 'tenant-a', permissions: ['soar:view', 'soar:approve', 'soar:publish'] })
    api.listOperators.mockResolvedValue({ items: [{ id: 'colleague' }] })
    const capabilities = defineComponent({ setup() {
      const soar = useSoarAccess(); const write = useWriteAccess()
      return () => h('p', { 'data-approve': soar.canApprove.value, 'data-publish': soar.canPublish.value, 'data-write': write.value })
    } })
    const wrapper = mount(defineComponent({ setup() {
      const auth = useAuth()
      provide(WORKBENCH_STATE, auth)
      void auth.initAuth()
      return () => h('main', [h(capabilities), h('nav', getVisibleMenuGroups(auth.currentRole.value, undefined, auth.currentPermissions.value).flatMap(group => group.items.map(item => item.key)).join(','))])
    } }))
    await flushPromises()
    expect(wrapper.get('p').attributes()).toMatchObject({ 'data-approve': 'true', 'data-publish': 'true', 'data-write': 'false' })
    expect(wrapper.get('nav').text()).toContain('soar')
    expect(wrapper.get('nav').text()).not.toContain('detect')
    wrapper.unmount()
  })

  it.each([
    ['viewer', [], false],
    ['viewer', ['soar:view'], false],
    ['viewer', ['soar:approve'], true],
    ['viewer', ['soar:publish'], true],
    ['analyst', [], true],
    ['approver', ['soar:approve'], false],
  ] as const)('keeps the overview SOAR shortcut aligned with navigation for %s / %j', async (role, permissions, visible) => {
    const overview = {
      stat: ref({}), sitStats: ref({}), alarms: ref([]), priorityAlarms: ref([]), healths: ref({}),
      error: ref(''), loading: ref(false), availability: ref({}), healthStatus: ref('success'),
      refreshing: ref(false), updatedAt: ref(0),
    }
    const wrapper = shallowMount(OverviewRoute, {
      global: { provide: { [WORKBENCH_STATE as symbol]: { currentRole: ref(role), currentPermissions: ref(permissions), overview } } },
    })
    const shortcut = wrapper.findComponent(OverviewView).props('goSoar')
    const navigation = getVisibleMenuGroups(role, undefined, permissions).flatMap(group => group.items.map(item => item.key))
    expect(Boolean(shortcut)).toBe(visible)
    expect(navigation.includes('soar')).toBe(visible)
    if (shortcut) { shortcut(); expect(router.push).toHaveBeenCalledWith({ name: 'soar' }) }
    wrapper.unmount()
  })
})
