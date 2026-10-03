<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { getSourceSetup, previewSource, renderSourceConfig, splSearch, type SourcePreview, type SourceSetup, type SearchEvent } from '../api'
import { useIngestCopy } from '../composables/useIngestCopy'
import { useWriteAccess } from '../composables/useWriteAccess'

const props = defineProps<{ sourceId: string; initialSample?: string }>()
const emit = defineEmits<{ edit: [id: string] }>()
const router = useRouter()
const copy = useIngestCopy()
const canWrite = useWriteAccess()
const setup = ref<SourceSetup | null>(null)
const loading = ref(false)
const error = ref('')
const sample = ref('')
const preview = ref<SourcePreview | null>(null)
const previewBusy = ref(false)
const previewError = ref('')
const config = ref('')
const configBusy = ref(false)
const configError = ref('')
const copyStatus = ref('')
const event = ref<SearchEvent | null>(null)
const eventBusy = ref(false)
const eventStatus = ref('')
let generation = 0
let sampleRevision = 0
let controller: AbortController | null = null
let previewController: AbortController | null = null
let disposed = false
const sourceQuery = computed(() => `source_id=${JSON.stringify(props.sourceId)}`)
const outputExternal = computed(() => setup.value?.output?.type.toUpperCase() === 'HTTP')

async function load() {
  const current = ++generation
  controller?.abort()
  previewController?.abort()
  const request = new AbortController()
  controller = request
  loading.value = canWrite.value; error.value = ''; setup.value = null
  preview.value = null; previewError.value = ''; previewBusy.value = false
  config.value = ''; configError.value = ''; configBusy.value = false; copyStatus.value = ''
  event.value = null; eventStatus.value = ''; eventBusy.value = false
  if (!canWrite.value) return
  try {
    const result = await getSourceSetup(props.sourceId, { signal: request.signal })
    if (!disposed && current === generation) setup.value = result
  } catch (failure) {
    if (!disposed && current === generation && !request.signal.aborted) error.value = String(failure)
  } finally {
    if (!disposed && current === generation) loading.value = false
  }
}
watch(() => [props.sourceId, props.initialSample], () => { sample.value = props.initialSample || ''; void load() }, { immediate: true })
watch(canWrite, () => { void load() }, { flush: 'sync' })
watch(sample, () => {
  sampleRevision++; previewController?.abort(); previewController = null
  preview.value = null; previewError.value = ''; previewBusy.value = false
}, { flush: 'sync' })
async function runPreview() {
  if (!canWrite.value || !sample.value.trim() || previewBusy.value || !setup.value) return
  const current = generation
  const revision = sampleRevision
  const request = new AbortController()
  previewController = request
  previewBusy.value = true; previewError.value = ''; preview.value = null
  try {
    const result = await previewSource(props.sourceId, sample.value, { signal: request.signal })
    if (!disposed && current === generation && revision === sampleRevision && !request.signal.aborted) preview.value = result
  } catch (failure) {
    if (!disposed && current === generation && revision === sampleRevision && !request.signal.aborted) previewError.value = String(failure)
  } finally {
    if (previewController === request) { previewController = null; previewBusy.value = false }
  }
}
async function render() {
  if (!canWrite.value || configBusy.value || !setup.value?.nativeVector || !setup.value.source.enabled) return
  const current = generation
  configBusy.value = true; configError.value = ''; config.value = ''; copyStatus.value = ''
  try {
    const result = await renderSourceConfig(props.sourceId, { signal: controller?.signal })
    if (!disposed && current === generation) config.value = result
  } catch (failure) {
    if (!disposed && current === generation) configError.value = String(failure)
  } finally { if (!disposed && current === generation) configBusy.value = false }
}
async function copyConfig() {
  const current = generation
  try {
    await navigator.clipboard.writeText(config.value)
    if (!disposed && current === generation) copyStatus.value = copy.value.copied
  } catch (failure) { if (!disposed && current === generation) configError.value = String(failure) }
}
async function verifyEvent() {
  if (eventBusy.value || !setup.value) return
  const current = generation
  const id = props.sourceId
  const from = new Date(Date.now() - 86400000).toISOString()
  const to = new Date().toISOString()
  eventBusy.value = true; eventStatus.value = ''; event.value = null
  try {
    const result = await splSearch(`(${sourceQuery.value}) AND timestamp>=${from} AND timestamp<=${to}`, { limit: 20, signal: controller?.signal })
    if (disposed || current !== generation) return
    if (result.degraded || result.source !== 'opensearch') { eventStatus.value = copy.value.degraded; return }
    event.value = result.events.find(row => row.fields?.source_id === id) || null
    eventStatus.value = event.value ? copy.value.verified : copy.value.notObserved
  } catch (failure) { if (!disposed && current === generation) eventStatus.value = String(failure) }
  finally { if (!disposed && current === generation) eventBusy.value = false }
}
function openSearch() {
  void router.push({ name: 'search', query: { q: event.value ? `eventId=${JSON.stringify(event.value.eventId)}` : sourceQuery.value, range: event.value ? 'all' : '24h' } })
}
onUnmounted(() => { disposed = true; generation++; controller?.abort(); previewController?.abort() })
</script>

