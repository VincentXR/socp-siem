<script setup lang="ts">
/**
 * One numbered section of a create/edit form.
 *
 * Extracted from the rule editor, which was the only form in the workbench with
 * a deliberate structure: a number, a heading, and a sentence saying what the
 * section is for. Every other form stacked bare fields, so a reader had no way
 * to tell which of a dozen inputs belonged together or why.
 */
defineProps<{
  /** Displayed as-is, e.g. "01". Omit for a single-section form. */
  index?: string
  title: string
  hint?: string
}>()
</script>

<template>
  <section class="form-section">
    <div class="form-section-head">
      <span v-if="index" class="form-section-index">{{ index }}</span>
      <div>
        <h3>{{ title }}</h3>
        <p v-if="hint">{{ hint }}</p>
      </div>
    </div>
    <slot />
  </section>
</template>

<style scoped>
.form-section {
  padding: 16px 0;
  border-top: 1px solid var(--ns-border);
}

.form-section:first-child {
  padding-top: 0;
  border-top: 0;
}

.form-section-head {
  display: flex;
  gap: 10px;
  margin-bottom: 14px;
}

.form-section-index {
  display: grid;
  place-items: center;
  flex: none;
  width: 26px;
  height: 26px;
  border: 1px solid var(--ns-accent-line);
  border-radius: 7px;
  background: var(--ns-accent-subtle);
  color: var(--ns-accent-fg);
  font-family: var(--ns-font-mono);
  font-size: 12px;
  font-weight: 600;
}

.form-section-head h3 {
  margin: 0;
  color: var(--ns-text);
  font-size: 14px;
  font-weight: 650;
}

.form-section-head p {
  margin: 4px 0 0;
  color: var(--ns-text-3);
  font-size: 12px;
  line-height: 1.5;
}
</style>
