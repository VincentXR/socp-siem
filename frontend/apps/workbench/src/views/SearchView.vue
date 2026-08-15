<script setup lang="ts">
import 'element-plus/es/components/alert/style/css.mjs'
import 'element-plus/es/components/button/style/css.mjs'
import 'element-plus/es/components/card/style/css.mjs'
import 'element-plus/es/components/input/style/css.mjs'
import 'element-plus/es/components/table/style/css.mjs'
import 'element-plus/es/components/tag/style/css.mjs'
import ElAlert from 'element-plus/es/components/alert/index.mjs'
import ElButton from 'element-plus/es/components/button/index.mjs'
import ElCard from 'element-plus/es/components/card/index.mjs'
import ElInput from 'element-plus/es/components/input/index.mjs'
import { ElTable, ElTableColumn } from 'element-plus/es/components/table/index.mjs'
import ElTag from 'element-plus/es/components/tag/index.mjs'
import { computed, onMounted, ref } from 'vue'
import PageHeader from '../components/PageHeader.vue'
import SevBadge from '../components/SevBadge.vue'
import { exportSearch, splSearch, type SearchResult } from '../api'

const query = ref('source=auth severity=HIGH')
const result = ref<SearchResult | null>(null)
const loading = ref(false)
const error = ref('')
const examples = [
  'source=auth severity=HIGH',
  'msg contains "blocked" | top src_ip 5',
  'severity>=HIGH | timechart',
  'src_ip=10.0.0.9 OR user=admin',
  'source=web | count by http_method',
  'bytes>=1000 | head 10',
]
const maxStatCount = computed(() => Math.max(1, ...(result.value?.stat?.rows.map(row => Number(row.count)) ?? [1])))

async function search() {
  loading.value = true
  error.value = ''
  try {
    result.value = await splSearch(query.value)
  } catch (err) {
    result.value = null
    error.value = `检索失败：${err instanceof Error ? err.message : String(err)}`
  } finally {
    loading.value = false
  }
}

function runExample(example: string) {
  query.value = example
  search()
}

onMounted(search)
</script>

<template>
  <div class="page-pad view-enter">
    <PageHeader title="日志检索" description="使用 SPL 查询事件、聚合统计并导出当前结果。" />
    <el-card shadow="never" class="search-toolbar">
      <div class="search-query-row">
        <el-input v-model="query" placeholder="SPL 查询，如 source=auth severity=HIGH | top src_ip 5" clearable @keyup.enter="search" />
        <el-button type="primary" :loading="loading" @click="search">执行检索</el-button>
        <el-button size="small" @click="exportSearch(query, 'json')">导出 JSON</el-button>
        <el-button size="small" @click="exportSearch(query, 'csv')">导出 CSV</el-button>
      </div>
      <div class="search-examples">
        <el-tag v-for="example in examples" :key="example" size="small" @click="runExample(example)">{{ example }}</el-tag>
      </div>
    </el-card>

    <el-alert v-if="error" :title="error" type="error" :closable="false" class="search-error" />

    <template v-if="result">
      <el-card shadow="never" class="search-result-card">
        <template #header>命中 {{ result.total }} 条事件</template>
        <el-table :data="result.events" size="small" max-height="420">
          <el-table-column prop="timestamp" label="时间" width="150" sortable><template #default="{ row }">{{ row.timestamp.slice(0, 19).replace('T', ' ') }}</template></el-table-column>
          <el-table-column prop="source" label="来源" width="90" sortable />
          <el-table-column prop="host" label="主机" width="90" sortable />
          <el-table-column prop="severity" label="级别" width="80" sortable><template #default="{ row }"><SevBadge :value="row.severity" /></template></el-table-column>
          <el-table-column prop="msg" label="消息" min-width="240" sortable show-overflow-tooltip />
        </el-table>
      </el-card>
      <el-card v-if="result.stat" shadow="never">
        <template #header>{{ result.stat.type === 'timechart' ? '时间分布（按天）' : `统计（${result.stat.type === 'top' ? 'Top' : '分组计数'}）` }}</template>
        <el-table :data="result.stat.rows" size="small">
          <el-table-column prop="key" label="Key" sortable show-overflow-tooltip />
          <el-table-column prop="count" label="条数" width="220" sortable>
            <template #default="{ row }">
              <div class="search-stat-row"><span>{{ row.count }}</span><span class="search-stat-track"><i :style="{ width: `${Math.min(100, (row.count / maxStatCount) * 100)}%` }" /></span></div>
            </template>
          </el-table-column>
        </el-table>
      </el-card>
    </template>
  </div>
</template>
