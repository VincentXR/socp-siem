<script setup lang="ts">
import 'element-plus/es/components/button/style/css.mjs'
import 'element-plus/es/components/card/style/css.mjs'
import 'element-plus/es/components/col/style/css.mjs'
import 'element-plus/es/components/descriptions/style/css.mjs'
import 'element-plus/es/components/dialog/style/css.mjs'
import 'element-plus/es/components/drawer/style/css.mjs'
import 'element-plus/es/components/empty/style/css.mjs'
import 'element-plus/es/components/form/style/css.mjs'
import 'element-plus/es/components/input/style/css.mjs'
import 'element-plus/es/components/input-number/style/css.mjs'
import 'element-plus/es/components/progress/style/css.mjs'
import 'element-plus/es/components/row/style/css.mjs'
import 'element-plus/es/components/select/style/css.mjs'
import 'element-plus/es/components/slider/style/css.mjs'
import 'element-plus/es/components/table/style/css.mjs'
import 'element-plus/es/components/tabs/style/css.mjs'
import 'element-plus/es/components/tag/style/css.mjs'
import ElButton from 'element-plus/es/components/button/index.mjs'
import ElCard from 'element-plus/es/components/card/index.mjs'
import ElCol from 'element-plus/es/components/col/index.mjs'
import { ElDescriptions, ElDescriptionsItem } from 'element-plus/es/components/descriptions/index.mjs'
import ElDialog from 'element-plus/es/components/dialog/index.mjs'
import ElDrawer from 'element-plus/es/components/drawer/index.mjs'
import ElEmpty from 'element-plus/es/components/empty/index.mjs'
import { ElForm, ElFormItem } from 'element-plus/es/components/form/index.mjs'
import ElInput from 'element-plus/es/components/input/index.mjs'
import ElInputNumber from 'element-plus/es/components/input-number/index.mjs'
import ElProgress from 'element-plus/es/components/progress/index.mjs'
import ElRow from 'element-plus/es/components/row/index.mjs'
import { ElOption, ElSelect } from 'element-plus/es/components/select/index.mjs'
import ElSlider from 'element-plus/es/components/slider/index.mjs'
import { ElTable, ElTableColumn } from 'element-plus/es/components/table/index.mjs'
import { ElTabPane, ElTabs } from 'element-plus/es/components/tabs/index.mjs'
import ElTag from 'element-plus/es/components/tag/index.mjs'
import { onMounted, onUnmounted, ref, shallowRef, watch } from 'vue'
import type { ECharts } from 'echarts/core'
import { loadEcharts } from '../lib/echarts'
import SevBadge from '../components/SevBadge.vue'
import {
  appendWatchlist, deleteWatchlist, listWatchlists, putWatchlist,
  uebaEntities, uebaEntity, uebaScore, uebaSummary,
  SEVERITIES,
  type RiskEntity, type RiskSummary, type ScoreBreakdown, type Watchlist,
} from '../api'

const props = defineProps<{ theme: 'light' | 'dark' }>()
const emit = defineEmits<{ 'go-alarms': [entity: string] }>()

const riskEntities = ref<RiskEntity[]>([])
const riskSummary = ref<RiskSummary | null>(null)
const riskLimit = ref(20)
const entityDrawer = ref(false)
const entityDetail = ref<RiskEntity | null>(null)
const watchlists = ref<Watchlist[]>([])
const wlAppend = ref<Record<string, string>>({})
const newWl = ref({ name: '', values: '' })
const showWlDialog = ref(false)
const uebaTab = ref('entities')
const scoreForm = ref({ severity: 'HIGH', mitre: 'T1110', tiHits: 1, recentAlerts: 3, assetCriticality: 2 })
const scoreResult = ref<ScoreBreakdown | null>(null)
const riskBarEl = ref<HTMLElement>()
const chartRiskBar = shallowRef<ECharts>()
let renderToken = 0
const BREAKDOWN_LABEL: Record<string, string> = {
  base: '严重级别基线', tactic: 'ATT&CK 战术权重', intel: '情报命中加成',
  frequency: '实体频次加成', asset: '资产重要性加成',
}

function openWlDialog() {
  newWl.value = { name: '', values: '' }
  showWlDialog.value = true
}

function sevColor(severity: string) {
  return { CRITICAL: '#f56c6c', HIGH: '#e63946', MEDIUM: '#e6a23c', LOW: '#909399', INFO: '#909399' }[severity] ?? '#909399'
}
function riskColor(level: string) { return sevColor(level) }
function fmtTime(value: string | null) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—' }

