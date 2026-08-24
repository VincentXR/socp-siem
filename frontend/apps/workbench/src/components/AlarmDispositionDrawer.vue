<script setup lang="ts">
import 'element-plus/es/components/alert/style/css.mjs'
import 'element-plus/es/components/button/style/css.mjs'
import 'element-plus/es/components/card/style/css.mjs'
import 'element-plus/es/components/descriptions/style/css.mjs'
import 'element-plus/es/components/divider/style/css.mjs'
import 'element-plus/es/components/drawer/style/css.mjs'
import 'element-plus/es/components/empty/style/css.mjs'
import 'element-plus/es/components/input/style/css.mjs'
import 'element-plus/es/components/select/style/css.mjs'
import 'element-plus/es/components/tag/style/css.mjs'
import ElAlert from 'element-plus/es/components/alert/index.mjs'
import ElButton from 'element-plus/es/components/button/index.mjs'
import ElCard from 'element-plus/es/components/card/index.mjs'
import { ElDescriptions, ElDescriptionsItem } from 'element-plus/es/components/descriptions/index.mjs'
import ElDivider from 'element-plus/es/components/divider/index.mjs'
import ElDrawer from 'element-plus/es/components/drawer/index.mjs'
import ElEmpty from 'element-plus/es/components/empty/index.mjs'
import ElInput from 'element-plus/es/components/input/index.mjs'
import { ElOption, ElSelect } from 'element-plus/es/components/select/index.mjs'
import ElTag from 'element-plus/es/components/tag/index.mjs'
import { computed, ref, watch } from 'vue'
import SevBadge from './SevBadge.vue'
import type { Alarm, AlarmEvidenceResponse, CaseInfo, Disposition, Ioc } from '../api'
import { addAlarmNote, assignAlarm, getAlarmEvidence, getDisposition, setDispositionStatus } from '../api/alarms'
import { listCases as loadCases } from '../api/incidents'
import { useI18n } from '../composables/useI18n'

const props = defineProps<{
  modelValue: boolean
  alarm: Alarm | null
  goCase: () => void
  goSearch: () => void
}>()

const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()
const drawerVisible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})

const { t, locale } = useI18n()

const DISP_STATUSES = ['OPEN', 'INVESTIGATING', 'RESOLVED', 'CLOSED']
const disposition = ref<Disposition | null>(null)
const evidence = ref<AlarmEvidenceResponse | null>(null)
const evidenceError = ref('')
const relatedCase = ref<CaseInfo | null>(null)
const newStatus = ref('OPEN')
const newAssignee = ref('')
const newNote = ref('')
let loadToken = 0

const tiHits = computed<Ioc[]>(() => {
  try {
    return props.alarm?.tiHits ? JSON.parse(props.alarm.tiHits) as Ioc[] : []
  } catch {
    return []
  }
})

async function loadDetails(alarm: Alarm) {
  const token = ++loadToken
  disposition.value = null
  evidence.value = null
  evidenceError.value = ''
  newStatus.value = alarm.status || 'OPEN'
  newAssignee.value = ''
  newNote.value = ''
  const [disp, ev, cases] = await Promise.allSettled([getDisposition(alarm.id), getAlarmEvidence(alarm.id), loadCases()])
  if (token !== loadToken) return
  if (disp.status === 'fulfilled') disposition.value = disp.value
  if (ev.status === 'fulfilled') evidence.value = ev.value
  else evidenceError.value = locale.value === 'zh-CN' ? '关联原始日志加载失败，请稍后重试' : 'Failed to load associated raw events, please try again later'
  if (cases.status === 'fulfilled') relatedCase.value = cases.value.find(item => item.alarmIds.includes(alarm.id)) ?? null
  else relatedCase.value = null
}

watch(() => [props.modelValue, props.alarm?.id] as const, ([visible]) => {
  if (visible && props.alarm) void loadDetails(props.alarm)
}, { immediate: true })

async function changeStatus() {
  if (!props.alarm) return
  try {
    await setDispositionStatus(props.alarm.id, newStatus.value)
    disposition.value = await getDisposition(props.alarm.id)
  } catch {
    // Keep the previous state visible when the update fails.
  }
}

async function doAssign() {
  if (!props.alarm || !newAssignee.value.trim()) return
  await assignAlarm(props.alarm.id, newAssignee.value.trim())
  newAssignee.value = ''
}

async function doAddNote() {
  if (!props.alarm || !newNote.value.trim()) return
  await addAlarmNote(props.alarm.id, newNote.value.trim())
  newNote.value = ''
  disposition.value = await getDisposition(props.alarm.id)
}

function openEvidenceSearch() {
  const query = evidence.value?.query
  if (!query) return
  window.sessionStorage.setItem('socp.search.query', query)
  drawerVisible.value = false
  props.goSearch()
}
</script>

