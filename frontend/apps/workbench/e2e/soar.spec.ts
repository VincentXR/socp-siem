import { expect, test, type Page } from '@playwright/test'
import { isWorkbenchBackendUrl } from './helpers'

const SIMPLE_DEFINITION = {
  schemaVersion: 'soar.playbook',
  entryNodeId: 'start',
  nodes: [
    { id: 'start', type: 'START', name: 'Start' },
    { id: 'end', type: 'END', name: 'End', outcome: 'SUCCEEDED' },
  ],
  edges: [{ from: 'start', to: 'end' }],
}

const EDITED_DEFINITION = {
  ...SIMPLE_DEFINITION,
  nodes: [
    { id: 'start', type: 'START', name: 'Browser start' },
    { id: 'end', type: 'END', name: 'End', outcome: 'SUCCEEDED' },
  ],
}

const EXISTING_PLAYBOOK = {
  id: 'pb-existing', name: 'Existing response', description: 'fixture', owner: 'analyst', status: 'ACTIVE',
  latestPublishedVersion: 1, draftVersion: null, tags: ['fixture'], createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z',
}

const EXISTING_VERSION = {
  id: 'ver-existing', playbookId: 'pb-existing', version: 1, status: 'PUBLISHED',
  schemaVersion: 'soar.playbook', definition: SIMPLE_DEFINITION, layout: {}, definitionHash: 'fixture-hash',
  riskSummary: { actionCount: 0, highRiskActionCount: 0 }, rowVersion: 1,
}

type MockState = {
  playbooks: Array<Record<string, unknown>>
  versions: Record<string, Array<Record<string, unknown>>>
  runs: Array<Record<string, unknown>>
  approvals: Array<Record<string, unknown>>
  tasks: Array<Record<string, unknown>>
  deadLetters: Array<Record<string, unknown>>
  requests: Array<{ method: string; path: string }>
  unknown: string[]
}

function envelope<T>(data: T): string {
  return JSON.stringify({ code: 0, message: 'OK', data })
}

function pageData<T>(items: T[]) {
  return { page: 0, size: 100, total: items.length, items }
}

function runFixture() {
  return {
    runId: 'run-1', requestId: 'request-1', playbookId: 'pb-existing', playbookVersionId: 'ver-existing',
    playbookVersion: 1, status: 'SUCCEEDED', triggerType: 'alert.created', definitionHash: 'fixture-hash',
    temporalWorkflowId: 'soar-run-1', temporalRunId: 'temporal-run-1', createdAt: '2026-01-01T00:00:00Z',
  }
}

