import { mount, flushPromises } from '@vue/test-utils'
import { ref } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import IngestView from '../src/views/IngestView.vue'
import { WORKBENCH_STATE } from '../src/app/workbenchState'
import { ApiError } from '../src/api/core'

const mocks = vi.hoisted(() => ({
  listSourcesPage: vi.fn().mockResolvedValue({ items: [], total: 0, page: 1, size: 20, totalPages: 0 }),
  listOutputs: vi.fn().mockResolvedValue([]),
  listParseRulesPage: vi.fn().mockResolvedValue({ items: [], total: 0, page: 1, size: 20, totalPages: 0 }),
  resolveParseRules: vi.fn().mockResolvedValue([]),
  listIngestTasks: vi.fn().mockResolvedValue([]),
  ingestSummary: vi.fn().mockResolvedValue({}),
  listCategories: vi.fn().mockResolvedValue([]),
  renderConfig: vi.fn(),
}))

// IngestView pulls every symbol from the `../api` barrel; keep the real module
// (SOURCE_TYPES, types, unused mutators) and only swap the calls the view makes.
vi.mock('../src/api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../src/api')>()),
  ...mocks,
}))

function mountView() {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', component: { template: '<div />' } }] })
  return mount(IngestView, {
    global: { plugins: [router], provide: { [WORKBENCH_STATE as symbol]: { currentRole: ref('admin') } } },
  })
}

async function clickRender(wrapper: ReturnType<typeof mountView>) {
  const button = wrapper.findAll('button').find(item => item.text().includes('渲染 vector.toml'))
  expect(button, 'render button must be reachable').toBeTruthy()
  await button!.trigger('click')
  await flushPromises()
}

describe('IngestView vector.toml render failures', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mocks.listSourcesPage.mockResolvedValue({ items: [], total: 0, page: 1, size: 20, totalPages: 0 })
    mocks.listOutputs.mockResolvedValue([])
    mocks.listParseRulesPage.mockResolvedValue({ items: [], total: 0, page: 1, size: 20, totalPages: 0 })
    mocks.resolveParseRules.mockResolvedValue([])
    mocks.listIngestTasks.mockResolvedValue([])
    mocks.ingestSummary.mockResolvedValue({})
    mocks.listCategories.mockResolvedValue([])
  })

  it('surfaces an actionable localized hint when the backend reports HTTP 409 (no output target)', async () => {
    // Backend copy is Chinese-only; the render path must replace it with the locale key
    // so the operator gets an actionable, language-correct next step.
    mocks.renderConfig.mockRejectedValue(new ApiError(409, '日志源 fw 没有可用输出目标：请在「输出目标」创建 sink', '日志源 fw 没有可用输出目标：请在「输出目标」创建 sink'))
    const wrapper = mountView()
    await flushPromises()
    await clickRender(wrapper)

    const alerts = wrapper.findAll('[role="alert"]')
    const text = alerts.map(alert => alert.text()).join(' ')
    expect(text).toContain('渲染失败：当前日志源没有可用的输出目标')
    expect(text).toContain('SOCP_VECTOR_URI')
    // The raw Chinese-only backend sentence is not what we render.
    expect(text).not.toContain('请在「输出目标」创建 sink 并绑定该源')
    wrapper.unmount()
  })

  it('surfaces a role-scoped hint when a viewer is denied with HTTP 403', async () => {
    mocks.renderConfig.mockRejectedValue(new ApiError(403, '当前账号无权执行该操作', '当前账号无权执行该操作'))
    const wrapper = mountView()
    await flushPromises()
    await clickRender(wrapper)

    const text = wrapper.findAll('[role="alert"]').map(alert => alert.text()).join(' ')
    expect(text).toContain('需要 analyst 或 admin 角色')
    wrapper.unmount()
  })

  it('keeps a generic localized message for unrelated render failures', async () => {
    mocks.renderConfig.mockRejectedValue(new ApiError(502, '服务暂时不可用', 'HTTP 502'))
    const wrapper = mountView()
    await flushPromises()
    await clickRender(wrapper)

    const text = wrapper.findAll('[role="alert"]').map(alert => alert.text()).join(' ')
    expect(text).toContain('服务暂时不可用')
    expect(text).not.toContain('没有可用的输出目标')
    wrapper.unmount()
  })
})
