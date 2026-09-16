<script setup lang="ts">
import { ElFormItem } from 'element-plus/es/components/form/index.mjs'
import 'element-plus/es/components/form/style/css.mjs'

/**
 * One labelled field: label, required marker, unit, help text, and the error
 * for this field alone.
 *
 * The workbench had no field wrapper, so each form decided for itself whether to
 * show a required marker, whether to explain the field, and how to report a
 * problem. Errors surfaced as one banner above the form, which on a long form
 * tells the operator that something is wrong without telling them where.
 */
withDefaults(
  defineProps<{
    label: string
    required?: boolean
    /** One sentence under the control: what to put here, in the operator's terms. */
    hint?: string
    /** Shown on this field only. Prefer this over a form-level banner. */
    error?: string
    /** Suffix such as "秒" or "条", rendered next to the control. */
    unit?: string
    /** Span every column of the surrounding grid. Use for textareas and JSON. */
    full?: boolean
  }>(),
  { required: false, full: false },
)
</script>

<template>
  <el-form-item
    :label="label"
    :required="required"
    :error="error"
    :class="{ 'field-full': full }"
  >
    <div class="field-control">
      <slot />
      <span v-if="unit" class="field-unit">{{ unit }}</span>
    </div>
    <p v-if="hint" class="field-hint">{{ hint }}</p>
  </el-form-item>
</template>

<style scoped>
.field-full {
  grid-column: 1 / -1;
}

.field-control {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  min-width: 0;
}

.field-control > :not(.field-unit) {
  flex: 1;
  min-width: 0;
}

.field-unit {
  flex: none;
  color: var(--ns-text-3);
  font-size: 12px;
}

.field-hint {
  margin: 6px 0 0;
  color: var(--ns-text-3);
  font-size: 12px;
  line-height: 1.5;
}
</style>
