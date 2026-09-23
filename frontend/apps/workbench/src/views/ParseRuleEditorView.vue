<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import ElButton from 'element-plus/es/components/button/index.mjs'
import { ElForm, ElFormItem } from 'element-plus/es/components/form/index.mjs'
import ElInput from 'element-plus/es/components/input/index.mjs'
import { ElOption, ElSelect } from 'element-plus/es/components/select/index.mjs'
import ElSwitch from 'element-plus/es/components/switch/index.mjs'
import 'element-plus/es/components/button/style/css.mjs'
import 'element-plus/es/components/form/style/css.mjs'
import 'element-plus/es/components/input/style/css.mjs'
import 'element-plus/es/components/select/style/css.mjs'
import 'element-plus/es/components/switch/style/css.mjs'
import PageHeader from '../components/PageHeader.vue'
import ActionFeedback from '../components/ActionFeedback.vue'
import { useI18n } from '../composables/useI18n'
import { useMutation } from '../composables/useMutation'
import { useUnsavedChanges } from '../composables/useUnsavedChanges'
import { useWriteAccess } from '../composables/useWriteAccess'
import { createParseRule, updateParseRule, previewParseDraft, getParseRule, listSourcesPage, getSource, listFields, type ParseRule, type LogSource, type FieldDef } from '../api'

const { t } = useI18n()
const canWrite = useWriteAccess()
const route = useRoute()
const router = useRouter()
const sources = ref<LogSource[]>([])
const sourceTotal = ref(0)
const selectedSource = ref<LogSource | null>(null)
const sourceLoading = ref(false)
const sourceError = ref('')
const selectedSourceError = ref('')
let sourceController: AbortController | null = null
let editorController: AbortController | null = null
let sourceTimer: number | null = null
let disposed = false
const fields = ref<FieldDef[]>([])
function emptyForm(): Partial<ParseRule> { return { name: '', format: 'REGEX', pattern: '', sourceId: null, enabled: false, order: 10, mapping: [], setFields: [], filters: [] } }
const form = ref<Partial<ParseRule>>(emptyForm())
let loadGeneration = 0
let loadedId: string | null = null
const filtersText = ref('[]')
const sample = ref('')
const preview = ref<Awaited<ReturnType<typeof previewParseDraft>> | null>(null)
const loading = ref(true)
const loadError = ref('')
const mutation = useMutation()
const { busy, error } = mutation
const changes = useUnsavedChanges(() => ({ form: form.value, filters: filtersText.value }), () => !loading.value)
const previewStale = ref(false)
watch([form, filtersText, sample], () => { if (preview.value) previewStale.value = true }, { deep: true, flush: 'sync' })

async function fetchSources(query: string) {
  if (disposed) return
  sourceController?.abort()
  const controller = new AbortController()
  sourceController = controller
  sourceLoading.value = true
  sourceError.value = ''
  try {
    const page = await listSourcesPage(1, 50, query, { signal: controller.signal })
    if (disposed || sourceController !== controller || controller.signal.aborted) return
    sources.value = page.items
    sourceTotal.value = page.total
  } catch (failure) {
    if (!disposed && sourceController === controller && !controller.signal.aborted) sourceError.value = String(failure)
  } finally {
    if (sourceController === controller) { sourceController = null; sourceLoading.value = false }
  }
}
function searchSources(query: string) {
  if (sourceTimer !== null) window.clearTimeout(sourceTimer)
  sourceController?.abort()
  sourceController = null
  sources.value = []
  sourceTotal.value = 0
  sourceLoading.value = true
  sourceError.value = ''
  sourceTimer = window.setTimeout(() => { sourceTimer = null; void fetchSources(query) }, 250)
}
function onSourceChange(id: string | null) {
  selectedSource.value = sources.value.find(source => source.id === id)
    ?? (selectedSource.value?.id === id ? selectedSource.value : null)
  selectedSourceError.value = ''
}

