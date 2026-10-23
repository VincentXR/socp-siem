import { mount, flushPromises } from '@vue/test-utils'
import { h, ref } from 'vue'
import { createRouter, createMemoryHistory, RouterView } from 'vue-router'
import { expect, it, vi } from 'vitest'
import SoarView from '../src/views/SoarView.vue'
import { WORKBENCH_STATE } from '../src/app/workbenchState'

const confirm = vi.hoisted(() => vi.fn(async () => false))
vi.mock('../src/composables/useConfirm', () => ({ useConfirm: () => ({ confirmDanger: confirm }) }))
vi.mock('../src/api', async original => ({ ...await original<object>(),
  listPlaybooks: vi.fn(async () => ({ page: 0, size: 25, total: 0, totalPages: 0, items: [] })),
  listTemplates: vi.fn(async () => []),
}))
vi.mock('../src/components/soar/SoarEditor.vue', async () => {
  const { defineComponent, ref, h } = await import('vue')
  const { onBeforeRouteLeave } = await import('vue-router')
  return { default: defineComponent({
    setup(_, { expose }) {
      const dirty = ref(false)
      // The real editor owns dialog guards on the destination record too.
      onBeforeRouteLeave(() => true)
      expose({ hasUnsavedChanges: dirty })
      return () => h('button', { onClick: () => { dirty.value = true } }, 'Change graph')
    },
  }) }
})

it('preserves the dirty editor guard after catalog, create and edit routes reuse the view', async () => {
  const page = () => Promise.resolve(SoarView)
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/soar', name: 'soar', component: page },
    { path: '/soar/playbooks/new', name: 'playbook-new', component: page, meta: { editor: true } },
    { path: '/soar/playbooks/:playbookId/edit', name: 'playbook-edit', component: page, meta: { editor: true } },
    { path: '/metadata', component: { template: '<p>Metadata</p>' } },
  ] })
  await router.push('/soar'); await router.isReady()
  const wrapper = mount({ render: () => h(RouterView) }, { global: {
    plugins: [router], provide: { [WORKBENCH_STATE as symbol]: { currentRole: ref('admin') } },
  } })
  try {
    await flushPromises()
    await router.push('/soar/playbooks/new'); await flushPromises()
    await router.replace('/soar/playbooks/created/edit'); await flushPromises()
    await router.push('/metadata'); await flushPromises()
    await router.push('/soar'); await flushPromises()
    await router.push('/soar/playbooks/created/edit'); await flushPromises()
    await wrapper.findAll('button').find(button => button.text() === 'Change graph')!.trigger('click'); await flushPromises()
    await router.push('/metadata'); await flushPromises()
    expect(confirm).toHaveBeenCalledOnce()
    expect(router.currentRoute.value.path).toBe('/soar/playbooks/created/edit')
    confirm.mockResolvedValueOnce(true)
    await router.push('/metadata'); await flushPromises()
    expect(router.currentRoute.value.path).toBe('/metadata')
    expect(confirm).toHaveBeenCalledTimes(2)
    wrapper.unmount()
    await router.push('/soar'); await router.push('/metadata')
    expect(confirm).toHaveBeenCalledTimes(2)
  } finally { wrapper.unmount() }
})