<template>
  <el-drawer v-model="drawerVisible" :title="`${t('drawer.title')} · ${props.alarm?.ruleName ?? ''}`" size="480px">
    <template v-if="props.alarm">
      <el-descriptions :column="2" size="small" border style="margin-bottom:14px">
        <el-descriptions-item :label="t('drawer.ruleId')">{{ props.alarm.ruleId }}</el-descriptions-item>
        <el-descriptions-item :label="t('common.severity')"><SevBadge :value="props.alarm.severity" /></el-descriptions-item>
        <el-descriptions-item :label="t('common.entity')">{{ props.alarm.entity }}</el-descriptions-item>
        <el-descriptions-item :label="t('alarms.occurredAt')">{{ props.alarm.occurredAt }}</el-descriptions-item>
        <el-descriptions-item :label="t('common.message')" :span="2">{{ props.alarm.message }}</el-descriptions-item>
        <el-descriptions-item :label="t('drawer.mitre')" :span="2">
          <a v-if="props.alarm.mitre" :href="`https://attack.mitre.org/techniques/${String(props.alarm.mitre).replace('-', '/')}/`" target="_blank" style="color:#409eff;font-weight:600">{{ props.alarm.mitre }}</a>
          <span v-else style="color:#909399">—</span>
        </el-descriptions-item>
        <el-descriptions-item :label="t('drawer.tiHits')" :span="2">
          <span v-if="tiHits.length">
            <el-tag v-for="(hit, index) in tiHits" :key="index" size="small" type="danger" style="margin-right:6px;margin-bottom:4px">{{ hit.type }} · {{ hit.value }}</el-tag>
          </span>
          <span v-else style="color:#909399">—</span>
        </el-descriptions-item>
      </el-descriptions>

      <el-divider content-position="left">{{ t('drawer.evidence') }}</el-divider>
      <el-alert v-if="evidenceError" :title="evidenceError" type="error" :closable="false" />
      <template v-else-if="evidence && evidence.items.length">
        <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:8px;font-size:12px;color:#909399">
          <span>{{ t('drawer.evidenceCount', { count: evidence.total }) }}</span>
          <div v-if="evidence.query" style="display:flex;align-items:center;gap:6px">
            <span class="mono" style="max-width:150px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap" :title="evidence.query">{{ locale === 'zh-CN' ? 'eventId 下钻可用' : 'eventId drill-down' }}</span>
            <el-button link type="primary" size="small" @click="openEvidenceSearch">{{ t('drawer.openSearch') }}</el-button>
          </div>
        </div>
        <div v-for="item in evidence.items" :key="item.id" style="border:1px solid var(--el-border-color-lighter);border-radius:6px;padding:8px 10px;margin-bottom:8px;background:var(--ns-bg-subtle)">
          <div style="display:flex;gap:8px;align-items:center;font-size:12px;color:#909399;margin-bottom:4px">
            <span>{{ item.timestamp || '-' }}</span><span>{{ item.source || '-' }}</span><span>{{ item.host || '-' }}</span>
            <SevBadge v-if="item.severity" :value="item.severity" />
          </div>
          <div class="mono" style="white-space:pre-wrap;word-break:break-word;font-size:12px">{{ item.raw || '-' }}</div>
          <div v-if="item.eventId" style="margin-top:5px;color:#909399;font-size:11px">eventId: {{ item.eventId }}</div>
        </div>
      </template>
      <el-empty v-else :description="t('drawer.noEvidence')" :image-size="50" />

      <el-divider content-position="left">{{ t('drawer.stateFlow') }}</el-divider>
      <div style="display:flex;gap:8px;margin-bottom:8px">
        <el-select v-model="newStatus" style="flex:1"><el-option v-for="s in DISP_STATUSES" :key="s" :label="t('statuses.' + s) || s" :value="s" /></el-select>
        <el-button type="primary" @click="changeStatus">{{ t('common.update') }}</el-button>
      </div>
      <div style="display:flex;gap:8px;margin-bottom:14px">
        <el-input v-model="newAssignee" :placeholder="t('drawer.assigneePlaceholder')" /><el-button @click="doAssign">{{ t('common.assign') }}</el-button>
      </div>

      <el-divider content-position="left">{{ t('drawer.notesTitle') }}</el-divider>
      <div v-if="disposition && disposition.notes.length">
        <div v-for="(note, index) in disposition.notes" :key="index" style="background:var(--ns-bg-subtle);border-radius:6px;padding:8px 12px;margin-bottom:8px">
          <div style="font-size:12px;color:#909399">{{ note.author }} · {{ note.at }}</div><div style="margin-top:2px">{{ note.content }}</div>
        </div>
      </div>
      <el-empty v-else :description="t('drawer.noNotes')" :image-size="50" />
      <div style="display:flex;gap:8px;margin-top:8px">
        <el-input v-model="newNote" :placeholder="t('drawer.addNotePlaceholder')" @keyup.enter="doAddNote" /><el-button type="success" @click="doAddNote">{{ t('common.add') }}</el-button>
      </div>

      <el-divider content-position="left">{{ t('drawer.relatedCase') }}</el-divider>
      <el-card v-if="relatedCase" shadow="never" style="margin-bottom:10px">
        <div style="display:flex;justify-content:space-between;align-items:center;gap:8px">
          <div><div style="font-weight:600">{{ relatedCase.title }}</div><div style="font-size:12px;color:#909399;margin-top:2px">{{ relatedCase.id }} · {{ relatedCase.status }} · {{ relatedCase.entity }} · {{ relatedCase.alarmIds.length }} alarms</div></div>
          <el-button link type="primary" size="small" @click="drawerVisible = false; props.goCase()">{{ t('drawer.goToCase') }}</el-button>
        </div>
      </el-card>
      <el-empty v-else :description="t('drawer.noRelatedCase')" :image-size="50" />
    </template>
  </el-drawer>
</template>