async function installSoarMocks(page: Page, role: 'analyst' | 'admin' = 'analyst'): Promise<MockState> {
  const state: MockState = {
    playbooks: [EXISTING_PLAYBOOK],
    versions: { 'pb-existing': [EXISTING_VERSION] },
    runs: [runFixture()],
    approvals: [{ id: 'approval-1', runId: 'run-1', actionRef: 'fixture.action', reason: 'Review required', status: 'PENDING', requestedBy: 'automation', createdAt: '2026-01-01T00:00:00Z' }],
    tasks: [{ id: 'task-1', runId: 'run-1', nodeId: 'manual-1', formSchema: {}, status: 'PENDING', assignee: 'analyst' }],
    deadLetters: [],
    requests: [],
    unknown: [],
  }

  await page.route('**/*', async route => {
    const request = route.request()
    const url = new URL(request.url())
    if (!isWorkbenchBackendUrl(url)) {
      await route.continue()
      return
    }
    if (url.pathname === '/auth/session') {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ username: role, role, tenant: 'default' }) })
      return
    }
    if (url.pathname === '/auth/operators') {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ items: [{ id: role }] }) })
      return
    }
    if (!url.pathname.startsWith('/soar-web/')) {
      state.unknown.push(`${request.method()} ${url.pathname}`)
      await route.abort()
      return
    }

    const method = request.method()
    const path = url.pathname
    state.requests.push({ method, path })
    if (path.endsWith('/stream')) {
      // The inspector records a polling state after a closed stream. This
      // keeps the browser test deterministic without a long-lived connection.
      await route.abort()
      return
    }

    const parts = path.split('/').filter(Boolean)
    const api = parts.slice(0, 4).join('/')
    let status = 200
    let data: unknown = {}

    if (method === 'GET' && api === 'soar-web/api/playbooks' && parts.length === 3) {
      data = pageData(state.playbooks)
    } else if (method === 'POST' && api === 'soar-web/api/playbooks' && parts.length === 3) {
      const body = request.postDataJSON() as { name?: string; description?: string; tags?: string[] }
      const playbook = {
        id: 'pb-browser', name: body.name || 'Browser response', description: body.description || '', owner: 'analyst', status: 'ACTIVE',
        latestPublishedVersion: null, draftVersion: 1, tags: body.tags || [], rowVersion: 1,
      }
      state.playbooks.unshift(playbook)
      state.versions[playbook.id] = []
      data = playbook
      status = 201
    } else if (method === 'GET' && parts[0] === 'soar-web' && parts[1] === 'api' && parts[2] === 'playbooks' && parts.length === 5 && parts[4] === 'versions') {
      data = state.versions[parts[3]] || []
    } else if (method === 'POST' && parts[0] === 'soar-web' && parts[1] === 'api' && parts[2] === 'playbooks' && parts.length === 5 && parts[4] === 'versions') {
      const version = { id: 'ver-browser', playbookId: parts[3], version: 1, status: 'DRAFT', schemaVersion: 'soar.playbook', definition: SIMPLE_DEFINITION, layout: {}, definitionHash: 'browser-hash', riskSummary: { actionCount: 0, highRiskActionCount: 0 }, rowVersion: 1 }
      state.versions[parts[3]] = [version]
      data = version
      status = 201
    } else if (method === 'GET' && parts[0] === 'soar-web' && parts[1] === 'api' && parts[2] === 'playbooks' && parts.length === 6 && parts[4] === 'versions') {
      data = (state.versions[parts[3]] || []).find(version => String(version.version) === parts[5]) || {}
    } else if (method === 'PUT' && parts[0] === 'soar-web' && parts[1] === 'api' && parts[2] === 'playbooks' && parts.length === 6 && parts[4] === 'versions') {
      const body = request.postDataJSON() as { definition?: unknown; layout?: unknown; rowVersion?: number }
      const versions = state.versions[parts[3]] || []
      const current = versions.find(version => String(version.version) === parts[5]) || { id: 'ver-browser', playbookId: parts[3], version: Number(parts[5]) }
      Object.assign(current, { status: 'DRAFT', schemaVersion: 'soar.playbook', definition: body.definition, layout: body.layout || {}, definitionHash: 'browser-saved-hash', riskSummary: { actionCount: 0, highRiskActionCount: 0 }, rowVersion: (body.rowVersion || 1) + 1 })
      state.versions[parts[3]] = [current]
      data = current
    } else if (method === 'POST' && parts[0] === 'soar-web' && parts[1] === 'api' && parts[2] === 'playbooks' && parts.length === 7 && parts[4] === 'versions' && parts[6] === 'validate') {
      data = { valid: true, errors: [], warnings: [], schemaVersion: 'soar.playbook', definitionHash: 'browser-saved-hash' }
    } else if (method === 'POST' && parts[0] === 'soar-web' && parts[1] === 'api' && parts[2] === 'playbooks' && parts.length === 7 && parts[4] === 'versions' && parts[6] === 'publish') {
      const version = (state.versions[parts[3]] || []).find(item => String(item.version) === parts[5])
      if (version) Object.assign(version, { status: 'PUBLISHED', publishedAt: '2026-01-01T00:00:00Z' })
      const playbook = state.playbooks.find(item => item.id === parts[3])
      if (playbook) Object.assign(playbook, { latestPublishedVersion: Number(parts[5]), draftVersion: null })
      data = version || {}
    } else if (method === 'GET' && api === 'soar-web/api/runs' && parts.length === 3) {
      data = pageData(state.runs)
    } else if (method === 'POST' && api === 'soar-web/api/runs' && parts.length === 3) {
      const body = request.postDataJSON() as { requestId?: string; playbookVersionId?: string; subject?: unknown; inputs?: unknown }
      const queued = {
        runId: 'run-browser-queued', requestId: body.requestId || 'workbench-request', playbookId: 'pb-existing',
        playbookVersionId: body.playbookVersionId || 'ver-existing', playbookVersion: 1, status: 'QUEUED',
        triggerType: 'manual', definitionHash: 'fixture-hash', temporalWorkflowId: 'soar-run-browser-queued',
        createdAt: '2026-01-01T00:01:00Z', subject: body.subject, inputs: body.inputs,
      }
      state.runs = [queued, ...state.runs.filter(item => item.runId !== queued.runId)]
      data = queued
      status = 202
    } else if (method === 'GET' && parts[0] === 'soar-web' && parts[1] === 'api' && parts[2] === 'runs' && parts.length === 4) {
      data = state.runs.find(item => item.runId === parts[3]) || {}
    } else if (method === 'GET' && parts[0] === 'soar-web' && parts[1] === 'api' && parts[2] === 'runs' && parts.length === 5 && parts[4] === 'nodes') {
      data = [{ id: 'node-run-1', runId: 'run-1', nodeId: 'start', nodeType: 'START', status: 'SUCCEEDED' }]
    } else if (method === 'GET' && parts[0] === 'soar-web' && parts[1] === 'api' && parts[2] === 'runs' && parts.length === 5 && parts[4] === 'events') {
      data = pageData([])
    } else if (method === 'GET' && parts[0] === 'soar-web' && parts[1] === 'api' && parts[2] === 'runs' && parts.length === 5 && parts[4] === 'artifacts') {
      data = []
    } else if (method === 'GET' && parts[0] === 'soar-web' && parts[1] === 'api' && parts[2] === 'node-runs' && parts.length === 5 && parts[4] === 'attempts') {
      data = pageData([])
    } else if (method === 'GET' && api === 'soar-web/api/approvals' && parts.length === 3) {
      data = state.approvals
    } else if (method === 'POST' && parts[0] === 'soar-web' && parts[1] === 'api' && parts[2] === 'approvals' && parts.length === 5) {
      const approval = state.approvals.find(item => item.id === parts[3])
      if (approval) Object.assign(approval, { status: parts[4] === 'approve' ? 'APPROVED' : 'REJECTED', decisionReason: 'Reviewed by browser test' })
      data = approval || {}
    } else if (method === 'GET' && api === 'soar-web/api/templates' && parts.length === 3) {
      data = []
    } else if (method === 'GET' && api === 'soar-web/api/automation-rules' && parts.length === 3) {
      data = pageData([])
    } else if (method === 'GET' && api === 'soar-web/api/connections' && parts.length === 3) {
      data = pageData([])
    } else if (method === 'GET' && api === 'soar-web/api/actions' && parts.length === 3) {
      data = []
    } else if (method === 'GET' && api === 'soar-web/api/manual-tasks' && parts.length === 3) {
      data = pageData(state.tasks)
    } else if (method === 'POST' && parts[0] === 'soar-web' && parts[1] === 'api' && parts[2] === 'manual-tasks' && parts.length === 5 && parts[4] === 'complete') {
      state.tasks = state.tasks.filter(task => task.id !== parts[3])
      data = { id: parts[3], status: 'COMPLETED' }
    } else if (method === 'GET' && parts[0] === 'soar-web' && parts[1] === 'api' && parts[2] === 'operations' && parts.length === 4 && parts[3] === 'dead-dispatches') {
      data = state.deadLetters
    } else if (method === 'GET' && api === 'soar-web/api/stats' && parts.length === 3) {
      data = { runsByStatus: { SUCCEEDED: 1 }, dispatchBacklog: 0, signalBacklog: 0, generatedAt: '2026-01-01T00:00:00Z' }
    } else {
      state.unknown.push(`${method} ${path}`)
      await route.fulfill({ status: 404, contentType: 'application/json', body: envelope({}) })
      return
    }
    await route.fulfill({ status, contentType: 'application/json', body: envelope(data) })
  })
  return state
}

