import { expect, test } from '@playwright/test'
import { isWorkbenchBackendUrl } from './helpers'

test('reset abandons a pending answer and permits a new question', async ({ page }) => {
  const unexpected: string[] = [], errors: string[] = []
  let release!: () => void, calls = 0
  const gate = new Promise<void>(resolve => { release = resolve })
  page.on('pageerror', error => errors.push(error.message))
  await page.route('**/*', async route => {
    const url = new URL(route.request().url()), path = url.pathname
    if (!isWorkbenchBackendUrl(url)) { await route.continue(); return }
    let data: unknown
    if (path === '/auth/session') data = { username: 'analyst', role: 'analyst', tenant: 'default', locale: 'en-US' }
    else if (path === '/auth/operators') data = { items: [] }
    else if (path === '/ai-assistant/api/v1/ai/ask') {
      const question = route.request().postDataJSON().question as string
      if (++calls === 1) await gate
      data = { question, answer: `answer ${question}`, source: 'KNOWLEDGE_BASE', suggestion: null, elapsedMs: 1 }
    } else { unexpected.push(`${route.request().method()} ${path}`); await route.abort(); return }
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify(path.startsWith('/auth/') ? data : { code: 0, data }) })
  })
  try {
    await page.goto('/assistant')
    const question = page.locator('.ai-panel').first().getByRole('textbox')
    await question.fill('first')
    await page.getByRole('button', { name: 'Ask AI', exact: true }).click()
    await expect.poll(() => calls).toBe(1)
    await page.getByRole('button', { name: 'Reset', exact: true }).click()
    await expect(question).toHaveValue('')
    await question.fill('second')
    await page.getByRole('button', { name: 'Ask AI', exact: true }).click()
    await expect(page.locator('.ai-result-answer')).toHaveText('answer second')
    release()
    await expect(page.locator('.ai-result-question')).toContainText('second')
    await expect(page.locator('.ai-error')).toHaveCount(0)
    expect(unexpected).toEqual([]); expect(errors).toEqual([])
  } finally { release() }
})
