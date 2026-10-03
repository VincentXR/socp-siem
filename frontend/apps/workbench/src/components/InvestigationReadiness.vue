<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from '../composables/useI18n'
import { useRouter } from 'vue-router'
import { gasEngineStats, listRulePage, type GasStats } from '../api'
import { useLatestRequest } from '../composables/useLatestRequest'
const props = defineProps<{ canInspect: boolean }>()
const router = useRouter()
const { t } = useI18n()
const requests = useLatestRequest()
const stats = ref<GasStats | null>(null)
const activeRules = ref<number | null>(null)
const ruleCount = ref<number | null>(null)
const error = ref('')
const loading = ref(false)
const guidance = computed(() => !props.canInspect ? t('analystJourney.readinessPermission')
  : ruleCount.value === 0 ? t('analystJourney.noRules')
    : activeRules.value === 0 ? t('analystJourney.noActiveRules')
      : stats.value?.eventCount === 0 ? t('analystJourney.zeroEngineEvents')
        : stats.value ? t('analystJourney.noMatchingAlarms')
          : t('analystJourney.readinessUnknown'))
async function refresh(): Promise<void> {
  if (!props.canInspect) return
  const request = requests.start()
  loading.value = true; error.value = ''
  try {
    const [engine, all, active] = await Promise.all([gasEngineStats({ signal: request.signal }), listRulePage({ page: 1, size: 1 }, { signal: request.signal }), listRulePage({ page: 1, size: 1, status: 'ACTIVE' }, { signal: request.signal })])
    if (!request.isCurrent()) return
    stats.value = engine; ruleCount.value = all.total; activeRules.value = active.total
  } catch (failure) { if (request.isCurrent()) { stats.value = null; activeRules.value = ruleCount.value = null; error.value = failure instanceof Error ? failure.message : String(failure) } }
  finally { if (request.isCurrent()) loading.value = false }
}
watch(() => props.canInspect, allowed => { if (allowed) void refresh(); else requests.cancel() }, { immediate: true })
</script>
<template>
  <section class="readiness-check" :aria-label="t('analystJourney.readinessLabel')">
    <p>{{ guidance }}</p>
    <p v-if="stats">{{ t('analystJourney.readinessCounters', { active: activeRules ?? 0, all: ruleCount ?? 0, events: stats.eventCount, alerts: stats.alertCount }) }} </p>
    <p v-if="error" role="alert">{{ t('analystJourney.readinessError', { error }) }} </p>
    <button v-if="canInspect" type="button" :disabled="loading" @click="refresh">{{ t('analystJourney.refreshReadiness') }}</button>
    <button v-if="canInspect" type="button" @click="router.push({ name: 'detect' })">{{ t('analystJourney.inspectRules') }}</button>
    <button v-if="canInspect" type="button" @click="router.push({ name: 'ingest' })">{{ t('analystJourney.inspectSources') }}</button>
    <button type="button" @click="router.push({ name: 'search' })">{{ t('analystJourney.inspectEvents') }}</button>
  </section>
</template>
<style scoped>
.readiness-check { padding: 12px; color: var(--ns-text-2); font-size: 12px; line-height: 1.6; }
.readiness-check button { margin: 4px; padding: 5px 8px; border: 1px solid var(--ns-border); color: var(--ns-text); background: var(--ns-surface); border-radius: 4px; cursor: pointer; }
</style>