test('SOAR workbench covers draft lifecycle, run inspection and human controls', async ({ page }, testInfo) => {
  await page.addInitScript(() => localStorage.setItem('socp-locale', 'en-US'))
  // Publishing is intentionally restricted to the admin role; the backend
  // permission contract does not grant `soar:publish` to analysts.
  const state = await installSoarMocks(page, 'admin')
  await page.goto('/soar')
  await expect(page.locator('.soar-view')).toBeVisible()

  await page.getByRole('button', { name: /Create Playbook|新建剧本/ }).click()
  await page.getByRole('dialog', { name: 'Choose template' }).getByRole('button', { name: 'Blank playbook', exact: true }).click()
  await expect(page).toHaveURL(/\/soar\/playbooks\/new$/)
  await expect(page.locator('.soar-editor')).toBeVisible()
  const createDialog = page.getByRole('dialog', { name: /Create blank playbook|创建空白剧本/ })
  await expect(createDialog).toBeVisible()
  await createDialog.getByPlaceholder(/Account takeover response|账号接管响应/).fill('Browser response')
  await createDialog.getByRole('button', { name: /Create and open canvas|创建并打开画布/ }).click()
  await expect(page.locator('.soar-editor')).toBeVisible()
  await expect(page.locator('.soar-editor-message')).toBeVisible()

  await page.getByLabel('Definition JSON').fill(JSON.stringify(EDITED_DEFINITION))
  await page.getByRole('button', { name: 'Apply JSON' }).click()
  await page.getByRole('button', { name: 'Save draft' }).click()
  await expect(page.locator('.soar-editor-message')).toContainText('Saved draft revision 1')
  await page.getByRole('button', { name: 'Validate' }).click()
  await expect(page.locator('.soar-editor-message')).toContainText('Definition is publishable')
  await page.locator('.soar-editor').getByRole('button', { name: 'Publish', exact: true }).click()
  // Publishing asks for an explicit confirmation (an Element Plus message box)
  // that carries the revision number being made live.
  const publishConfirm = page.locator('.el-message-box')
  await expect(publishConfirm).toContainText('Publish version 1')
  await publishConfirm.getByRole('button', { name: /Confirm|确认/ }).click()
  await expect(page.locator('.soar-editor-message')).toContainText('Published revision 1')
  await expect(publishConfirm).not.toBeVisible()
  await page.screenshot({ path: testInfo.outputPath('soar-editor.png'), fullPage: true })

  await page.getByRole('button', { name: 'Back to list', exact: true }).click()
  await page.getByRole('tab', { name: 'Runs' }).click()
  await expect(page.locator('.soar-run-summary')).toContainText('run-1')
  await expect(page.locator('.soar-run-summary')).toContainText('Succeeded')
  await page.getByRole('button', { name: 'Queue run' }).click()
  const queueDialog = page.getByRole('dialog', { name: 'Queue a published playbook run' })
  await expect(queueDialog).toBeVisible()
  await expect(queueDialog.getByRole('button', { name: 'Accept and queue' })).toBeEnabled()
  await queueDialog.getByLabel('Inputs JSON').fill('{"eventId":"browser-queued","eventType":"manual.test"}')
  await queueDialog.getByRole('button', { name: 'Accept and queue' }).click()
  await expect(page.locator('.soar-queue-message')).toContainText('run-browser-queued')
  await expect(page.locator('.soar-stream-state')).toHaveClass(/polling/)
  await page.getByRole('button', { name: /Open in visual editor|在可视化编辑器中打开/ }).click()
  await expect(page.locator('.soar-editor-message')).toContainText('Loaded the revision 1 run path')
  await expect(page.getByRole('dialog', { name: 'Create blank playbook' })).not.toBeVisible()

  await page.getByRole('button', { name: 'Back to list', exact: true }).click()
  await page.getByRole('tab', { name: /Approvals/ }).click()
  const approvalTable = page.locator('.soar-approval-table')
  await expect(approvalTable).toContainText('run-1')
  await approvalTable.getByRole('button', { name: 'Approve' }).click()
  const approvalDialog = page.locator('.el-dialog').filter({ hasText: 'run-1' })
  await approvalDialog.locator('textarea').fill('Reviewed by browser test')
  await approvalDialog.getByRole('button', { name: 'Approve' }).click()
  await expect(approvalTable).not.toContainText('approval-1')

  const taskRow = page.locator('.soar-control-plane table tr').filter({ hasText: 'task-1' })
  await expect(taskRow).toBeVisible()
  await taskRow.getByRole('button', { name: 'Review task' }).click()
  const taskDrawer = page.getByRole('dialog', { name: 'Review task' })
  await taskDrawer.getByText('Advanced configuration', { exact: true }).click()
  await taskDrawer.getByRole('textbox').fill('{"decision":"allow"}')
  await page.screenshot({ path: testInfo.outputPath('manual-task.png'), fullPage: true })
  await taskDrawer.getByRole('button', { name: 'Complete task' }).click()
  await expect(taskRow).toHaveCount(0)

  expect(state.unknown).toEqual([])
  expect(state.requests).toContainEqual({ method: 'POST', path: '/soar-web/api/playbooks/pb-browser/versions/1/publish' })
  expect(state.requests).toContainEqual({ method: 'POST', path: '/soar-web/api/approvals/approval-1/approve' })
  expect(state.requests).toContainEqual({ method: 'POST', path: '/soar-web/api/manual-tasks/task-1/complete' })
  expect(state.requests).toContainEqual({ method: 'POST', path: '/soar-web/api/runs' })
})

