import { computed, onUnmounted, ref, watch } from 'vue'
import { createSource, deleteSource, updateSource, listCategories, listParseRulesPage, resolveParseRules, listSourcesPage,
  type LogCategory, type LogSource, type LogSourceInput, type ParseRule } from '../../api'
import { useI18n } from '../useI18n'
import { useIngestCopy } from '../useIngestCopy'
import { useFormDialog } from '../useFormDialog'
import type { IngestWorkspace } from './useIngestWorkspace'

type SourceNavigation = { selectSources: () => void; cancelSetupEdit: () => void; openSetup: (id: string) => void }

export function useIngestSources(workspace: IngestWorkspace, navigation: SourceNavigation) {
  const { canWrite, actionBusy, actionError, loadErrors, loadStates, loadList, abortRead, confirmWrite } = workspace
  const { t } = useI18n()
  const copy = useIngestCopy()
  const sourcesLoading = computed(() => Boolean(loadStates.value.sources))
  const sources = ref<LogSource[]>([])
  const sourcePage = ref(1)
  const sourceSize = ref(20)
  const sourceTotal = ref(0)
  const sourceSearchDraft = ref('')
  const sourceQuery = ref('')
  const sourceRuleOptions = ref<ParseRule[]>([])
  const sourceRuleTotal = ref(0)
  const selectedSourceRules = ref<ParseRule[]>([])
  const sourceRuleSelectionVersion = ref(0)
  const sourceRuleLoading = ref(false)
  let sourceRuleSearchTimer: number | null = null
  let sourceRuleSearchGeneration = 0
  const logCategories = ref<LogCategory[]>([])
  const emptySource = () => ({
    name: '', type: 'FILE', format: 'AUTO', path: '', address: '', topic: '', env: 'local',
    readFrom: 'beginning', multiline: '', description: '', protocol: 'tcp', charset: 'utf-8',
    timeField: '', timezone: 'Asia/Shanghai', tags: '', frequency: 1 as number | null,
    ignoreOlderSeconds: null as number | null, categoryId: '', groupId: '', sinkTargetId: '',
    parseRuleIds: [] as string[], enabled: true,
  })
  const newSource = ref(emptySource())
  const sourceErrors = ref<Record<string, string>>({})
  const sourceError = ref('')
  const showSourceDialog = ref(false)
  const editingSourceId = ref<string | null>(null)
  const lastSavedSource = ref<LogSource | null>(null)
  const loadSources = () => loadList('sources',
    signal => listSourcesPage(sourcePage.value, sourceSize.value, sourceQuery.value, { signal }), value => {
      sources.value = value.items
      sourceTotal.value = value.total
      const lastPage = Math.max(1, Math.ceil(value.total / sourceSize.value))
      if (sourcePage.value > lastPage) sourcePage.value = lastPage
    })
  function applySourceSearch() {
    sourceQuery.value = sourceSearchDraft.value.trim()
    if (sourcePage.value === 1) void loadSources()
    else sourcePage.value = 1
  }
  watch([sourcePage, sourceSize], () => { void loadSources() })
  async function loadSourceRuleOptions(query: string) {
    const generation = ++sourceRuleSearchGeneration
    sourceRuleLoading.value = true
    await loadList('ruleOptions', signal => listParseRulesPage(1, 50, query, { signal }), value => {
      sourceRuleOptions.value = value.items
      sourceRuleTotal.value = value.total
    })
    if (generation === sourceRuleSearchGeneration) sourceRuleLoading.value = false
  }
  function searchSourceRules(query: string) {
    if (sourceRuleSearchTimer !== null) window.clearTimeout(sourceRuleSearchTimer)
    abortRead('ruleOptions')
    sourceRuleOptions.value = []
    sourceRuleTotal.value = 0
    sourceRuleLoading.value = true
    sourceRuleSearchGeneration++
    sourceRuleSearchTimer = window.setTimeout(() => {
      sourceRuleSearchTimer = null
      void loadSourceRuleOptions(query)
    }, 250)
  }
  function loadSelectedSourceRules(ids: string[]) {
    selectedSourceRules.value = []
    if (!ids.length) return Promise.resolve()
    return loadList('selectedRules', signal => resolveParseRules(ids, { signal }), value => {
      selectedSourceRules.value = value
      sourceRuleSelectionVersion.value++
    })
  }
  function sourceRuleOptionLabel(id: string) {
    return sourceRuleOptions.value.find(rule => rule.id === id)?.name
      ?? selectedSourceRules.value.find(rule => rule.id === id)?.name ?? id
  }
  function onSourceRuleChange(ids: string[]) {
    const known = [...sourceRuleOptions.value, ...selectedSourceRules.value]
    selectedSourceRules.value = ids.flatMap(id => {
      const rule = known.find(item => item.id === id)
      return rule ? [rule] : []
    })
  }
  const loadCategories = () => loadList('categories', signal => listCategories({ signal }), value => { logCategories.value = value })


  function prepareSourceRuleSelector(ids: string[]) {
    if (sourceRuleSearchTimer !== null) { window.clearTimeout(sourceRuleSearchTimer); sourceRuleSearchTimer = null }
    abortRead('ruleOptions')
    abortRead('selectedRules')
    sourceRuleOptions.value = []
    selectedSourceRules.value = []
    sourceRuleSelectionVersion.value++
    sourceRuleTotal.value = 0
    loadErrors.value = { ...loadErrors.value, ruleOptions: '', selectedRules: '' }
    void loadSourceRuleOptions('')
    void loadSelectedSourceRules(ids)
  }
  function openCreateSource() {
    if (!canWrite.value || actionBusy.value || workspace.disposed) return
    navigation.selectSources()
    editingSourceId.value = null
    newSource.value = emptySource()
    sourceErrors.value = {}
    sourceError.value = ''
    actionError.value = ''
    showSourceDialog.value = true
    prepareSourceRuleSelector([])
  }
  function openEditSource(source: LogSource) {
    if (!canWrite.value || actionBusy.value || workspace.disposed) return
    navigation.cancelSetupEdit()
    editingSourceId.value = source.id
    newSource.value = {
      name: source.name, type: source.type || 'FILE', format: source.format || 'AUTO',
      path: source.path || '', address: source.address || '', topic: source.topic || '', env: source.env || 'local',
      readFrom: source.readFrom || 'beginning', multiline: source.multiline || '', protocol: source.protocol || 'tcp',
      description: source.description || '', charset: source.charset || 'utf-8', timeField: source.timeField || '',
      timezone: source.timezone || 'Asia/Shanghai', tags: (source.tags || []).join(','),
      frequency: source.frequency ?? 1, ignoreOlderSeconds: source.ignoreOlderSeconds ?? null,
      categoryId: source.categoryId || '', groupId: source.groupId || '',
      sinkTargetId: source.sinkTargetId || '',
      parseRuleIds: [...(source.parseRuleIds || [])], enabled: source.enabled,
    }
    sourceErrors.value = {}
    sourceError.value = ''
    actionError.value = ''
    showSourceDialog.value = true
    prepareSourceRuleSelector(source.parseRuleIds || [])
  }

  /** Required fields only; empty optional targets are submitted as empty. */
  function validateSource(asDraft = false): boolean {
    sourceErrors.value = {}
    if (!newSource.value.name.trim()) sourceErrors.value.name = t('forms.fieldRequired', { field: t('ingest.sourceName') })
    if (!asDraft && newSource.value.enabled) {
      if (newSource.value.type === 'FILE' && !newSource.value.path.trim()) sourceErrors.value.path = copy.value.requiredConnection
      if (['SOCKET', 'SYSLOG', 'KAFKA'].includes(newSource.value.type) && !newSource.value.address.trim()) sourceErrors.value.address = copy.value.requiredConnection
      if (newSource.value.type === 'KAFKA' && !newSource.value.topic.trim()) sourceErrors.value.topic = copy.value.requiredConnection
    }
    return !Object.keys(sourceErrors.value).length
  }
  async function saveSource(asDraft = false) {
    if (!canWrite.value || actionBusy.value || workspace.disposed || !validateSource(asDraft)) return
    await workspace.run(async () => {
      const source: LogSourceInput = {
        name: newSource.value.name.trim(), type: newSource.value.type, format: newSource.value.format,
        path: newSource.value.path.trim() || null, address: newSource.value.address.trim() || null,
        topic: newSource.value.topic.trim() || null, env: newSource.value.env.trim() || null,
        enabled: asDraft ? false : newSource.value.enabled, readFrom: newSource.value.readFrom || null,
        multiline: newSource.value.multiline.trim() || null, description: newSource.value.description.trim() || null,
        protocol: newSource.value.protocol || null, charset: newSource.value.charset || null,
        timeField: newSource.value.timeField.trim() || null, timezone: newSource.value.timezone || null,
        tags: newSource.value.tags.split(/[,\uFF0C\s]+/).filter(Boolean),
        frequency: newSource.value.frequency, ignoreOlderSeconds: newSource.value.ignoreOlderSeconds,
        groupId: newSource.value.groupId || null,
        categoryId: newSource.value.categoryId || null,
        sinkTargetId: newSource.value.sinkTargetId || null,
        parseRuleIds: newSource.value.parseRuleIds,
      }
      const saved = editingSourceId.value ? (await updateSource(editingSourceId.value, source)).source : await createSource(source)
      if (workspace.disposed) return
      lastSavedSource.value = saved
      sourceSearchDraft.value = source.name
      sourceQuery.value = source.name
      sourcePage.value = 1
      editingSourceId.value = null
      showSourceDialog.value = false
      navigation.openSetup(saved.id)
      await loadSources()
    }, sourceError)
  }
  async function removeSource(id: string) {
    if (!await confirmWrite('ingest.deleteSourceConfirm')) return
    return workspace.run(async () => {
      await deleteSource(id)
      await loadSources()
    })
  }
  const showSourceDialogGuard = useFormDialog(showSourceDialog, () => newSource.value, () => actionBusy.value)
  watch(showSourceDialog, open => {
    if (open) return
    if (sourceRuleSearchTimer !== null) { window.clearTimeout(sourceRuleSearchTimer); sourceRuleSearchTimer = null }
    sourceRuleSearchGeneration++
    sourceRuleLoading.value = false
    abortRead('ruleOptions')
    abortRead('selectedRules')
  }, { flush: 'sync' })
  onUnmounted(() => { if (sourceRuleSearchTimer !== null) window.clearTimeout(sourceRuleSearchTimer) })

  return {
    sources, sourcesLoading, sourcePage, sourceSize, sourceTotal, sourceSearchDraft, loadSources,
    applySourceSearch, logCategories, loadCategories, sourceRuleOptions, sourceRuleTotal,
    sourceRuleSelectionVersion, sourceRuleLoading, searchSourceRules, sourceRuleOptionLabel, onSourceRuleChange,
    newSource, sourceErrors, sourceError, editingSourceId, lastSavedSource, showSourceDialog,
    showSourceDialogGuard, openCreateSource, openEditSource, saveSource, removeSource,
  }
}
