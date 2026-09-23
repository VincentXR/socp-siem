<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import ElButton from 'element-plus/es/components/button/index.mjs'
import ElDialog from 'element-plus/es/components/dialog/index.mjs'
import ElDrawer from 'element-plus/es/components/drawer/index.mjs'
import { ElForm, ElFormItem } from 'element-plus/es/components/form/index.mjs'
import ElInput from 'element-plus/es/components/input/index.mjs'
import { ElTable, ElTableColumn } from 'element-plus/es/components/table/index.mjs'
import 'element-plus/es/components/button/style/css.mjs'
import 'element-plus/es/components/dialog/style/css.mjs'
import 'element-plus/es/components/drawer/style/css.mjs'
import 'element-plus/es/components/form/style/css.mjs'
import 'element-plus/es/components/input/style/css.mjs'
import 'element-plus/es/components/table/style/css.mjs'
import { useMutation } from '../../composables/useMutation'
import { useFormDialog } from '../../composables/useFormDialog'
import { useI18n } from '../../composables/useI18n'
import ActionFeedback from '../ActionFeedback.vue'
import DataTableCard from '../DataTableCard.vue'
import FormField from '../FormField.vue'
import FormGrid from '../FormGrid.vue'
import FormSection from '../FormSection.vue'
import type { Watchlist, WatchlistSummary } from '../../api'

const props = withDefaults(defineProps<{
  watchlists: WatchlistSummary[]
  load: (name: string) => Promise<Watchlist>
  create: (name: string, values: string[]) => Promise<Watchlist>
  append: (name: string, values: string[]) => Promise<Watchlist>
  canWrite?: boolean
}>(), { canWrite: true })
const emit = defineEmits<{ remove: [name: string] }>()
const mutation = useMutation()
const { busy, error } = mutation
const { t } = useI18n()
const newWatchlist = ref({ name: '', values: '' })
const dialogVisible = ref(false)
const drawerVisible = ref(false)
const keyword = ref('')
const selectedName = ref('')
const appendText = ref('')
const entrySearch = ref('')
const page = ref(1)
const size = ref(20)
const cataloguePage = ref(1)
const catalogueSize = ref(20)
const details = ref<Watchlist>()
const detailsLoading = ref(false)
const detailsError = ref('')
let requestId = 0
const filtered = computed(() => props.watchlists.filter(item => item.name.toLowerCase().includes(keyword.value.toLowerCase())))
const selected = computed(() => details.value?.name === selectedName.value ? details.value : undefined)
const entries = computed(() => (selected.value?.values || []).filter(value => value.toLowerCase().includes(entrySearch.value.toLowerCase())))
const dialogGuard = useFormDialog(dialogVisible, () => newWatchlist.value, () => busy.value)
const drawerGuard = useFormDialog(drawerVisible, () => appendText.value, () => busy.value)
function splitValues(value: string) { return value.split(/[\n,\s，]+/).map(item => item.trim()).filter(Boolean) }
async function openList(name: string) {
  if (busy.value) return
  const currentRequest = ++requestId
  selectedName.value = name; entrySearch.value = ''; appendText.value = ''; page.value = 1; error.value = ''; drawerVisible.value = true
  details.value = undefined; detailsError.value = ''; detailsLoading.value = true
  try {
    const result = await props.load(name)
    if (currentRequest === requestId && drawerVisible.value) details.value = result
  } catch (failure) {
    if (currentRequest === requestId && drawerVisible.value) detailsError.value = failure instanceof Error ? failure.message : String(failure)
  } finally {
    if (currentRequest === requestId) detailsLoading.value = false
  }
}
watch(keyword, () => { cataloguePage.value = 1 })
watch(drawerVisible, visible => { if (!visible) { requestId++; detailsLoading.value = false } })
watch(() => props.watchlists, lists => {
  cataloguePage.value = Math.min(cataloguePage.value, Math.max(1, Math.ceil(filtered.value.length / catalogueSize.value)))
  if (selectedName.value && !lists.some(item => item.name === selectedName.value) && !busy.value) drawerVisible.value = false
})
onBeforeUnmount(() => { requestId++ })
function openDialog() { if (!props.canWrite) return; newWatchlist.value = { name: '', values: '' }; error.value = ''; dialogVisible.value = true }
async function submitCreate() {
  if (!props.canWrite) return
  const name = newWatchlist.value.name.trim()
  if (!name) { error.value = t('forms.fieldRequired', { field: t('ueba.watchlistName') }); return }
  if (props.watchlists.some(item => item.name.toLowerCase() === name.toLowerCase())) { error.value = t('forms.duplicateName'); return }
  await mutation.run(async () => {
    const saved = await props.create(name, splitValues(newWatchlist.value.values))
    requestId++; details.value = saved; selectedName.value = saved.name; entrySearch.value = ''; appendText.value = ''; page.value = 1
    detailsLoading.value = false; detailsError.value = ''; dialogVisible.value = false; drawerVisible.value = true
  })
}
async function submitAppend(name: string) {
  if (!props.canWrite) return
  const values = splitValues(appendText.value)
  if (!values.length) return
  if (!await mutation.run(async () => { details.value = await props.append(name, values) })) return
  appendText.value = ''
  drawerGuard.markSaved()
}
</script>

