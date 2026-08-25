<script setup lang="ts">
import 'element-plus/es/components/button/style/css.mjs'
import 'element-plus/es/components/card/style/css.mjs'
import 'element-plus/es/components/col/style/css.mjs'
import 'element-plus/es/components/dialog/style/css.mjs'
import 'element-plus/es/components/form/style/css.mjs'
import 'element-plus/es/components/input/style/css.mjs'
import 'element-plus/es/components/row/style/css.mjs'
import 'element-plus/es/components/tag/style/css.mjs'
import ElButton from 'element-plus/es/components/button/index.mjs'
import ElCard from 'element-plus/es/components/card/index.mjs'
import ElCol from 'element-plus/es/components/col/index.mjs'
import ElDialog from 'element-plus/es/components/dialog/index.mjs'
import { ElForm, ElFormItem } from 'element-plus/es/components/form/index.mjs'
import ElInput from 'element-plus/es/components/input/index.mjs'
import ElRow from 'element-plus/es/components/row/index.mjs'
import ElTag from 'element-plus/es/components/tag/index.mjs'
import { ref } from 'vue'
import type { Watchlist } from '../../api'

defineProps<{ watchlists: Watchlist[] }>()
const emit = defineEmits<{
  create: [name: string, values: string[]]
  append: [name: string, values: string[]]
  remove: [name: string]
}>()

const appendValues = ref<Record<string, string>>({})
const newWatchlist = ref({ name: '', values: '' })
const dialogVisible = ref(false)

function splitValues(value: string) {
  return value.split(/[\n,\s，]+/).map(item => item.trim()).filter(Boolean)
}

function openDialog() {
  newWatchlist.value = { name: '', values: '' }
  dialogVisible.value = true
}

function submitCreate() {
  const name = newWatchlist.value.name.trim()
  if (!name) return
  emit('create', name, splitValues(newWatchlist.value.values))
  newWatchlist.value = { name: '', values: '' }
  dialogVisible.value = false
}

function submitAppend(name: string) {
  const value = appendValues.value[name] || ''
  const values = splitValues(value)
  if (!values.length) return
  emit('append', name, values)
  appendValues.value[name] = ''
}
</script>

<template>
  <div>
    <div class="add-bar">
      <el-button type="primary" @click="openDialog">+ 新增观察名单</el-button>
      <span class="hint">名单可被规则条件 <code class="mono">op=inlist / notinlist</code> 引用，改完立即生效，无需重载规则</span>
    </div>
    <el-dialog v-model="dialogVisible" title="新增观察名单" width="560px">
      <el-form label-width="92px">
        <el-form-item label="名单标识"><el-input v-model="newWatchlist.name" placeholder="如 vip_accounts" /></el-form-item>
        <el-form-item label="成员值">
          <el-input v-model="newWatchlist.values" type="textarea" :rows="4" placeholder="值以逗号/空格/换行分隔" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submitCreate">创建/覆盖</el-button>
      </template>
    </el-dialog>
    <el-row :gutter="12">
      <el-col v-for="watchlist in watchlists" :key="watchlist.name" :span="8" style="margin-bottom:12px">
        <el-card shadow="never" class="wl-card">
          <template #header>
            <div style="display:flex;align-items:center;gap:8px">
              <span class="mono" style="font-weight:600">{{ watchlist.name }}</span>
              <el-tag size="small" type="info">{{ watchlist.size }} 项</el-tag>
              <el-button link type="danger" size="small" style="margin-left:auto" @click="emit('remove', watchlist.name)">删除</el-button>
            </div>
          </template>
          <div class="wl-values">
            <el-tag v-for="value in watchlist.values" :key="value" size="small" style="margin:2px" class="mono">{{ value }}</el-tag>
            <span v-if="!watchlist.values.length" style="color:#c0c4cc;font-size:12px">空名单</span>
          </div>
          <div style="display:flex;gap:6px;margin-top:10px">
            <el-input v-model="appendValues[watchlist.name]" size="small" placeholder="追加值" @keyup.enter="submitAppend(watchlist.name)" />
            <el-button size="small" @click="submitAppend(watchlist.name)">追加</el-button>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>
