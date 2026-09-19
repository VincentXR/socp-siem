import { afterEach, describe, expect, it } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { nextTick } from 'vue'
import ElMessageBox from 'element-plus/es/components/message-box/index.mjs'
import 'element-plus/es/components/message-box/style/css.mjs'
import { useConfirm } from '../../../../composables/useConfirm'
import { translate } from '../../../../i18n'

/**
 * Drives the real Element Plus message box, because the point of `promptInput`
 * is that an empty input stays inside the dialog instead of resolving a blank
 * reason the way `window.prompt` did. Button and validation copy comes from
 * the locale bundle, so the test cannot drift from what operators see.
 */
const { promptInput } = useConfirm()

const confirmLabel = translate('common.confirm')
const cancelLabel = translate('common.cancel')
const requiredHint = translate('common.inputRequired')

function box(): HTMLElement {
  const element = document.querySelector<HTMLElement>('.el-message-box')
  if (!element) throw new Error('the prompt dialog is not rendered')
  return element
}

function pushButton(label: string): HTMLButtonElement {
  const button = [...box().querySelectorAll<HTMLButtonElement>('.el-message-box__btns button')]
    .find(item => item.textContent?.trim() === label)
  if (!button) throw new Error(`no ${label} button in the prompt dialog`)
  return button
}

function inputField(): HTMLInputElement {
  const input = box().querySelector<HTMLInputElement>('.el-message-box__input input')
  if (!input) throw new Error('the prompt dialog has no input')
  return input
}

async function typeText(value: string): Promise<void> {
  const input = inputField()
  input.value = value
  input.dispatchEvent(new Event('input'))
  await nextTick()
}

function errorText(): string {
  return box().querySelector<HTMLElement>('.el-message-box__errormsg')?.textContent ?? ''
}

/** Observes settlement without awaiting a promise that must stay pending. */
function track<T>(promise: Promise<T>) {
  const state = { settled: false, value: undefined as T | undefined }
  void promise.then(result => {
    state.settled = true
    state.value = result
  })
  return state
}

afterEach(() => {
  ElMessageBox.close()
  document.body.replaceChildren()
})

describe('promptInput', () => {
  it('resolves the typed reason through a localized confirm button', async () => {
    const pending = track(promptInput('Retry reason'))
    await flushPromises()
    expect(box().querySelector('.el-message-box__btns')?.textContent).toContain(cancelLabel)
    await typeText('upstream queue was drained')
    pushButton(confirmLabel).click()
    await flushPromises()
    expect(pending.value).toBe('upstream queue was drained')
  })

  it('keeps the dialog open on an empty input and accepts the retry', async () => {
    const pending = track(promptInput('Evidence reference required'))
    await flushPromises()
    pushButton(confirmLabel).click()
    await flushPromises()
    expect(pending.settled).toBe(false)
    expect(errorText()).toBe(requiredHint)
    await typeText('   ')
    pushButton(confirmLabel).click()
    await flushPromises()
    expect(pending.settled).toBe(false)
    await typeText('ticket-4711')
    pushButton(confirmLabel).click()
    await flushPromises()
    expect(pending.value).toBe('ticket-4711')
  })

  it('prefills the current value so a rename can be confirmed unchanged', async () => {
    const pending = track(promptInput('Node name', { defaultValue: 'Enrich alert' }))
    await flushPromises()
    expect(inputField().value).toBe('Enrich alert')
    pushButton(confirmLabel).click()
    await flushPromises()
    expect(pending.value).toBe('Enrich alert')
  })

  it('resolves null for cancel and for the close control', async () => {
    const cancelled = track(promptInput('Cancel reason'))
    await flushPromises()
    pushButton(cancelLabel).click()
    await flushPromises()
    expect(cancelled.value).toBeNull()

    ElMessageBox.close()
    document.body.replaceChildren()

    const closed = track(promptInput('Cancel reason'))
    await flushPromises()
    box().querySelector<HTMLButtonElement>('.el-message-box__headerbtn')?.click()
    await flushPromises()
    expect(closed.value).toBeNull()
  })

  it('accepts an explicit title and keeps the message as the field label', async () => {
    const pending = track(promptInput('Resolution reason required', { title: 'Disposition' }))
    await flushPromises()
    expect(box().querySelector('.el-message-box__title')?.textContent?.trim()).toBe('Disposition')
    expect(box().querySelector('.el-message-box__content')?.textContent).toContain('Resolution reason required')
    await typeText('confirmed by the on-call analyst')
    pushButton(confirmLabel).click()
    await flushPromises()
    expect(pending.value).toBe('confirmed by the on-call analyst')
  })
})