test('SOAR run queue reports an explicit permission denial', async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem('socp-locale', 'en-US'))
  const state = await installSoarMocks(page)
  await page.route('**/soar-web/api/runs', async route => {
    if (route.request().method() !== 'POST') {
      await route.fallback()
      return
    }
    await route.fulfill({ status: 403, contentType: 'application/json', body: JSON.stringify({ code: 403, message: 'SOAR execute permission required' }) })
  })
  await page.goto('/soar')
  await page.getByRole('tab', { name: 'Runs' }).click({ force: true })
  await page.getByRole('button', { name: 'Queue run' }).click()
  const queueDialog = page.getByRole('dialog', { name: 'Queue a published playbook run' })
  await expect(queueDialog.getByRole('button', { name: 'Accept and queue' })).toBeEnabled()
  await queueDialog.getByRole('button', { name: 'Accept and queue' }).click()
  await expect(queueDialog.getByRole('alert')).toContainText('SOAR execute permission required')
  expect(state.unknown).toEqual([])
})

test('SOAR run controls surface permission failures without breaking the inspector', async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem('socp-locale', 'en-US'))
  const state = await installSoarMocks(page)
  await page.route('**/soar-web/api/runs/run-1/rerun', async route => {
    await route.fulfill({ status: 403, contentType: 'application/json', body: JSON.stringify({ code: 403, message: 'SOAR rerun permission required' }) })
  })
  await page.goto('/soar')
  await page.getByRole('tab', { name: 'Runs' }).click({ force: true })
  // The rerun confirmation is an Element Plus message box, not a native dialog.
  await page.getByRole('button', { name: 'Rerun' }).click()
  const rerunConfirm = page.locator('.el-message-box')
  await expect(rerunConfirm).toBeVisible()
  await rerunConfirm.getByRole('button', { name: /Confirm|确认/ }).click()
  await expect(page.locator('.soar-inspector-error[role="alert"]')).toContainText('SOAR rerun permission required')
  await expect(page.locator('.soar-run-summary')).toContainText('run-1')
  expect(state.unknown).toEqual([])
})

