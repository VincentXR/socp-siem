<script setup lang="ts">
/**
 * 告警页（2026-08-10 从 App.vue 拆分）：搜索/筛选 + 微卡片列表 + 分页 + 处置抽屉。
 * 数据与加载回调由 App.vue 传入；抽屉/处置逻辑自包含（直接调 api.ts）。
 */
import { ref, computed } from 'vue'
import EmptyState from '../components/EmptyState.vue'
import PageHeader from '../components/PageHeader.vue'
import SevBadge from '../components/SevBadge.vue'
import { sevColor, relTime } from '../lib/ui'
import {
  SEVERITIES, type Alarm, type Ioc,
  getDisposition, setDispositionStatus, assignAlarm, addAlarmNote, listCases,
} from '../api'

const props = defineProps<{
  filteredAlarms: Alarm[]
  alarmPageData: { total: number }
  alarmPageSize: number
  onSearch: () => void
  loadPage: () => void
  exportCsv: () => void
  exportJson: () => void
  goCase: () => void
}>()

const keyword = defineModel<string>('keyword', { default: '' })
const severity = defineModel<string>('severity', { default: '' })
const pageNum = defineModel<number>('pageNum', { default: 1 })

const DISP_STATUSES = ['OPEN', 'INVESTIGATING', 'RESOLVED', 'CLOSED']

// ---- 抽屉/处置（组件自包含） ----
const drawerVisible = ref(false)
const currentAlarm = ref<Alarm | null>(null)
const disposition = ref<{ notes: Array<{ author: string; at: string; content: string }> } | null>(null)
const relatedCase = ref<{ id: string; title: string; status: string; entity: string; alarmIds: string[] } | null>(null)
const newStatus = ref('OPEN')
const newAssignee = ref('')
const newNote = ref('')

function tiHitsList(): Ioc[] {
  try {
    return currentAlarm.value?.tiHits ? (JSON.parse(currentAlarm.value.tiHits) as Ioc[]) : []
  } catch {
    return []
  }
}

async function findRelatedCase(alarmId: string) {
  relatedCase.value = null
  try {
    const all = await listCases()
    relatedCase.value = all.find((c: any) => c.alarmIds?.includes(alarmId)) ?? null
  } catch {
    relatedCase.value = null
  }
}

async function openAlarm(a: Alarm) {
  currentAlarm.value = a
  drawerVisible.value = true
  disposition.value = null
  try {
    disposition.value = await getDisposition(a.id)
  } catch {
    disposition.value = null
  }
  newStatus.value = a.status || 'OPEN'
  newAssignee.value = ''
  newNote.value = ''
  await findRelatedCase(a.id)
}

async function changeStatus() {
  if (!currentAlarm.value) return
  try {
    await setDispositionStatus(currentAlarm.value.id, newStatus.value)
    disposition.value = await getDisposition(currentAlarm.value.id)
  } catch {
    /* 失败保持原状 */
  }
}

async function doAssign() {
  if (!currentAlarm.value || !newAssignee.value.trim()) return
  await assignAlarm(currentAlarm.value.id, newAssignee.value.trim())
  newAssignee.value = ''
}

async function doAddNote() {
  if (!currentAlarm.value || !newNote.value.trim()) return
  await addAlarmNote(currentAlarm.value.id, newNote.value.trim())
  newNote.value = ''
  disposition.value = await getDisposition(currentAlarm.value.id)
}

const sevTagStyle = (s: string) => ({ background: sevColor(s) })
</script>

