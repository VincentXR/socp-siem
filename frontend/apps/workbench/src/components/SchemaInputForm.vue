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
  if (key === '$') raw.value = value
  else jsonDrafts.value[key] = value
  try {
    const parsed: unknown = JSON.parse(value)
    if (key === '$') {
      if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) throw new Error('Expected an object')
      jsonDrafts.value = {}
      errors.value = {}
      model.value = parsed as Record<string, unknown>
    } else {
      const type = fields.value.find(([name]) => name === key)?.[1].type
      if (type === 'array' && !Array.isArray(parsed)) throw new Error('Expected an array')
      if (type === 'object' && (!parsed || typeof parsed !== 'object' || Array.isArray(parsed))) throw new Error('Expected an object')
      update(key, parsed)
    }
    delete errors.value[key]
  } catch (failure) { errors.value[key] = String(failure) }
}
</script>
<template>
  <el-form label-position="top" :disabled="disabled">
    <el-form-item v-for="[key, field] in fields" :key="key" :label="String(field.title || key)" :required="required.includes(key)">
      <el-select v-if="Array.isArray(field.enum)" :model-value="model[key] as string" clearable @change="value => update(key, value)"><el-option v-for="(value, index) in field.enum" :key="index" :label="String(value)" :value="value as string" /></el-select>
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

      <el-input v-else :model-value="String(model[key] ?? '')" :maxlength="field.maxLength as number | undefined" @update:model-value="value => update(key, value)" />
      <small v-if="field.description">{{ field.description }}</small>
      <small v-if="field.pattern" class="schema-constraint">{{ t('forms.serverPattern', { pattern: String(field.pattern) }) }}</small>
      <ActionFeedback :error="errors[key]" />
    </el-form-item>
    <details><summary>{{ t('forms.advanced') }}</summary><p>{{ t('forms.advancedHint') }}</p><el-input v-model="raw" type="textarea" :rows="8" @input="value => updateJson('$', value)" /><ActionFeedback :error="errors.$" /></details>
    <ActionFeedback v-for="(issue, index) in validation" :key="index" :error="`${issue.path}: ${t('forms.schemaValidation.' + issue.code)}`" />
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
