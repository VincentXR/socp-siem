<script setup lang="ts">
// Reusable keyboard-activatable cell for <el-table> rows whose only other
// trigger is @row-click (a pure mouse action). It mirrors the row-activation
// contract introduced by 58f8e76b for div rows: role=button + tabindex=0 +
// @keydown.enter.space. The global `:where([role='button']):focus-visible`
// rule in styles.css supplies the focus ring, so no per-component styling is
// needed. @click.stop keeps a mouse click from also firing the table's
// @row-click, so exactly one activation happens per interaction.
defineProps<{ ariaLabel?: string }>()
const emit = defineEmits<{ activate: [] }>()
</script>

<template>
  <span class="row-activate" role="button" tabindex="0" :aria-label="ariaLabel" @click.stop="emit('activate')" @keydown.enter.space.prevent="emit('activate')"><slot /></span>
</template>
