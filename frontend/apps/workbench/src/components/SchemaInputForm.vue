<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElForm, ElFormItem } from 'element-plus/es/components/form/index.mjs'
import ElButton from 'element-plus/es/components/button/index.mjs'
import ElInput from 'element-plus/es/components/input/index.mjs'
import ElInputNumber from 'element-plus/es/components/input-number/index.mjs'
import { ElOption, ElSelect } from 'element-plus/es/components/select/index.mjs'
import 'element-plus/es/components/button/style/css.mjs'
import 'element-plus/es/components/form/style/css.mjs'
import 'element-plus/es/components/input/style/css.mjs'
import 'element-plus/es/components/input-number/style/css.mjs'
import 'element-plus/es/components/select/style/css.mjs'
import ActionFeedback from './ActionFeedback.vue'
import { useI18n } from '../composables/useI18n'
import { tOr } from '../utils/i18nLabel'
import { validateSchemaInput } from '../utils/schemaValidation'

/**
 * Renders a backend-supplied JSON Schema as a form.
 *
 * `object` and `array` used to render as raw JSON textareas. That asked an
 * operator to know the serialization format of a field, and it reported a typo
 * at submit rather than at the field. They now render as a key/value editor and
 * a repeatable list; the raw view survives only as a collapsed fallback for
 * shapes the typed editors cannot express.
 */
const props = defineProps<{ schema: unknown; disabled?: boolean }>()
const model = defineModel<Record<string, unknown>>({ default: () => ({}) })
const emit = defineEmits<{ valid: [value: boolean] }>()
const { t } = useI18n()
const raw = ref('')
const jsonDrafts = ref<Record<string, string>>({})
const errors = ref<Record<string, string>>({})

type PairRow = { key: string; value: string }
const objectRows = ref<Record<string, PairRow[]>>({})
const arrayRows = ref<Record<string, string[]>>({})

const schema = computed(() => props.schema && typeof props.schema === 'object' ? props.schema as Record<string, unknown> : {})
const required = computed(() => Array.isArray(schema.value.required) ? schema.value.required.map(String) : [])
const fields = computed(() => Object.entries((schema.value.properties || {}) as Record<string, Record<string, unknown>>))
const validation = computed(() => validateSchemaInput(model.value, props.schema))

/** The label the operator sees, so a message can point at a field by name. */
const titles = computed<Record<string, string>>(() => Object.fromEntries(fields.value.map(([key, field]) => [key, String(field.title || key)])))