<template>
  <div class="ingest-setup" :aria-busy="loading">
    <p v-if="loading" role="status">{{ copy.checking }}</p>
    <p v-if="error" role="alert" class="setup-error">{{ error }}</p>
    <button type="button" :disabled="loading" @click="load">{{ copy.reload }}</button>
    <template v-if="setup">
      <div class="setup-state" role="status">
        <strong>{{ setup.source.name }}</strong>
        <span>{{ copy.saved }} · {{ setup.source.enabled ? copy.enabled : copy.disabled }}</span>
        <span>{{ copy.appliedUnknown }}</span>
        <small>{{ copy.version }}: {{ setup.configurationVersion }}</small>
      </div>
      <section aria-labelledby="setup-connect">
        <h3 id="setup-connect">{{ copy.connect }}</h3>
        <p>{{ setup.source.type }} · {{ setup.source.path || setup.source.address || '—' }}<span v-if="setup.source.topic"> · {{ setup.source.topic }}</span></p>
        <p v-if="setup.source.type === 'FILE'">{{ copy.localPath }}</p>
        <template v-if="!setup.nativeVector"><p>{{ copy.external }}</p><p class="setup-warning">{{ copy.externalBlocked }}</p></template>
        <p>{{ copy.collectorIdentity }}: <code>{{ setup.source.id }}</code> / <code>{{ setup.collectorTag }}</code></p>
        <ul v-if="setup.problems.length" class="setup-warning"><li v-for="problem in setup.problems" :key="problem">{{ problem }}</li></ul>
        <button v-if="canWrite" type="button" @click="emit('edit', sourceId)">{{ copy.edit }}</button>
      </section>
      <section aria-labelledby="setup-preview">
        <h3 id="setup-preview">{{ copy.preview }}</h3>
        <p>{{ copy.previewHint }}</p>
        <label for="source-preview-sample">{{ copy.sample }}</label>
        <textarea id="source-preview-sample" v-model="sample" rows="5" maxlength="65536" />
        <div class="setup-actions"><button type="button" @click="sample = 'host=example-host source=example message=synthetic-preview'">{{ copy.example }}</button><button v-if="canWrite" type="button" :disabled="!sample.trim() || previewBusy" @click="runPreview">{{ previewBusy ? copy.checking : copy.runPreview }}</button></div>
        <p v-if="previewError" role="alert" class="setup-error">{{ previewError }}</p>
        <div v-if="preview" role="status"><p>{{ preview.ok ? copy.previewSuccess : preview.error }}</p><p>{{ copy.previewVersion }}: {{ preview.parserVersion }}</p><pre v-if="preview.ok">{{ JSON.stringify({ fields: preview.fields, ecs: preview.ecs }, null, 2) }}</pre></div>
        <h4>{{ copy.pipeline }}</h4><p v-if="setup.pipelineMode === 'BUILTIN_THEN_SPARSE_FALLBACK'">{{ copy.noBindings }}</p>
        <ol v-if="setup.pipeline.length"><li v-for="rule in setup.pipeline" :key="rule.id">{{ rule.name }} <span v-if="!rule.exists || !rule.enabled || !rule.scopeMatches" class="setup-warning">{{ copy.missingRule }}</span></li></ol>
        <p v-else>{{ copy.noBindings }}</p>
        <p>{{ copy.pipelineHint }}</p>
        <button v-if="canWrite" type="button" @click="router.push({ name: 'parser-new', query: { sourceId } })">{{ copy.parserNew }}</button>
      </section>
      <section aria-labelledby="setup-destination">
        <h3 id="setup-destination">{{ copy.destination }}</h3>
        <template v-if="setup.output"><p>{{ setup.output.name }} · {{ setup.output.type }} · {{ setup.output.enabled ? copy.outputEnabled : copy.outputDisabled }}</p><code>{{ setup.output.uri }}</code><p v-if="outputExternal" class="setup-warning">{{ copy.outputWarning }}</p></template>
        <p v-else class="setup-warning">{{ copy.noOutput }}</p>
        <p>{{ copy.outputHint }}</p>
        <button type="button" @click="router.push({ name: 'ingest', query: { tab: 'outputs' } })">{{ copy.manageOutputs }}</button>
      </section>
      <section aria-labelledby="setup-apply">
        <h3 id="setup-apply">{{ copy.apply }}</h3>
        <p>{{ setup.nativeVector ? copy.manualApply : copy.unsupportedRender }}</p>
        <button v-if="canWrite && setup.nativeVector" type="button" :disabled="configBusy || !setup.source.enabled" @click="render">{{ copy.render }}</button>
        <p v-if="configError" role="alert" class="setup-error">{{ configError }}</p>
        <template v-if="config"><p role="status">{{ copy.rendered }}</p><pre>{{ config }}</pre><button type="button" @click="copyConfig">{{ copy.copy }}</button><span role="status">{{ copyStatus }}</span></template>
        <details><summary>{{ copy.ack }}</summary><p>{{ copy.debugHint }}</p></details>
      </section>
      <section aria-labelledby="setup-verify">
        <h3 id="setup-verify">{{ copy.verify }}</h3>
        <p>{{ copy.verifyHint }}</p>
        <div class="setup-actions"><button type="button" :disabled="eventBusy" @click="verifyEvent">{{ eventBusy ? copy.checking : copy.checkEvent }}</button><button type="button" @click="openSearch">{{ event ? copy.event : copy.sourceSearch }}</button></div>
        <p v-if="eventStatus" role="status">{{ eventStatus }}</p>
        <template v-if="event"><p>{{ event.eventId }} · {{ event.timestamp }}</p><pre>{{ event.msg }}</pre><p>{{ copy.firstEventHint }}</p></template>
      </section>
    </template>
  </div>
