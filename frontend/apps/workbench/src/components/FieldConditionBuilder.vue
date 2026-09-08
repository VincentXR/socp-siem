<script setup lang="ts">
import 'element-plus/es/components/button/style/css.mjs'
import 'element-plus/es/components/input/style/css.mjs'
import 'element-plus/es/components/select/style/css.mjs'
import ElButton from 'element-plus/es/components/button/index.mjs'
import ElInput from 'element-plus/es/components/input/index.mjs'
import { ElOption, ElSelect } from 'element-plus/es/components/select/index.mjs'
import type { FieldDef, ReferenceSet, RuleCondition } from '../api'

const props = withDefaults(defineProps<{
  modelValue: RuleCondition[]
  fields?: FieldDef[]
  referenceSets?: ReferenceSet[]
  title?: string
  addLabel?: string
  emptyHint?: string
  fieldPlaceholder?: string
  valuePlaceholder?: string
}>(), {
  fields: () => [],
  referenceSets: () => [],
  title: '',
  addLabel: 'Add condition',
  emptyHint: 'No conditions',
  fieldPlaceholder: 'Search field',
  valuePlaceholder: 'Value',
})

const emit = defineEmits<{ 'update:modelValue': [RuleCondition[]] }>()

const DEFAULT_OPERATORS = ['eq', 'ne', 'contains', 'startswith', 'endswith', 'regex', 'gt', 'gte', 'lt', 'lte', 'inlist', 'notinlist']

function fieldInfo(fieldName: string): FieldDef | undefined {
  return props.fields.find(field => field.fieldName === fieldName)
}

function fieldType(fieldName: string): string {
  return String(fieldInfo(fieldName)?.fieldType || '').toLowerCase()
}

function fieldOperators(fieldName: string, current = ''): string[] {
  const type = fieldType(fieldName)
  const numeric = ['int', 'integer', 'long', 'float', 'double', 'number'].includes(type)
  const boolean = ['bool', 'boolean'].includes(type)
  const temporal = ['date', 'datetime', 'timestamp', 'time'].includes(type)
  const operators = boolean
    ? ['eq', 'ne']
    : numeric || temporal
      ? ['eq', 'ne', 'gt', 'gte', 'lt', 'lte', 'inlist', 'notinlist']
      : type
        ? ['eq', 'ne', 'contains', 'startswith', 'endswith', 'regex', 'inlist', 'notinlist']
        : DEFAULT_OPERATORS
  return current && !operators.includes(current) ? [current, ...operators] : operators
}

function fieldNames(): Set<string> {
  return new Set(props.fields.map(field => field.fieldName))
}

function updateRow(index: number, patch: Partial<RuleCondition>): void {
  const next = props.modelValue.map((condition, rowIndex) => rowIndex === index ? { ...condition, ...patch } : { ...condition })
  emit('update:modelValue', next)
}

function updateField(index: number, field: string): void {
  const row = props.modelValue[index]
  if (!row) return
  const operators = fieldOperators(field)
  const nextOperator = operators.includes(row.op) ? row.op : operators[0]
  updateRow(index, { field, op: nextOperator })
}

function updateOperator(index: number, operator: string): void {
  updateRow(index, { op: operator })
}

function addCondition(): void {
  emit('update:modelValue', [...props.modelValue.map(condition => ({ ...condition })), { field: '', op: 'eq', value: '' }])
}

function removeCondition(index: number): void {
  emit('update:modelValue', props.modelValue.filter((_, rowIndex) => rowIndex !== index).map(condition => ({ ...condition })))
}

function isReferenceOperator(operator: string): boolean {
  return operator === 'inlist' || operator === 'notinlist'
}

function isBooleanField(fieldName: string): boolean {
  return ['bool', 'boolean'].includes(fieldType(fieldName))
}

function isNumberField(fieldName: string): boolean {
  return ['int', 'integer', 'long', 'float', 'double', 'number'].includes(fieldType(fieldName))
}

function fieldMeta(fieldName: string): string {
  const field = fieldInfo(fieldName)
  if (!field) return fieldName ? 'Field is not in the current dictionary; it will be preserved.' : ''
  return [field.fieldLabel || field.fieldName, field.fieldType, field.description].filter(Boolean).join(' · ')
}
</script>

