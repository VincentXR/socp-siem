<script setup lang="ts">
/**
 * Field grid for forms.
 *
 * The rule editor let short fields sit several to a row and dropped to two then
 * one column as the window narrowed. Every other form was one long stack, so a
 * six-field dialog was six full-width rows and a two-field dialog looked empty.
 * The column count is explicit rather than automatic because only the form
 * knows which fields are short.
 */
withDefaults(defineProps<{ columns?: 1 | 2 | 3 | 4 }>(), { columns: 2 })
</script>

<template>
  <div class="form-grid" :class="`cols-${columns}`">
    <slot />
  </div>
</template>

<style scoped>
.form-grid {
  display: grid;
  gap: 10px 12px;
}

.form-grid.cols-1 {
  grid-template-columns: repeat(1, minmax(0, 1fr));
}

.form-grid.cols-2 {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.form-grid.cols-3 {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.form-grid.cols-4 {
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

/* Controls fill their cell: a 180px select in a 4-column grid leaves a gap that
   reads as a layout mistake rather than a deliberate width. */
.form-grid :deep(.el-form-item) {
  margin-bottom: 0;
}

.form-grid :deep(.el-select),
.form-grid :deep(.el-input),
.form-grid :deep(.el-input-number) {
  width: 100%;
}

@media (max-width: 1000px) {
  .form-grid.cols-3,
  .form-grid.cols-4 {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 640px) {
  .form-grid.cols-2,
  .form-grid.cols-3,
  .form-grid.cols-4 {
    grid-template-columns: repeat(1, minmax(0, 1fr));
  }
}
</style>
