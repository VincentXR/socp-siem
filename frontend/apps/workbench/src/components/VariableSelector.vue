<script setup lang="ts">
import 'element-plus/es/components/select/style/css.mjs'
import { ElOption, ElSelect } from 'element-plus/es/components/select/index.mjs'
import { useI18n } from '../composables/useI18n'

export interface VariableOption {
  value: string
  label: string
  kind?: string
  description?: string
}

const props = withDefaults(defineProps<{
  modelValue: string
  variables?: VariableOption[]
  placeholder?: string
  disabled?: boolean
}>(), {
  variables: () => [],
  placeholder: 'Select a variable',
  disabled: false,
})

const emit = defineEmits<{ 'update:modelValue': [string] }>()
const { t } = useI18n()
</script>

<template>
  <el-select
    :model-value="props.modelValue"
    :disabled="props.disabled"
    filterable
    allow-create
    default-first-option
    clearable
    :placeholder="props.placeholder"
    @update:model-value="value => emit('update:modelValue', String(value ?? ''))"
  >
    <el-option v-if="props.modelValue && !props.variables.some(variable => variable.value === props.modelValue)" :label="props.modelValue" :value="props.modelValue">
      <div class="variable-option"><b>{{ props.modelValue }}</b><small>{{ t('common.customValuePreserved') }}</small></div>
    </el-option>
    <el-option v-for="variable in props.variables" :key="variable.value" :label="variable.label" :value="variable.value">
      <div class="variable-option"><b>{{ variable.label }}</b><small>{{ variable.kind || t('common.variable') }}<span v-if="variable.description"> · {{ variable.description }}</span></small></div>
    </el-option>
  </el-select>
</template>

<style scoped>
.variable-option { display: flex; flex-direction: column; gap: 2px; line-height: 1.25; }
.variable-option small { color: var(--ns-text-3); font-size: 10px; }
</style>