/** Top-level field a JSONPath belongs to, or `$` when nothing owns it. */
function issueFieldKey(path: string): string {
  const rest = path.startsWith('$.') ? path.slice(2) : path.replace(/^\$/, '')
  return rest.split(/[.[]/)[0] || '$'
}

function issueMessage(issue: { path: string; code: string }): string {
  const message = tOr(t, `forms.schemaValidation.${issue.code}`, t('forms.schemaValidation.schema'))
  const key = issueFieldKey(issue.path)
  const detail = issue.path.startsWith(`$.${key}`) ? issue.path.slice(key.length + 2).replace(/^\./, '') : ''
  return [titles.value[key] ?? key, detail, message].filter(Boolean).join(' · ')
}

const knownFields = computed(() => new Set(fields.value.map(([key]) => key)))

/** Issues mapped onto the control that can fix them. */
const fieldIssues = computed<Record<string, string>>(() => {
  const grouped: Record<string, string[]> = {}
  for (const issue of validation.value) {
    const key = issueFieldKey(issue.path)
    if (!knownFields.value.has(key)) continue
    ;(grouped[key] ??= []).push(issueMessage(issue))
  }
  return Object.fromEntries(Object.entries(grouped).map(([key, messages]) => [key, messages.join(' · ')]))
})

/** Issues no control can carry (undeclared keys, whole-object failures). */
const summaryCount = computed(() => validation.value.filter(issue => !knownFields.value.has(issueFieldKey(issue.path))).length)
watch([validation, errors], () => emit('valid', !validation.value.length && !Object.keys(errors.value).length), { immediate: true, deep: true })
watch(model, value => {
  if (errors.value.$) return
  try { if (JSON.stringify(JSON.parse(raw.value)) === JSON.stringify(value)) return } catch { /* initialize */ }
  raw.value = JSON.stringify(value, null, 2)
}, { immediate: true, deep: true })

/** Seed the typed editors once per field so a re-render cannot discard an edit. */
watch(fields, entries => {
  for (const [key, field] of entries) {
    if (field.type === 'object' && !objectRows.value[key]) {
      const current = model.value[key]
      objectRows.value[key] = current && typeof current === 'object' && !Array.isArray(current)
        ? Object.entries(current as Record<string, unknown>).map(([k, v]) => ({ key: k, value: v == null ? '' : String(v) }))
        : []
    }
    if (field.type === 'array' && !arrayRows.value[key]) {
      const current = model.value[key]
      arrayRows.value[key] = Array.isArray(current) ? current.map(item => item == null ? '' : String(item)) : []
    }
  }
}, { immediate: true })

function update(key: string, value: unknown) { model.value = { ...model.value, [key]: value } }

/** A 500-character one-line box is unusable; long text gets a real textarea. */
function isLongText(field: Record<string, unknown>): boolean {
  return Number(field.maxLength) > 200
}

/** Keep the value typed where it can be: `true` stays a boolean, `42` a number. */
function coerce(text: string): unknown {
  const trimmed = text.trim()
  if (trimmed === '') return ''
  try { return JSON.parse(trimmed) } catch { return text }
}

function commitObject(key: string) {
  const next: Record<string, unknown> = {}
  for (const row of objectRows.value[key] ?? []) {
    const name = row.key.trim()
    if (name) next[name] = coerce(row.value)
  }
  update(key, next)
}

function addObjectRow(key: string) {
  (objectRows.value[key] ??= []).push({ key: '', value: '' })
}

function removeObjectRow(key: string, index: number) {
  objectRows.value[key]?.splice(index, 1)
  commitObject(key)
}

function commitArray(key: string) {
  const items = (arrayRows.value[key] ?? []).map(value => value.trim()).filter(value => value.length > 0)
  update(key, items)
}

function addArrayRow(key: string) {
  (arrayRows.value[key] ??= []).push('')
}

function removeArrayRow(key: string, index: number) {
  arrayRows.value[key]?.splice(index, 1)
  commitArray(key)
}

function updateJson(key: string, value: string) {
  const label = key === '$' ? t('forms.advanced') : titles.value[key] ?? key
  if (key === '$') raw.value = value
  else jsonDrafts.value[key] = value
  let parsed: unknown
  try {
    parsed = JSON.parse(value) as unknown
  } catch {
    // Parser output is English diagnostics text; the operator gets the localized
    // "this is not valid JSON" for this field instead of `Unexpected token`.
    errors.value[key] = t('soar.invalidJson', { label })
    return
  }
  if (key === '$') {
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) { errors.value[key] = t('soar.jsonObjectRequired', { label }); return }
    jsonDrafts.value = {}
    errors.value = {}
    model.value = parsed as Record<string, unknown>
    return
  }
  const type = fields.value.find(([name]) => name === key)?.[1].type
  if (type === 'array' && !Array.isArray(parsed)) { errors.value[key] = t('forms.schemaValidation.type'); return }
  if (type === 'object' && (!parsed || typeof parsed !== 'object' || Array.isArray(parsed))) { errors.value[key] = t('soar.jsonObjectRequired', { label }); return }
  update(key, parsed)
  delete errors.value[key]
}
</script>
<template>
  <el-form label-position="top" :disabled="disabled">
    <el-form-item v-for="[key, field] in fields" :key="key" :label="String(field.title || key)" :required="required.includes(key)" :error="errors[key] || fieldIssues[key]">
      <el-select v-if="Array.isArray(field.enum)" :model-value="model[key] as string" clearable :filterable="(field.enum as unknown[]).length > 8" @change="value => update(key, value)"><el-option v-for="(value, index) in field.enum" :key="index" :label="String(value)" :value="value as string" /></el-select>
      <el-select v-else-if="field.type === 'boolean'" :model-value="model[key] as boolean | undefined" clearable @change="value => update(key, value)"><el-option :label="t('common.yes')" :value="true" /><el-option :label="t('common.no')" :value="false" /></el-select>
      <el-input-number v-else-if="field.type === 'number' || field.type === 'integer'" :model-value="model[key] as number | undefined" :precision="field.type === 'integer' ? 0 : undefined" :min="field.minimum as number | undefined" :max="field.maximum as number | undefined" @change="value => update(key, value)" />

      <div v-else-if="field.type === 'object'" class="schema-rows">
        <div v-for="(row, index) in objectRows[key]" :key="index" class="schema-row">
          <el-input v-model="row.key" :placeholder="t('forms.entryKey')" @update:model-value="commitObject(key)" />
          <el-input v-model="row.value" :placeholder="t('forms.entryValue')" @update:model-value="commitObject(key)" />
          <el-button link type="danger" size="small" :disabled="disabled" @click="removeObjectRow(key, index)">{{ t('common.delete') }}</el-button>
        </div>
        <el-button v-if="!disabled" link type="primary" size="small" @click="addObjectRow(key)">+ {{ t('forms.addEntry') }}</el-button>
      </div>

      <div v-else-if="field.type === 'array'" class="schema-rows">
        <div v-for="(_, index) in arrayRows[key]" :key="index" class="schema-row">
          <el-input v-model="arrayRows[key][index]" @update:model-value="commitArray(key)" />
          <el-button link type="danger" size="small" :disabled="disabled" @click="removeArrayRow(key, index)">{{ t('common.delete') }}</el-button>
        </div>
        <el-button v-if="!disabled" link type="primary" size="small" @click="addArrayRow(key)">+ {{ t('forms.addEntry') }}</el-button>
      </div>

      <el-input v-else-if="isLongText(field)" :model-value="String(model[key] ?? '')" type="textarea" :rows="4" :maxlength="field.maxLength as number | undefined" show-word-limit @update:model-value="value => update(key, value)" />
      <el-input v-else :model-value="String(model[key] ?? '')" :maxlength="field.maxLength as number | undefined" @update:model-value="value => update(key, value)" />
      <small v-if="field.description">{{ field.description }}</small>
      <small v-if="field.pattern" class="schema-constraint">{{ t('forms.serverPattern', { pattern: String(field.pattern) }) }}</small>
    </el-form-item>
    <details><summary>{{ t('forms.advanced') }}</summary><p>{{ t('forms.advancedHint') }}</p><el-input v-model="raw" type="textarea" :rows="8" @input="value => updateJson('$', value)" /><ActionFeedback :error="errors.$" /></details>
    <ActionFeedback v-if="summaryCount" :error="`${t('forms.invalidInput')} · ${summaryCount}`" />
  </el-form>
</template>
<style scoped>
.schema-rows {
  display: flex;
  flex-direction: column;
  gap: 6px;
  width: 100%;
}

.schema-row {
  display: flex;
  align-items: center;
  gap: 6px;
}

.schema-row :deep(.el-input) {
  flex: 1;
  min-width: 0;
}

/* A pattern is a machine constraint, not guidance. Render it as one so it does
   not read as the sentence that explains the field. */
.schema-constraint {
  font-family: var(--ns-font-mono);
  color: var(--ns-text-3);
}
</style>