</template>

<style scoped>
.ingest-setup { display: grid; gap: 18px; color: var(--el-text-color-primary); }
.setup-state { display: grid; gap: 8px; padding: 16px; border: 1px solid var(--el-border-color); border-radius: 8px; }
section { border-top: 1px solid var(--el-border-color); padding-top: 12px; }
h3 { margin: 8px 0 12px; }
p, li { line-height: 1.6; }
code, small { overflow-wrap: anywhere; }
pre { white-space: pre-wrap; overflow-wrap: anywhere; max-height: 340px; overflow: auto; padding: 12px; background: var(--el-fill-color-light); border-radius: 6px; }
label { display: block; margin-bottom: 6px; }
textarea { box-sizing: border-box; width: 100%; color: inherit; background: var(--el-bg-color); border: 1px solid var(--el-border-color); border-radius: 6px; padding: 10px; font: inherit; }
button { width: fit-content; padding: 8px 12px; color: var(--el-color-primary); background: var(--el-bg-color); border: 1px solid var(--el-border-color); border-radius: 6px; cursor: pointer; }
button:disabled { opacity: .55; cursor: not-allowed; }
button:focus-visible, textarea:focus-visible { outline: 2px solid var(--el-color-primary); outline-offset: 2px; }
.setup-actions { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 10px; }
.setup-error { color: var(--el-color-danger); }
.setup-warning { color: var(--el-color-warning); }
</style>
