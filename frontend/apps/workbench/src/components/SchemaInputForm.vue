<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElForm, ElFormItem } from 'element-plus/es/components/form/index.mjs'
import ElInput from 'element-plus/es/components/input/index.mjs'
import ElInputNumber from 'element-plus/es/components/input-number/index.mjs'
import { ElOption, ElSelect } from 'element-plus/es/components/select/index.mjs'
import 'element-plus/es/components/form/style/css.mjs'
import 'element-plus/es/components/input/style/css.mjs'
import 'element-plus/es/components/input-number/style/css.mjs'
import 'element-plus/es/components/select/style/css.mjs'
import ActionFeedback from './ActionFeedback.vue'
import { useI18n } from '../composables/useI18n'
import { validateSchemaInput } from '../utils/schemaValidation'
const props = defineProps<{ schema: unknown; disabled?: boolean }>()
const model = defineModel<Record<string, unknown>>({ default: () => ({}) })
const emit = defineEmits<{ valid: [value: boolean] }>()
const { t } = useI18n()
const raw = ref('')
const jsonDrafts = ref<Record<string, string>>({})
const errors = ref<Record<string, string>>({})
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
function update(key: string, value: unknown) { model.value = { ...model.value, [key]: value } }
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
      <el-input v-else-if="field.type === 'object' || field.type === 'array'" :model-value="jsonDrafts[key] ?? JSON.stringify(model[key] ?? (field.type === 'array' ? [] : {}), null, 2)" type="textarea" :rows="4" @update:model-value="value => updateJson(key, value)" />
      <el-input v-else :model-value="String(model[key] ?? '')" :maxlength="field.maxLength as number | undefined" @update:model-value="value => update(key, value)" />
      <small v-if="field.description">{{ field.description }}</small><small v-if="field.pattern">{{ t('forms.serverPattern', { pattern: String(field.pattern) }) }}</small><ActionFeedback :error="errors[key]" />
    </el-form-item>
    <details><summary>{{ t('forms.advanced') }}</summary><el-input v-model="raw" type="textarea" :rows="8" @input="value => updateJson('$', value)" /><ActionFeedback :error="errors.$" /></details>
    <ActionFeedback v-for="(issue, index) in validation" :key="index" :error="`${issue.path}: ${t('forms.schemaValidation.' + issue.code)}`" />
  </el-form>
</template>
