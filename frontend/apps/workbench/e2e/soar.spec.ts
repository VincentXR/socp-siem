import { expect, test, type Page } from '@playwright/test'

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

async function installSoarMocks(page: Page): Promise<MockState> {
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
    if (url.origin !== 'http://127.0.0.1:4173') {
      await route.continue()
      return
    }
    if (url.pathname === '/auth/session') {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ username: 'analyst', role: 'analyst', tenant: 'default' }) })
      return
    }
    if (!url.pathname.startsWith('/soar-web/')) {
      await route.continue()
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

    if (method === 'GET' && api === 'soar-web/api/playbooks' && parts.length === 4) {
      data = pageData(state.playbooks)
    } else if (method === 'POST' && api === 'soar-web/api/playbooks' && parts.length === 4) {
      const body = request.postDataJSON() as { name?: string; description?: string; tags?: string[] }
      const playbook = {
        id: 'pb-browser', name: body.name || 'Browser response', description: body.description || '', owner: 'analyst', status: 'ACTIVE',
        latestPublishedVersion: null, draftVersion: 1, tags: body.tags || [], rowVersion: 1,
      }
      state.playbooks.unshift(playbook)
      state.versions[playbook.id] = []
      data = playbook
      status = 201
    } else if (method === 'GET' && parts[0] === 'soar-web' && parts[1] === 'api' && parts[2] === 'playbooks' && parts.length === 6 && parts[4] === 'versions') {
      data = state.versions[parts[3]] || []
    } else if (method === 'POST' && parts[0] === 'soar-web' && parts[1] === 'api' && parts[2] === 'playbooks' && parts.length === 6 && parts[4] === 'versions') {
      const version = { id: 'ver-browser', playbookId: parts[3], version: 1, status: 'DRAFT', schemaVersion: 'soar.playbook', definition: SIMPLE_DEFINITION, layout: {}, definitionHash: 'browser-hash', riskSummary: { actionCount: 0, highRiskActionCount: 0 }, rowVersion: 1 }
      state.versions[parts[3]] = [version]
      data = version
      status = 201
    } else if (method === 'GET' && parts[0] === 'soar-web' && parts[1] === 'api' && parts[2] === 'playbooks' && parts.length === 7 && parts[4] === 'versions') {
      data = (state.versions[parts[3]] || []).find(version => String(version.version) === parts[5]) || {}
    } else if (method === 'PUT' && parts[0] === 'soar-web' && parts[1] === 'api' && parts[2] === 'playbooks' && parts.length === 7 && parts[4] === 'versions') {
      const body = request.postDataJSON() as { definition?: unknown; layout?: unknown; rowVersion?: number }
      const versions = state.versions[parts[3]] || []
      const current = versions.find(version => String(version.version) === parts[5]) || { id: 'ver-browser', playbookId: parts[3], version: Number(parts[5]) }
      Object.assign(current, { status: 'DRAFT', schemaVersion: 'soar.playbook', definition: body.definition, layout: body.layout || {}, definitionHash: 'browser-saved-hash', riskSummary: { actionCount: 0, highRiskActionCount: 0 }, rowVersion: (body.rowVersion || 1) + 1 })
      state.versions[parts[3]] = [current]
      data = current
    } else if (method === 'POST' && parts[0] === 'soar-web' && parts[1] === 'api' && parts[2] === 'playbooks' && parts.length === 8 && parts[4] === 'versions' && parts[6] === 'validate') {
      data = { valid: true, errors: [], warnings: [], schemaVersion: 'soar.playbook', definitionHash: 'browser-saved-hash' }
    } else if (method === 'POST' && parts[0] === 'soar-web' && parts[1] === 'api' && parts[2] === 'playbooks' && parts.length === 8 && parts[4] === 'versions' && parts[6] === 'publish') {
      const version = (state.versions[parts[3]] || []).find(item => String(item.version) === parts[5])
      if (version) Object.assign(version, { status: 'PUBLISHED', publishedAt: '2026-01-01T00:00:00Z' })
      const playbook = state.playbooks.find(item => item.id === parts[3])
      if (playbook) Object.assign(playbook, { latestPublishedVersion: Number(parts[5]), draftVersion: null })
      data = version || {}
    } else if (method === 'GET' && api === 'soar-web/api/runs' && parts.length === 4) {
      data = pageData(state.runs)
    } else if (method === 'POST' && api === 'soar-web/api/runs' && parts.length === 4) {
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
    } else if (method === 'GET' && parts[0] === 'soar-web' && parts[1] === 'api' && parts[2] === 'runs' && parts.length === 5) {
      data = state.runs.find(item => item.runId === parts[3]) || {}
    } else if (method === 'GET' && parts[0] === 'soar-web' && parts[1] === 'api' && parts[2] === 'runs' && parts.length === 6 && parts[4] === 'nodes') {
      data = [{ id: 'node-run-1', runId: 'run-1', nodeId: 'start', nodeType: 'START', status: 'SUCCEEDED' }]
    } else if (method === 'GET' && parts[0] === 'soar-web' && parts[1] === 'api' && parts[2] === 'runs' && parts.length === 6 && parts[4] === 'events') {
      data = pageData([])
    } else if (method === 'GET' && parts[0] === 'soar-web' && parts[1] === 'api' && parts[2] === 'runs' && parts.length === 6 && parts[4] === 'artifacts') {
      data = []
    } else if (method === 'GET' && parts[0] === 'soar-web' && parts[1] === 'api' && parts[2] === 'node-runs' && parts.length === 6 && parts[4] === 'attempts') {
      data = pageData([])
    } else if (method === 'GET' && api === 'soar-web/api/approvals' && parts.length === 4) {
      data = state.approvals
    } else if (method === 'POST' && parts[0] === 'soar-web' && parts[1] === 'api' && parts[2] === 'approvals' && parts.length === 6) {
      const approval = state.approvals.find(item => item.id === parts[3])
      if (approval) Object.assign(approval, { status: parts[4] === 'approve' ? 'APPROVED' : 'REJECTED', decisionReason: 'Reviewed by browser test' })
      data = approval || {}
    } else if (method === 'GET' && api === 'soar-web/api/templates' && parts.length === 4) {
      data = []
    } else if (method === 'GET' && api === 'soar-web/api/automation-rules' && parts.length === 4) {
      data = pageData([])
    } else if (method === 'GET' && api === 'soar-web/api/connections' && parts.length === 4) {
      data = pageData([])
    } else if (method === 'GET' && api === 'soar-web/api/actions' && parts.length === 4) {
      data = []
    } else if (method === 'GET' && api === 'soar-web/api/manual-tasks' && parts.length === 4) {
      data = pageData(state.tasks)
    } else if (method === 'POST' && parts[0] === 'soar-web' && parts[1] === 'api' && parts[2] === 'manual-tasks' && parts.length === 6 && parts[4] === 'complete') {
      state.tasks = state.tasks.filter(task => task.id !== parts[3])
      data = { id: parts[3], status: 'COMPLETED' }
    } else if (method === 'GET' && parts[0] === 'soar-web' && parts[1] === 'api' && parts[2] === 'operations' && parts.length === 5 && parts[3] === 'dead-dispatches') {
      data = state.deadLetters
    } else if (method === 'GET' && api === 'soar-web/api/stats' && parts.length === 4) {
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
  const state = await installSoarMocks(page)
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
  await expect(page.locator('.soar-editor-message')).toContainText('Published revision 1')
  await page.screenshot({ path: testInfo.outputPath('soar-editor.png'), fullPage: true })

  await page.getByRole('button', { name: 'Back to list', exact: true }).click()
  await page.getByRole('tab', { name: 'Runs' }).click()
  await expect(page.locator('.soar-run-summary')).toContainText('run-1')
  await expect(page.locator('.soar-run-summary')).toContainText('SUCCEEDED')
  await page.getByRole('button', { name: 'Queue run' }).click()
  const queueDialog = page.getByRole('dialog', { name: 'Queue a published SOAR run' })
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
  const approvalDialog = page.locator('.el-dialog').filter({ hasText: 'Run: run-1' })
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
  const queueDialog = page.getByRole('dialog', { name: 'Queue a published SOAR run' })
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
  page.once('dialog', dialog => dialog.accept())
  await page.getByRole('button', { name: 'Rerun' }).click()
  await expect(page.locator('.soar-inspector-error[role="alert"]')).toContainText('SOAR rerun permission required')
  await expect(page.locator('.soar-run-summary')).toContainText('run-1')
  expect(state.unknown).toEqual([])
})