<template>
  <div class="field-condition-builder">
    <div v-if="title" class="field-condition-builder-head">
      <b>{{ title }}</b>
      <el-button size="small" plain @click="addCondition">{{ addLabel }}</el-button>
    </div>
    <div v-for="(condition, index) in modelValue" :key="index" class="field-condition-row">
      <el-select
        :model-value="condition.field"
        filterable
        allow-create
        default-first-option
        clearable
        :placeholder="fieldPlaceholder"
        @change="updateField(index, String($event ?? ''))"
      >
        <el-option v-if="condition.field && !fieldNames().has(condition.field)" :label="condition.field" :value="condition.field">
          <div class="field-condition-option"><b>{{ condition.field }}</b><small>Not in current dictionary · preserved</small></div>
        </el-option>
        <el-option v-for="field in fields" :key="field.fieldName" :label="field.fieldName" :value="field.fieldName">
          <div class="field-condition-option">
            <b>{{ field.fieldName }}</b>
            <small>{{ field.fieldLabel || field.fieldType }} · {{ field.fieldType }}<span v-if="field.aggregatable"> · aggregate</span></small>
          </div>
        </el-option>
      </el-select>
      <el-select :model-value="condition.op" @change="updateOperator(index, String($event ?? ''))">
        <el-option v-for="operator in fieldOperators(condition.field, condition.op)" :key="operator" :label="operator" :value="operator" />
      </el-select>
      <el-select v-if="isReferenceOperator(condition.op)" :model-value="condition.value" filterable allow-create default-first-option clearable :placeholder="valuePlaceholder" @change="updateRow(index, { value: String($event ?? '') })">
        <el-option v-if="condition.value && !referenceSets.some(refset => refset.name === condition.value)" :label="condition.value" :value="condition.value" />
        <el-option v-for="refset in referenceSets" :key="refset.id" :label="refset.name" :value="refset.name">
          <div class="field-condition-option"><b>{{ refset.name }}</b><small>{{ refset.entries.length }} entries · {{ refset.description }}</small></div>
        </el-option>
      </el-select>
      <el-select v-else-if="isBooleanField(condition.field)" :model-value="condition.value" clearable :placeholder="valuePlaceholder" @change="updateRow(index, { value: String($event ?? '') })">
        <el-option label="true" value="true" />
        <el-option label="false" value="false" />
      </el-select>
      <el-input v-else :model-value="condition.value" :type="isNumberField(condition.field) ? 'number' : 'text'" :placeholder="valuePlaceholder" @update:model-value="value => updateRow(index, { value: String(value ?? '') })" />
      <el-button link type="danger" :aria-label="`Remove condition ${index + 1}`" @click="removeCondition(index)">×</el-button>
      <small v-if="fieldMeta(condition.field)" class="field-condition-meta">{{ fieldMeta(condition.field) }}</small>
    </div>
    <div v-if="!modelValue.length" class="field-condition-empty">{{ emptyHint }}</div>
    <el-button v-if="!title" size="small" plain @click="addCondition">{{ addLabel }}</el-button>
  </div>
</template>

<style scoped>
.field-condition-builder { display: grid; gap: 8px; }
.field-condition-builder-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.field-condition-builder-head b { color: var(--ns-text-2); font-size: 12px; }
.field-condition-row { display: grid; grid-template-columns: minmax(150px, .9fr) 120px minmax(140px, 1fr) auto; gap: 6px; align-items: center; }
.field-condition-row :deep(.el-input), .field-condition-row :deep(.el-select) { width: 100%; }
.field-condition-option { display: flex; flex-direction: column; gap: 2px; line-height: 1.25; }
.field-condition-option small { color: var(--ns-text-3); font-size: 10px; }
.field-condition-meta { grid-column: 1 / -1; margin: -3px 0 2px; color: var(--ns-text-3); font-size: 10px; line-height: 1.35; }
.field-condition-empty { padding: 10px; border: 1px dashed var(--ns-border); border-radius: var(--ns-radius-sm); color: var(--ns-text-3); font-size: 11px; text-align: center; }
@media (max-width: 640px) { .field-condition-row { grid-template-columns: 1fr; } .field-condition-meta { grid-column: auto; } }
</style>