function payload(): Partial<ParseRule> {
  if (!form.value.name?.trim()) throw new Error(t('forms.fieldRequired', { field: t('common.name') }))
  const filters: unknown = JSON.parse(filtersText.value)
  if (!Array.isArray(filters)) throw new Error(t('ingest.filtersMustBeArray'))
  return { ...form.value, filters, mapping: form.value.mapping ?? [], setFields: form.value.setFields ?? [] }
}
async function save() {
  if (!canWrite.value || loading.value || loadError.value) return
  const generation = loadGeneration
  await mutation.run(async () => {
    const id = loadedId || ''
    const saved = id ? await updateParseRule(id, payload()) : await createParseRule(payload())
    if (generation !== loadGeneration) return
    form.value = saved
    filtersText.value = JSON.stringify(saved.filters ?? [], null, 2)
    loadedId = saved.id
    changes.markSaved()
    await router.replace({ name: 'parser-edit', params: { parserId: saved.id } })
  })
}
async function test() {
  const generation = loadGeneration
  await mutation.run(async () => {
    preview.value = null
    const result = await previewParseDraft(payload(), sample.value)
    if (generation !== loadGeneration) return
    preview.value = result
    previewStale.value = false
  })
}
function addMapping(fixed = false) {
  if (!canWrite.value) return
  const key = fixed ? 'setFields' : 'mapping'
  form.value[key]!.push({ group: fixed ? 'fixed' : '', field: '', value: '' })
}
async function loadEditor() {
  const id = String(route.params.parserId || '')
  const generation = ++loadGeneration
  editorController?.abort()
  const controller = new AbortController()
  editorController = controller
  loading.value = true
  loadError.value = ''
  loadedId = null
  form.value = emptyForm()
  filtersText.value = '[]'
  preview.value = null
  previewStale.value = false
  sources.value = []
  selectedSource.value = null
  selectedSourceError.value = ''
  sourceTotal.value = 0
  if (sourceTimer !== null) { window.clearTimeout(sourceTimer); sourceTimer = null }
  void fetchSources('')
  try {
    const [rule, availableFields] = await Promise.all([
      id ? getParseRule(id, { signal: controller.signal }) : Promise.resolve(null),
      listFields({ signal: controller.signal }),
    ])
    if (generation !== loadGeneration || controller.signal.aborted) return
    fields.value = availableFields
    if (id) {
      if (!rule) throw new Error('Parse rule not found')
      if (rule.sourceId) {
        try {
          const result = await getSource(rule.sourceId, { signal: controller.signal, timeoutMs: 3_000 })
          if (generation !== loadGeneration || controller.signal.aborted) return
          selectedSource.value = result.source
        } catch (failure) {
          if (generation === loadGeneration && !controller.signal.aborted) selectedSourceError.value = String(failure)
        }
      }
      if (generation !== loadGeneration || controller.signal.aborted) return
      form.value = structuredClone(rule)
      filtersText.value = JSON.stringify(rule.filters ?? [], null, 2)
    }
    loadedId = id
  } catch (failure) { if (generation === loadGeneration && !controller.signal.aborted) loadError.value = String(failure) }
  finally { if (generation === loadGeneration) { loading.value = false; changes.markSaved() } }
}
watch(() => String(route.params.parserId || ''), id => {
  if (id !== loadedId) void loadEditor()
}, { immediate: true })
onUnmounted(() => {
  disposed = true
  loadGeneration++
  editorController?.abort()
  sourceController?.abort()
  if (sourceTimer !== null) window.clearTimeout(sourceTimer)
})
</script>
<template>
  <div class="page-pad editor-page">
    <PageHeader :title="form.name || t('ingest.addParseRule')">
      <template #actions><el-button @click="router.push({ name: 'ingest', query: { tab: 'rules' } })">{{ t('forms.back') }}</el-button></template>
    </PageHeader>
    <ActionFeedback :error="loadError || error" />
    <div v-if="!canWrite" class="page-readonly-hint">{{ t('ingest.readOnly') }}</div>
    <el-button v-if="loadError" :loading="loading" @click="loadEditor">{{ t('common.refresh') }}</el-button>
    <div v-if="loading">{{ t('common.loading') }}</div>
    <div v-else-if="!loadError" class="parser-workspace">
      <el-form label-position="top" :disabled="busy || !canWrite">
        <el-form-item :label="t('common.name')" required><el-input v-model="form.name" maxlength="128" /></el-form-item>
        <el-form-item :label="t('ingest.parseFormat')"><el-select v-model="form.format"><el-option v-for="format in ['REGEX','JSON','KV','SYSLOG','CEF','LEEF','AUTO']" :key="format" :value="format" :label="format" /></el-select></el-form-item>
        <el-form-item :label="t('common.source')"><el-select v-model="form.sourceId" filterable remote clearable :remote-method="searchSources" :loading="sourceLoading" @change="onSourceChange"><el-option v-for="source in sources" :key="source.id" :value="source.id" :label="source.name" /><el-option v-if="form.sourceId && !sources.some(source => source.id === form.sourceId)" :value="form.sourceId" :label="selectedSource?.id === form.sourceId ? selectedSource.name : form.sourceId" /><el-option v-if="sourceTotal > 50" value="__more_sources__" :label="t('ingest.sourceSearchMore', { count: sourceTotal })" disabled /></el-select><small v-if="sourceTotal > 50" class="source-search-hint">{{ t('ingest.sourceSearchMore', { count: sourceTotal }) }}</small><ActionFeedback :error="sourceError || selectedSourceError" /></el-form-item>
        <el-form-item v-if="form.format === 'REGEX'" :label="t('ingest.patternDescription')"><el-input v-model="form.pattern" type="textarea" :rows="4" spellcheck="false" /></el-form-item>
        <section v-for="key in (['mapping', 'setFields'] as const)" :key="key" class="editor-section">
          <div class="section-toolbar"><b>{{ t(key === 'mapping' ? 'forms.fields' : 'forms.fixedFields') }}</b><el-button v-if="canWrite" size="small" @click="addMapping(key === 'setFields')">{{ t('common.add') }}</el-button></div>
          <div v-for="(mapping, index) in form[key]" :key="index" class="mapping-row">
            <el-input v-if="key === 'mapping'" v-model="mapping.group" :placeholder="t('meta.fieldName')" />
            <el-select v-model="mapping.field" filterable allow-create default-first-option :placeholder="t('forms.fields')"><el-option v-for="field in fields" :key="field.id" :value="field.fieldName" :label="field.fieldName" /></el-select>
            <el-input v-if="key === 'setFields'" v-model="mapping.value" :placeholder="t('ueba.watchlistValues')" />
            <el-button v-if="canWrite" link type="danger" @click="form[key]!.splice(index, 1)">{{ t('common.delete') }}</el-button>
          </div>
        </section>
        <details class="editor-section"><summary>{{ t('forms.advanced') }}</summary><el-form-item :label="t('forms.filter')"><el-input v-model="filtersText" type="textarea" :rows="8" spellcheck="false" /></el-form-item><el-form-item label="Order"><el-input v-model.number="form.order" type="number" min="0" max="100000" /></el-form-item></details>
        <el-form-item :label="t('common.enabled')"><el-switch v-model="form.enabled" /></el-form-item>
      </el-form>
      <aside class="parser-preview">
        <h3>{{ t('forms.sample') }}</h3><el-input v-model="sample" type="textarea" :rows="8" />
        <el-button style="margin-top:12px" :loading="busy" :disabled="!sample.trim()" @click="test">{{ t('forms.test') }}</el-button>
        <h3>{{ t('forms.preview') }} <small v-if="preview && previewStale">· {{ t('forms.stalePreview') }}</small></h3>
        <ActionFeedback :error="preview?.error" />
        <p v-if="preview">{{ preview.matched ? t('common.success') : t('threat.noMatch') }}</p>
        <dl v-if="preview" class="preview-fields"><template v-for="(value, key) in preview.fields" :key="key"><dt>{{ key }}</dt><dd>{{ value }}</dd></template></dl>
      </aside>
    </div>
    <footer v-if="!loadError && canWrite" class="editor-footer"><el-button type="primary" :loading="busy" :disabled="loading" @click="save">{{ t('common.save') }}</el-button></footer>
  </div>
</template>
<style scoped>
.parser-workspace { display:grid; grid-template-columns:minmax(0,1fr) minmax(0,1fr); gap:32px; }
.parser-preview { border-left:1px solid var(--ns-border); padding-left:24px; }
.mapping-row { display:flex; gap:8px; margin:8px 0; }
.preview-fields { display:grid; grid-template-columns:minmax(120px,1fr) 2fr; gap:12px; overflow-wrap:anywhere; }
.preview-fields dd { margin:0; }
.source-search-hint { display:block; color:var(--ns-text-3); margin-top:4px; }
@media(max-width:1000px) { .parser-workspace { grid-template-columns:1fr; }.parser-preview { padding:0;border:0; } }
</style>
