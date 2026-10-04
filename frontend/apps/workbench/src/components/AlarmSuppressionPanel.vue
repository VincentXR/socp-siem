<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { ElButton } from 'element-plus/es/components/button/index.mjs'
import { listAlarmSuppressions, recordAlarmSuppression, releaseAlarmSuppression, type AlarmSuppression } from '../api/alarms'
import { useConfirm } from '../composables/useConfirm'
import { useI18n } from '../composables/useI18n'

const props = defineProps<{ alarmId: string; ruleId?: string; entity?: string | null; canWrite: boolean; disabled: boolean }>()
const emit = defineEmits<{ pending: [value: boolean] }>()
const { t, d } = useI18n()
const { confirmDanger } = useConfirm()
const windows = ref<AlarmSuppression[]>([])
const loading = ref(false), busy = ref(false), error = ref('')
const reason = ref(''), hours = ref(24), ruleWide = ref(false)
let generation = 0
const scope = computed(() => ruleWide.value ? t('suppression.wholeRule') : props.entity?.trim() || '')
const canSubmit = computed(() => props.canWrite && !props.disabled && !busy.value && !loading.value
  && Boolean(props.ruleId && scope.value && reason.value.trim()))
function pending(value: boolean) { busy.value = value; emit('pending', value) }
async function load(version = generation) {
  loading.value = true; error.value = ''
  try {
    const rows = await listAlarmSuppressions()
    if (version === generation) windows.value = rows.filter(row => row.ruleId === props.ruleId && (!row.entity || row.entity === props.entity))
  } catch (failure) { if (version === generation) error.value = String(failure) }
  finally { if (version === generation) loading.value = false }
}
watch(() => [props.alarmId, props.ruleId, props.entity, props.canWrite], () => {
  generation++; windows.value = []; reason.value = ''; ruleWide.value = false; hours.value = 24
  pending(false); void load()
}, { immediate: true })
onUnmounted(() => { generation++; pending(false) })
async function createWindow() {
  if (!canSubmit.value || !props.ruleId) return
  const version = generation
  const request = { alarmId: props.alarmId, ruleId: props.ruleId, entity: ruleWide.value ? undefined : props.entity?.trim(),
    ruleWide: ruleWide.value, reason: reason.value.trim(), windowSeconds: hours.value * 3600 }
  const target = scope.value
  pending(true); error.value = ''
  try {
    if (!await confirmDanger(t('suppression.confirm', { rule: request.ruleId, scope: target, hours: hours.value }))) return
    if (version !== generation || !props.canWrite) return
    await recordAlarmSuppression(request)
    if (version !== generation) return
    reason.value = ''; await load(version)
  } catch (failure) { if (version === generation) error.value = String(failure) }
  finally { if (version === generation) pending(false) }
}
async function releaseWindow(window: AlarmSuppression) {
  if (!props.canWrite || props.disabled || busy.value) return
  const version = generation
  pending(true); error.value = ''
  try {
    if (!await confirmDanger(t('suppression.releaseConfirm', { rule: window.ruleId, scope: window.entity || t('suppression.wholeRule') }))) return
    if (version !== generation || !props.canWrite) return
    await releaseAlarmSuppression(window.ruleId, window.entity)
    if (version === generation) await load(version)
  } catch (failure) { if (version === generation) error.value = String(failure) }
  finally { if (version === generation) pending(false) }
}
</script>

<template>
  <section class="suppression-panel">
    <h3>{{ t('suppression.title') }}</h3>
    <p>{{ t('suppression.hint') }}</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <el-button v-if="error" :disabled="busy || disabled" @click="load()">{{ t('common.retry') }}</el-button>
    <p v-if="loading" role="status">{{ t('common.loading') }}</p>
    <p v-else-if="!windows.length && !error">{{ t('suppression.empty') }}</p>
    <article v-for="window in windows" :key="window.id">
      <strong>{{ window.ruleId }} · {{ window.entity || t('suppression.wholeRule') }}</strong>
      <p>{{ window.reason }}</p>
      <small>{{ window.actor }} · {{ t('analystJourney.expires', { at: d(window.expiresAt) }) }}</small>
      <el-button v-if="canWrite" :disabled="busy || disabled" @click="releaseWindow(window)">{{ t('suppression.release') }}</el-button>
    </article>
    <fieldset v-if="canWrite" :disabled="busy || disabled || loading">
      <legend>{{ t('suppression.create') }}</legend>
      <label><input v-model="ruleWide" type="checkbox" /> {{ t('suppression.ruleWideConsent') }}</label>
      <p>{{ ruleId }} · {{ scope || t('suppression.entityRequired') }}</p>
      <label>{{ t('suppression.duration') }} <select v-model.number="hours" :aria-label="t('suppression.duration')"><option v-for="value in [1, 4, 24, 168, 720]" :key="value" :value="value">{{ t('suppression.hours', { hours: value }) }}</option></select></label>
      <label>{{ t('suppression.reason') }} <textarea v-model="reason" rows="3" maxlength="2000" /></label>
      <el-button type="warning" :loading="busy" :disabled="!canSubmit" @click="createWindow">{{ t('suppression.create') }}</el-button>
    </fieldset>
  </section>
</template>

<style scoped>
.suppression-panel { margin-top: 20px; border-top: 1px solid var(--ns-border); padding-top: 12px; }
.suppression-panel p, .suppression-panel small { color: var(--ns-text-2); overflow-wrap: anywhere; }
.suppression-panel article { padding: 10px 0; border-bottom: 1px solid var(--ns-border); }
.suppression-panel fieldset { margin-top: 12px; border: 1px solid var(--ns-border); border-radius: 6px; padding: 12px; }
.suppression-panel label { display: block; margin: 10px 0; }
.suppression-panel select, .suppression-panel textarea { border: 1px solid var(--ns-border); border-radius: 4px; padding: 6px 8px; background: var(--ns-bg); color: var(--ns-text); font: inherit; }
.suppression-panel textarea { display: block; width: 100%; box-sizing: border-box; background: var(--ns-bg); color: var(--ns-text); }
</style>