test('SOAR canvas shortcuts work after a dialog closes and the context menu supports keyboard navigation', async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem('socp-locale', 'en-US'))
  const state = await installSoarMocks(page, 'admin')
  state.versions['pb-existing'] = [{ ...EXISTING_VERSION, status: 'DRAFT' }]
  await page.goto('/soar/playbooks/pb-existing/edit')
  await expect(page.locator('.vue-flow__node[data-id="end"]')).toBeVisible()
  const start = page.locator('.vue-flow__node[data-id="start"]')
  // The rename prompt creates an Element Plus overlay which remains in the DOM after closing.
  await start.dblclick()
  const rename = page.locator('.el-message-box')
  await expect(rename).toBeVisible()
  await rename.locator('input').fill('Keyboard renamed')
  await rename.getByRole('button', { name: 'Confirm', exact: true }).click()
  await expect(rename).not.toBeVisible()
  await expect(start).toContainText('Keyboard renamed')
  await start.focus()
  await page.keyboard.press('ControlOrMeta+s')
  await expect(page.locator('.soar-editor-message')).toContainText('Saved draft revision 1')
  expect(state.requests.filter(request => request.method === 'PUT')).toHaveLength(1)

  await start.focus()
  await page.keyboard.press('Shift+F10')
  const menu = page.getByRole('menu', { name: 'Node actions menu' })
  await expect(menu).toBeVisible()
  await expect(menu.getByRole('menuitem', { name: 'Rename' })).toBeFocused()
  await page.keyboard.press('ArrowDown')
  await expect(menu.getByRole('menuitem', { name: 'Copy' })).toBeFocused()
  await page.keyboard.press('ArrowDown')
  await expect(menu.getByRole('menuitem', { name: 'Rename' })).toBeFocused()
  await page.keyboard.press('End')
  await expect(menu.getByRole('menuitem', { name: 'Copy' })).toBeFocused()
  await page.keyboard.press('Escape')
  await expect(menu).not.toBeVisible()
  await expect(start).toBeFocused()
  expect(state.unknown).toEqual([])
})

