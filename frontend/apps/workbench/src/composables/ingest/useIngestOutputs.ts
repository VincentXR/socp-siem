import { computed, ref, watch } from 'vue'
import { ApiError, createOutput, updateOutput, deleteOutput, listOutputs, validateOutputConfig, renderConfig, type SinkTarget } from '../../api'
import { useI18n } from '../useI18n'
import { useIngestCopy } from '../useIngestCopy'
import { useFormDialog } from '../useFormDialog'
import type { IngestWorkspace } from './useIngestWorkspace'

export function useIngestOutputs(workspace: IngestWorkspace) {
  const { canWrite, actionBusy, actionError, loadStates, loadList, confirmWrite } = workspace
  const { t } = useI18n()
  const copy = useIngestCopy()
  const outputs = ref<SinkTarget[]>([])
  const outputsLoading = computed(() => Boolean(loadStates.value.outputs))
  const editingOutputId = ref<string | null>(null)
  const outputValidation = ref('')
  const newOutput = ref({ name: '', type: 'GLS_INGEST', uri: '', authToken: '', enabled: true })
  const outputErrors = ref<Record<string, string>>({})
  const outputError = ref('')
  const renderError = ref('')
  const renderText = ref('')
  const showRender = ref(false)
  const showOutputDialog = ref(false)
  const credentialAction = ref<'KEEP' | 'REPLACE' | 'CLEAR'>('KEEP')
  function credentialLabel(action: string): string {
    return action === 'KEEP' ? t('workflow.credentialKEEP') : action === 'REPLACE' ? t('workflow.credentialREPLACE') : t('workflow.credentialCLEAR')
  }
  function outputLabel(id: string | null | undefined): string {
    if (!id) return copy.value.platformDefault
    const output = outputs.value.find(item => item.id === id)
    return output ? `${output.name} · ${output.id}` : id
  }
  const loadOutputs = () => loadList('outputs', signal => listOutputs({ signal }), value => { outputs.value = value })
  function validateOutput(): boolean {
    outputErrors.value = {}
    if (!newOutput.value.name.trim()) outputErrors.value.name = t('forms.fieldRequired', { field: t('ingest.outputName') })
    if (!['GLS_INGEST', 'HTTP'].includes(newOutput.value.type)) outputErrors.value.type = t('workflow.unsupported')
    if (editingOutputId.value && credentialAction.value === 'REPLACE' && !newOutput.value.authToken.trim()) outputErrors.value.authToken = t('forms.required')
    const uri = newOutput.value.uri.trim()
    if (!uri) outputErrors.value.uri = t('forms.fieldRequired', { field: t('ingest.targetUrl') })
    else {
      try {
        const url = new URL(uri)
        if (!['https:', 'http:'].includes(url.protocol) || url.username || url.password || url.hash) outputErrors.value.uri = t('forms.invalidInput')
      } catch { outputErrors.value.uri = t('forms.invalidInput') }
    }
    return !Object.keys(outputErrors.value).length
  }
  /**
   * Turns a failed vector.toml render into actionable, bilingual guidance.
   * The backend copy is Chinese-only and hard-coded, so the two known failure
   * modes (no output target -> 409, viewer role -> 403) are mapped to locale
   * keys instead of surfacing a message a non-Chinese operator cannot act on.
   * Any other status keeps the already-localized transport message untouched.
   */
  function renderFailureHint(failure: unknown): string {
    if (failure instanceof ApiError && failure.status === 409) return `${t('ingest.renderNoSinkHint')} · ${failure.message}`
    if (failure instanceof ApiError && failure.status === 403) return t('ingest.renderForbiddenHint')
    return failure instanceof Error ? failure.message : String(failure)
  }
  async function doRender() {
    if (actionBusy.value || workspace.disposed) return
    renderText.value = ''
    showRender.value = false
    await workspace.run(async () => {
      try {
        const rendered = await renderConfig()
        if (!workspace.disposed) { renderText.value = rendered; showRender.value = true }
      } catch (failure) { throw new Error(renderFailureHint(failure)) }
    }, renderError)
  }
  async function copyRender() {
    await workspace.run(async () => { await navigator.clipboard.writeText(renderText.value) }, renderError)
  }
  function openEditOutput(output: SinkTarget) {
    if (!canWrite.value || actionBusy.value || workspace.disposed) return
    editingOutputId.value = output.id
    credentialAction.value = 'KEEP'
    newOutput.value = { name: output.name, type: output.type, uri: output.uri, authToken: '', enabled: output.enabled }
    outputErrors.value = {}; outputError.value = ''; outputValidation.value = ''; actionError.value = ''; showOutputDialog.value = true
  }
  function openCreateOutput() {
    if (!canWrite.value || actionBusy.value || workspace.disposed) return
    editingOutputId.value = null
    outputValidation.value = ''
    credentialAction.value = 'REPLACE'
    newOutput.value = { name: '', type: 'GLS_INGEST', uri: '', authToken: '', enabled: true }
    outputErrors.value = {}
    outputError.value = ''
    actionError.value = ''
    showOutputDialog.value = true
  }
  function outputPayload() {
    return { name: newOutput.value.name.trim(), type: newOutput.value.type, uri: newOutput.value.uri.trim(),
      authToken: !editingOutputId.value || credentialAction.value === 'REPLACE' ? newOutput.value.authToken || null : null, enabled: newOutput.value.enabled }
  }
  async function checkOutput() {
    if (!canWrite.value || actionBusy.value || workspace.disposed || !validateOutput()) return
    outputValidation.value = ''; outputError.value = ''
    await workspace.run(async () => {
      await validateOutputConfig(outputPayload())
      if (!workspace.disposed) outputValidation.value = copy.value.validated
    }, outputError)
  }
  watch(newOutput, () => { outputValidation.value = '' }, { deep: true })
  watch(credentialAction, () => { outputValidation.value = ''; newOutput.value.authToken = ''; outputErrors.value = {} })
  async function addOutput() {
    if (!canWrite.value || actionBusy.value || workspace.disposed || !validateOutput()) return
    outputError.value = ''
    await workspace.run(async () => {
      const target = outputPayload()
      if (editingOutputId.value) await updateOutput(editingOutputId.value, target, credentialAction.value)
      else await createOutput(target)
      if (workspace.disposed) return
      newOutput.value = { name: '', type: 'GLS_INGEST', uri: '', authToken: '', enabled: true }
      showOutputDialog.value = false
      await loadOutputs()
    }, outputError)
  }
  async function removeOutput(id: string) {
    if (!await confirmWrite('ingest.deleteOutputConfirm')) return
    return workspace.run(async () => {
      await deleteOutput(id)
      await loadOutputs()
    })
  }
  const showOutputDialogGuard = useFormDialog(showOutputDialog, () => ({ output: newOutput.value, credentialAction: credentialAction.value }), () => actionBusy.value)

  return {
    outputs, outputsLoading, loadOutputs, outputLabel, credentialLabel, editingOutputId, newOutput,
    outputErrors, outputError, outputValidation, credentialAction, showOutputDialog, showOutputDialogGuard,
    openCreateOutput, openEditOutput, checkOutput, addOutput, removeOutput, renderError, renderText, showRender,
    doRender, copyRender,
  }
}