async function loadUeba() {
  const [entities, summary, lists] = await Promise.allSettled([uebaEntities(riskLimit.value), uebaSummary(), listWatchlists()])
  riskEntities.value = entities.status === 'fulfilled' ? entities.value : []
  riskSummary.value = summary.status === 'fulfilled' ? summary.value : null
  watchlists.value = lists.status === 'fulfilled' ? lists.value : []
  renderRiskBar()
  if (!scoreResult.value) await calcScore()
}
function renderRiskBar() {
  const token = ++renderToken
  setTimeout(async () => {
    const echarts = await loadEcharts()
    if (token !== renderToken) return
    if (!riskBarEl.value) return
    if (!chartRiskBar.value || chartRiskBar.value.isDisposed()) chartRiskBar.value = echarts.init(riskBarEl.value, 'socp')
    const top = riskEntities.value.slice(0, 10).slice().reverse()
    chartRiskBar.value.setOption({
      grid: { left: 4, right: 40, top: 10, bottom: 10, containLabel: true },
      tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
      xAxis: { type: 'value', max: 100, axisLabel: { fontSize: 10 }, splitLine: { lineStyle: { type: 'dashed' } } },
      yAxis: { type: 'category', data: top.map(entity => entity.entity), axisLabel: { fontSize: 11 } },
      series: [{ type: 'bar', barWidth: 14, data: top.map(entity => ({ value: entity.risk, itemStyle: { color: sevColor(entity.level), borderRadius: [0, 7, 7, 0] } })), label: { show: true, position: 'right', fontSize: 11, formatter: '{c}' } }],
    })
  }, 80)
}
async function openEntity(entity: RiskEntity) {
  entityDetail.value = entity
  entityDrawer.value = true
  try { entityDetail.value = await uebaEntity(entity.entity) } catch { /* 用列表快照兜底 */ }
}
async function calcScore() {
  try { scoreResult.value = await uebaScore(scoreForm.value) } catch { scoreResult.value = null }
}
async function doAppendWl(name: string) {
  const rawValue = (wlAppend.value[name] || '').trim()
  if (!rawValue) return
  await appendWatchlist(name, rawValue.split(/[\n,，\s]+/).filter(Boolean))
  wlAppend.value[name] = ''
  watchlists.value = await listWatchlists()
}
async function doCreateWl() {
  const name = newWl.value.name.trim()
  if (!name) return
  await putWatchlist(name, newWl.value.values.split(/[\n,，\s]+/).filter(Boolean))
  newWl.value = { name: '', values: '' }
  showWlDialog.value = false
  watchlists.value = await listWatchlists()
}
async function doDeleteWl(name: string) {
  await deleteWatchlist(name)
  watchlists.value = await listWatchlists()
}
function goToAlarms() {
  if (entityDetail.value) {
    entityDrawer.value = false
    emit('go-alarms', entityDetail.value.entity)
  }
}
function onResize() { chartRiskBar.value?.resize() }

watch(() => props.theme, renderRiskBar)
onMounted(loadUeba)
onMounted(() => window.addEventListener('resize', onResize))
onUnmounted(() => {
  renderToken++
  window.removeEventListener('resize', onResize)
  chartRiskBar.value?.dispose()
})
</script>