test('SOAR keyboard actions leave inputs, dialogs and other page controls alone', async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem('socp-locale', 'en-US'))
  const state = await installSoarMocks(page, 'admin')
  state.versions['pb-existing'] = [{ ...EXISTING_VERSION, status: 'DRAFT' }]
  await page.goto('/soar/playbooks/pb-existing/edit')
  const end = page.locator('.vue-flow__node[data-id="end"]')
  await expect(end).toBeVisible()
  await end.click()
  const definition = page.getByLabel('Definition JSON')
  await definition.focus()
  // Synthetic cancellation checks isolate app handling from the browser's native menu.
  expect(await definition.evaluate(input => input.dispatchEvent(new KeyboardEvent('keydown', { key: 'ContextMenu', bubbles: true, cancelable: true })))).toBe(true)
  await expect(page.getByRole('menu', { name: 'Node actions menu' })).not.toBeVisible()
  await page.getByRole('button', { name: 'Back to list', exact: true }).focus()
  await page.keyboard.press('Delete')
  await expect(end).toBeVisible()
  await end.dblclick()
  const rename = page.locator('.el-message-box')
  await expect(rename).toBeVisible()
  const input = rename.locator('input')
  expect(await input.evaluate(element => element.dispatchEvent(new KeyboardEvent('keydown', { key: 'F10', shiftKey: true, bubbles: true, cancelable: true })))).toBe(true)
  await expect(page.getByRole('menu', { name: 'Node actions menu' })).not.toBeVisible()
  await rename.getByRole('button', { name: 'Cancel', exact: true }).click()
  expect(state.requests.filter(request => request.method === 'PUT')).toHaveLength(0)
  expect(state.unknown).toEqual([])
})

test('SOAR repeated save shortcuts submit once and a delayed save preserves newer canvas edits', async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem('socp-locale', 'en-US'))
  const state = await installSoarMocks(page, 'admin')
  state.versions['pb-existing'] = [{ ...EXISTING_VERSION, status: 'DRAFT' }]
  let release: () => void = () => {}
  const pending = new Promise<void>(resolve => { release = resolve })
  let saves = 0
  await page.route('**/soar-web/api/playbooks/pb-existing/versions/1', async route => {
    if (route.request().method() === 'PUT') { saves++; await pending }
    await route.fallback()
  })
  await page.goto('/soar/playbooks/pb-existing/edit')
  const start = page.locator('.vue-flow__node[data-id="start"]')
  await expect(start).toBeVisible()
  await page.getByLabel('Definition JSON').fill(JSON.stringify(EDITED_DEFINITION))
  await page.getByRole('button', { name: 'Apply JSON' }).click()
  await start.focus()
  const sent = page.waitForRequest(request => request.method() === 'PUT')
  await page.keyboard.press('ControlOrMeta+s')
  await sent
  await page.keyboard.press('ControlOrMeta+s')
  expect(saves).toBe(1)
  const newer = { ...EDITED_DEFINITION, nodes: [{ ...EDITED_DEFINITION.nodes[0], name: 'Unsaved newer edit' }, EDITED_DEFINITION.nodes[1]] }
  await page.getByLabel('Definition JSON').fill(JSON.stringify(newer))
  await page.getByRole('button', { name: 'Apply JSON' }).click()
  await expect(start).toContainText('Unsaved newer edit')
  release()
  await expect(page.locator('.soar-editor-message')).toContainText('Saved draft revision 1')
  await expect(start).toContainText('Unsaved newer edit')
  await expect(page.getByRole('button', { name: 'Save draft' })).toBeEnabled()
  const persisted = state.versions['pb-existing'][0].definition as typeof EDITED_DEFINITION
  expect(persisted.nodes[0].name).toBe('Browser start')
})