<template>
  <div class="page-pad view-enter">
    <PageHeader title="告警查询" description="按规则、实体和严重级别筛选告警，并从右侧抽屉完成处置。" />
    <div class="alarm-toolbar">
      <el-input v-model="keyword" placeholder="搜索规则/实体/消息" clearable style="width:280px" @keyup.enter="props.onSearch" @clear="props.onSearch" />
      <el-select v-model="severity" placeholder="全部级别" clearable style="width:140px" @change="props.onSearch">
        <el-option v-for="s in SEVERITIES" :key="s" :label="s" :value="s" />
      </el-select>
      <el-button size="small" @click="props.onSearch">查询</el-button>
      <span class="toolbar-count">共 {{ props.alarmPageData.total }} 条</span>
      <span class="toolbar-spacer" />
      <el-button size="small" @click="props.exportCsv">导出 CSV</el-button>
      <el-button size="small" @click="props.exportJson">导出 JSON</el-button>
    </div>

    <!-- 告警微卡片列表 -->
    <div class="card-list">
      <div v-for="a in props.filteredAlarms" :key="a.id" class="alarm-card" @click="openAlarm(a)">
        <span class="alarm-sev-dot" :style="{ background: sevColor(a.severity) }" />
        <div class="alarm-body">
          <div class="alarm-top">
            <span class="alarm-sev-tag" :style="sevTagStyle(a.severity)">{{ a.severity }}</span>
            <span class="alarm-rule">{{ a.ruleName || a.ruleId }}</span>
            <span class="alarm-entity mono">{{ a.entity }}</span>
            <span class="alarm-status" :class="(a.status || 'OPEN').toLowerCase()">{{ a.status || 'OPEN' }}</span>
            <span class="alarm-time">{{ relTime(a.occurredAt) }}</span>
          </div>
          <div class="alarm-msg">{{ a.message }}</div>
        </div>
        <el-button link type="primary" size="small" @click.stop="openAlarm(a)">处置</el-button>
      </div>
      <EmptyState v-if="!props.filteredAlarms.length" title="暂无告警" description="调整筛选条件后重试，或等待新的告警进入系统。" />
    </div>

    <div class="alarm-pagination">
      <el-pagination
        v-model:current-page="pageNum"
        :page-size="props.alarmPageSize"
        :total="props.alarmPageData.total"
        :page-sizes="[10, 20, 50, 100]"
        layout="total, sizes, prev, pager, next"
        @current-change="props.loadPage"
        @size-change="() => { pageNum = 1; props.loadPage() }" />
    </div>

    <!-- 告警详情/处置抽屉 -->
    <el-drawer v-model="drawerVisible" :title="`告警处置 · ${currentAlarm?.ruleName ?? ''}`" size="480px">
      <template v-if="currentAlarm">
        <el-descriptions :column="2" size="small" border style="margin-bottom:14px">
          <el-descriptions-item label="规则 ID">{{ currentAlarm.ruleId }}</el-descriptions-item>
          <el-descriptions-item label="级别"><SevBadge :value="currentAlarm.severity" /></el-descriptions-item>
          <el-descriptions-item label="实体">{{ currentAlarm.entity }}</el-descriptions-item>
          <el-descriptions-item label="发生时间">{{ currentAlarm.occurredAt }}</el-descriptions-item>
          <el-descriptions-item label="消息" :span="2">{{ currentAlarm.message }}</el-descriptions-item>
          <el-descriptions-item label="ATT&CK" :span="2">
            <a v-if="currentAlarm.mitre" :href="`https://attack.mitre.org/techniques/${String(currentAlarm.mitre).replace('-', '/')}/`" target="_blank" style="color:#409eff;font-weight:600">{{ currentAlarm.mitre }}</a>
            <span v-else style="color:#909399">—</span>
          </el-descriptions-item>
          <el-descriptions-item label="威胁情报命中" :span="2">
            <span v-if="tiHitsList().length">
              <el-tag v-for="(h, i) in tiHitsList()" :key="i" size="small" type="danger" style="margin-right:6px;margin-bottom:4px">{{ h.type }} · {{ h.value }}</el-tag>
            </span>
            <span v-else style="color:#909399">—</span>
          </el-descriptions-item>
        </el-descriptions>

        <el-divider content-position="left">状态流转</el-divider>
        <div style="display:flex;gap:8px;margin-bottom:8px">
          <el-select v-model="newStatus" style="flex:1">
            <el-option v-for="s in DISP_STATUSES" :key="s" :label="s" :value="s" />
          </el-select>
          <el-button type="primary" @click="changeStatus">更新</el-button>
        </div>
        <div style="display:flex;gap:8px;margin-bottom:14px">
          <el-input v-model="newAssignee" placeholder="分配人，如 ops-zhang" />
          <el-button @click="doAssign">分配</el-button>
        </div>

        <el-divider content-position="left">备注 / 调查记录</el-divider>
        <div v-if="disposition && disposition.notes.length">
          <div v-for="(n, i) in disposition.notes" :key="i" style="background:var(--ns-bg-subtle);border-radius:6px;padding:8px 12px;margin-bottom:8px">
            <div style="font-size:12px;color:#909399">{{ n.author }} · {{ n.at }}</div>
            <div style="margin-top:2px">{{ n.content }}</div>
          </div>
        </div>
        <el-empty v-else description="暂无备注" :image-size="50" />
        <div style="display:flex;gap:8px;margin-top:8px">
          <el-input v-model="newNote" placeholder="添加调查备注…" @keyup.enter="doAddNote" />
          <el-button type="success" @click="doAddNote">添加</el-button>
        </div>

        <el-divider content-position="left">关联案件</el-divider>
        <el-card v-if="relatedCase" shadow="never" style="margin-bottom:10px">
          <div style="display:flex;justify-content:space-between;align-items:center;gap:8px">
            <div>
              <div style="font-weight:600">{{ relatedCase.title }}</div>
              <div style="font-size:12px;color:#909399;margin-top:2px">{{ relatedCase.id }} · {{ relatedCase.status }} · 实体 {{ relatedCase.entity }} · 告警 {{ relatedCase.alarmIds.length }} 条</div>
            </div>
            <el-button link type="primary" size="small" @click="drawerVisible = false; props.goCase()">前往案件</el-button>
          </div>
        </el-card>
        <el-empty v-else description="暂无关联案件（告警创建时会自动建案/归并）" :image-size="50" />
      </template>
    </el-drawer>
  </div>
</template>