<template>
        <!-- UEBA 风险看板 -->
  <div class="page-pad view-enter">
          <el-row :gutter="12" style="margin-bottom:14px">
            <el-col :span="5"><el-card shadow="never"><div class="stat-card"><div class="num">{{ riskSummary?.entities ?? 0 }}</div><div class="label">画像实体数</div></div></el-card></el-col>
            <el-col :span="5"><el-card shadow="never"><div class="stat-card"><div class="num" style="color:#f56c6c">{{ riskSummary?.maxRisk ?? 0 }}</div><div class="label">最高风险分</div></div></el-card></el-col>
            <el-col :span="5"><el-card shadow="never"><div class="stat-card"><div class="num" style="color:#e6a23c">{{ (riskSummary?.byLevel?.CRITICAL ?? 0) + (riskSummary?.byLevel?.HIGH ?? 0) }}</div><div class="label">高危实体</div></div></el-card></el-col>
            <el-col :span="5"><el-card shadow="never"><div class="stat-card"><div class="num">{{ riskSummary?.halfLifeHours ?? 0 }}h</div><div class="label">风险半衰期</div></div></el-card></el-col>
            <el-col :span="4"><el-card shadow="never"><div class="stat-card"><div class="num" style="color:#409eff">{{ watchlists.length }}</div><div class="label">观察名单</div></div></el-card></el-col>
          </el-row>

          <el-tabs v-model="uebaTab">
            <!-- 实体风险 -->
            <el-tab-pane label="实体风险排行" name="entities">
              <div style="display:flex;gap:10px;align-items:center;margin-bottom:12px">
                <span style="font-size:13px;color:#909399">Top N</span>
                <el-input-number v-model="riskLimit" :min="5" :max="100" :step="5" size="small" @change="loadUeba" />
                <el-button size="small" @click="loadUeba">刷新</el-button>
                <span style="font-size:12px;color:#909399">
                  风险分 = 严重级别 + ATT&CK 战术权重 + 情报命中 + 频次 + 资产重要性，按 {{ riskSummary?.halfLifeHours ?? 6 }} 小时半衰期指数衰减
                </span>
              </div>
              <el-row :gutter="12">
                <el-col :span="10">
                  <el-card shadow="never">
                    <template #header>风险 Top 10</template>
                    <div ref="riskBarEl" style="height:340px"></div>
                  </el-card>
                </el-col>
                <el-col :span="14">
                  <el-card shadow="never">
                    <template #header>实体明细（点击行下钻）</template>
                    <el-table :data="riskEntities" size="small" border height="340" @row-click="openEntity">
                      <el-table-column label="风险" width="80">
                        <template #default="{ row }"><span class="risk-pill" :style="{ background: riskColor(row.level) }">{{ row.risk }}</span></template>
                      </el-table-column>
                      <el-table-column label="实体" min-width="150" show-overflow-tooltip>
                        <template #default="{ row }">
                          <span class="mono">{{ row.entity }}</span>
                          <el-tag v-if="row.critical" size="small" type="danger" effect="dark" style="margin-left:6px">核心资产</el-tag>
                        </template>
                      </el-table-column>
                      <el-table-column prop="alerts" label="告警数" width="80" />
                      <el-table-column label="最高级别" width="100">
                        <template #default="{ row }"><SevBadge :value="row.maxSeverity" /></template>
                      </el-table-column>
                      <el-table-column label="主要战术" min-width="140" show-overflow-tooltip>
                        <template #default="{ row }">
                          <el-tag v-for="m in row.mitre.slice(0, 3)" :key="m.technique" size="small" style="margin-right:4px">{{ m.technique }}×{{ m.count }}</el-tag>
                          <span v-if="!row.mitre.length" style="color:#c0c4cc">—</span>
                        </template>
                      </el-table-column>
                      <el-table-column label="最近活动" width="150" show-overflow-tooltip>
                        <template #default="{ row }"><span class="mono" style="font-size:12px">{{ fmtTime(row.lastSeen) }}</span></template>
                      </el-table-column>
                    </el-table>
                  </el-card>
                </el-col>
              </el-row>
            </el-tab-pane>

            <!-- 观察名单 -->
            <el-tab-pane label="观察名单" name="watchlists">
              <div class="add-bar">
                <el-button type="primary" @click="openWlDialog">+ 新增观察名单</el-button>
                <span class="hint">名单可被规则条件 <code class="mono">op=inlist / notinlist</code> 引用，改完立即生效，无需重载规则</span>
              </div>
              <el-dialog v-model="showWlDialog" title="新增观察名单" width="560px">
                <el-form label-width="92px">
                  <el-form-item label="名单标识"><el-input v-model="newWl.name" placeholder="如 vip_accounts" /></el-form-item>
                  <el-form-item label="成员值">
                    <el-input v-model="newWl.values" type="textarea" :rows="4" placeholder="值，逗号/空格/换行分隔" />
                  </el-form-item>
                </el-form>
                <template #footer>
                  <el-button @click="showWlDialog = false">取消</el-button>
                  <el-button type="primary" @click="doCreateWl(); showWlDialog = false">创建/覆盖</el-button>
                </template>
              </el-dialog>
              <el-row :gutter="12">
                <el-col v-for="w in watchlists" :key="w.name" :span="8" style="margin-bottom:12px">
                  <el-card shadow="never" class="wl-card">
                    <template #header>
                      <div style="display:flex;align-items:center;gap:8px">
                        <span class="mono" style="font-weight:600">{{ w.name }}</span>
                        <el-tag size="small" type="info">{{ w.size }} 项</el-tag>
                        <el-button link type="danger" size="small" style="margin-left:auto" @click="doDeleteWl(w.name)">删除</el-button>
                      </div>
                    </template>
                    <div class="wl-values">
                      <el-tag v-for="v in w.values" :key="v" size="small" style="margin:2px" class="mono">{{ v }}</el-tag>
                      <span v-if="!w.values.length" style="color:#c0c4cc;font-size:12px">空名单</span>
                    </div>
                    <div style="display:flex;gap:6px;margin-top:10px">
                      <el-input v-model="wlAppend[w.name]" size="small" placeholder="追加值" @keyup.enter="doAppendWl(w.name)" />
                      <el-button size="small" @click="doAppendWl(w.name)">追加</el-button>
                    </div>
                  </el-card>
                </el-col>
              </el-row>
            </el-tab-pane>

            <!-- 评分模型 -->
            <el-tab-pane label="评分模型试算" name="score">
              <el-row :gutter="12">
                <el-col :span="10">
                  <el-card shadow="never">
                    <template #header>输入条件</template>
                    <el-form label-width="120px" size="small">
                      <el-form-item label="严重级别">
                        <el-select v-model="scoreForm.severity" @change="calcScore" style="width:160px">
                          <el-option v-for="s in SEVERITIES" :key="s" :label="s" :value="s" />
                        </el-select>
                      </el-form-item>
                      <el-form-item label="ATT&CK 技术">
                        <el-input v-model="scoreForm.mitre" placeholder="如 T1486" style="width:160px" @change="calcScore" />
                      </el-form-item>
                      <el-form-item label="情报命中数">
                        <el-slider v-model="scoreForm.tiHits" :min="0" :max="5" show-stops @change="calcScore" />
                      </el-form-item>
                      <el-form-item label="近 1h 同实体告警">
                        <el-slider v-model="scoreForm.recentAlerts" :min="0" :max="20" @change="calcScore" />
                      </el-form-item>
                      <el-form-item label="资产重要性">
                        <el-slider v-model="scoreForm.assetCriticality" :min="0" :max="3" show-stops @change="calcScore" />
                      </el-form-item>
                    </el-form>
                  </el-card>
                </el-col>
                <el-col :span="14">
                  <el-card shadow="never">
                    <template #header>评分拆解（与检测/分析侧同一口径）</template>
                    <div v-if="scoreResult">
                      <div style="display:flex;align-items:baseline;gap:12px;margin-bottom:16px">
                        <span style="font-size:44px;font-weight:700" :style="{ color: riskColor(scoreResult.level) }">{{ scoreResult.score }}</span>
                        <SevBadge :value="scoreResult.level" />
                        <span style="font-size:12px;color:#909399">总分上限 100</span>
                      </div>
                      <div v-for="(v, k) in scoreResult.breakdown" :key="k" class="bd-row">
                        <span class="bd-label">{{ BREAKDOWN_LABEL[k] ?? k }}</span>
                        <div class="bd-bar"><div class="bd-fill" :style="{ width: Math.min(100, v) + '%', background: riskColor(scoreResult.level) }" /></div>
                        <span class="bd-val">+{{ v }}</span>
                      </div>
                    </div>
                    <el-empty v-else description="评分服务不可用" />
                  </el-card>
                </el-col>
              </el-row>
            </el-tab-pane>
          </el-tabs>

          <!-- 实体下钻抽屉 -->
          <el-drawer v-model="entityDrawer" size="480px" :title="entityDetail?.entity ?? '实体画像'">
            <div v-if="entityDetail">
              <div style="display:flex;align-items:center;gap:12px;margin-bottom:16px">
                <span class="risk-pill lg" :style="{ background: riskColor(entityDetail.level) }">{{ entityDetail.risk }}</span>
                <div>
                  <div style="font-weight:600" class="mono">{{ entityDetail.entity }}</div>
                  <div style="font-size:12px;color:#909399">{{ entityDetail.level }} · {{ entityDetail.alerts }} 条告警</div>
                </div>
                <el-tag v-if="entityDetail.critical" type="danger" effect="dark" style="margin-left:auto">核心资产</el-tag>
              </div>
              <el-descriptions :column="1" border size="small">
                <el-descriptions-item label="最高级别"><SevBadge :value="entityDetail.maxSeverity" /></el-descriptions-item>
                <el-descriptions-item label="首次出现">{{ fmtTime(entityDetail.firstSeen) }}</el-descriptions-item>
                <el-descriptions-item label="最近活动">{{ fmtTime(entityDetail.lastSeen) }}</el-descriptions-item>
              </el-descriptions>
              <h4 style="margin:16px 0 8px">ATT&CK 技术分布</h4>
              <el-table :data="entityDetail.mitre" size="small" border>
                <el-table-column prop="technique" label="技术" width="120" />
                <el-table-column prop="count" label="次数" width="80" />
                <el-table-column label="占比">
                  <template #default="{ row }">
                    <el-progress :percentage="Math.round(row.count / entityDetail!.alerts * 100)" :stroke-width="10" />
                  </template>
                </el-table-column>
              </el-table>
              <h4 style="margin:16px 0 8px">触发最多的规则</h4>
              <el-table :data="entityDetail.topRules" size="small" border>
                <el-table-column prop="rule" label="规则" min-width="180" show-overflow-tooltip />
                <el-table-column prop="count" label="次数" width="80" />
              </el-table>
              <div style="margin-top:16px">
                <el-button type="primary" plain @click="goToAlarms">查看该实体全部告警</el-button>
              </div>
            </div>
          </el-drawer>
        </div>
</template>