<template>
  <div>
    <ActionFeedback :error="error" />
    <div v-if="props.canWrite" class="add-bar"><el-button type="primary" @click="openDialog">{{ t('ueba.watchlistCreate') }}</el-button></div>
    <el-input v-model="keyword" :placeholder="t('forms.search')" clearable />
    <DataTableCard v-model:current-page="cataloguePage" v-model:page-size="catalogueSize" :total="filtered.length" :empty-title="t('ueba.emptyWatchlist')" style="margin-top:16px">
    <el-table :data="filtered.slice((cataloguePage - 1) * catalogueSize, cataloguePage * catalogueSize)">
      <el-table-column prop="name" :label="t('ueba.watchlistName')" min-width="200" />
      <el-table-column prop="size" :label="t('forms.entries')" width="120" />
      <el-table-column :label="t('common.actions')" width="200"><template #default="{ row }">
        <el-button link :disabled="busy" @click="openList(row.name)">{{ t('forms.entries') }}</el-button>
        <el-button v-if="props.canWrite" link type="danger" :disabled="busy" @click="emit('remove', row.name)">{{ t('common.delete') }}</el-button>
      </template></el-table-column>
    </el-table>
    </DataTableCard>
    <el-dialog v-model="dialogVisible" :before-close="dialogGuard.beforeClose" :title="t('ueba.watchlistCreate')" width="440px">
      <ActionFeedback :error="error" />
        <el-form label-position="top" :disabled="busy || !props.canWrite">
        <FormGrid :columns="1">
          <FormField :label="t('ueba.watchlistName')" required :hint="t('ueba.watchlistNameHint')">
            <el-input v-model="newWatchlist.name" :placeholder="t('ueba.watchlistNamePlaceholder')" />
          </FormField>
          <FormField :label="t('ueba.watchlistValues')" :hint="t('ueba.watchlistValuesHint')">
            <el-input v-model="newWatchlist.values" type="textarea" :rows="4" :placeholder="t('ueba.watchlistValuesPlaceholder')" />
          </FormField>
        </FormGrid>
      </el-form>
      <template #footer><el-button @click="dialogGuard.cancel">{{ t('common.cancel') }}</el-button><el-button v-if="props.canWrite" type="primary" :loading="busy" @click="submitCreate">{{ t('common.create') }}</el-button></template>
    </el-dialog>
    <el-drawer v-model="drawerVisible" :before-close="drawerGuard.beforeClose" :title="selectedName" size="min(720px, 96vw)">
      <p v-if="detailsLoading" role="status">{{ t('common.loading') }}</p>
      <ActionFeedback :error="detailsError" />
      <el-button v-if="detailsError" @click="openList(selectedName)">{{ t('common.retry') }}</el-button>
      <template v-if="selected">
        <ActionFeedback :error="error" />
        <el-button tag="a" link :href="`/detect?reference=${encodeURIComponent(selected.name)}`">{{ t('forms.references') }}</el-button>
        <el-input v-model="entrySearch" :placeholder="t('forms.search')" clearable @input="page = 1" />
        <DataTableCard v-model:current-page="page" v-model:page-size="size" :total="entries.length" :empty-title="t('ueba.emptyWatchlist')">
          <el-table :data="entries.slice((page - 1) * size, page * size).map(value => ({ value }))"><el-table-column prop="value" :label="t('ueba.watchlistValues')" /></el-table>
        </DataTableCard>
        <el-form v-if="props.canWrite" label-position="top" :disabled="busy" style="margin-top:20px">
          <el-form-item :label="t('ueba.append')"><el-input v-model="appendText" type="textarea" :rows="4" :placeholder="t('ueba.appendValue')" /></el-form-item>
          <el-button type="primary" :loading="busy" @click="submitAppend(selected.name)">{{ t('ueba.append') }}</el-button>
        </el-form>
      </template>
    </el-drawer>
  </div>
</template>
