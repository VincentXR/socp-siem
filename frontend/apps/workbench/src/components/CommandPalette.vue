<script setup lang="ts">
import 'element-plus/es/components/dialog/style/css.mjs'
import 'element-plus/es/components/input/style/css.mjs'
import ElDialog from 'element-plus/es/components/dialog/index.mjs'
import ElInput from 'element-plus/es/components/input/index.mjs'
import { computed, nextTick, ref, watch } from 'vue'
import { MENU_ICONS, type MenuGroup } from '../app/navigation'
import { useI18n } from '../composables/useI18n'

const props = defineProps<{
  modelValue: boolean
  menuGroups: MenuGroup[]
}>()

const emit = defineEmits<{
  (event: 'update:modelValue', value: boolean): void
  (event: 'select', key: string): void
}>()

const { t } = useI18n()

const query = ref('')
const highlighted = ref(0)
const paletteRef = ref<HTMLElement>()

const entries = computed(() => props.menuGroups.flatMap(group =>
  group.items.map(item => ({ key: item.key, label: item.label, icon: item.icon, group: group.group }))))

const matches = computed(() => {
  const needle = query.value.trim().toLowerCase()
  if (!needle) return entries.value
  return entries.value.filter(entry =>
    entry.label.toLowerCase().includes(needle) || entry.group.toLowerCase().includes(needle))
})

watch(matches, () => { highlighted.value = 0 })

watch(highlighted, index => {
  paletteRef.value?.querySelectorAll<HTMLElement>('.command-palette-item')[index]?.scrollIntoView?.({ block: 'nearest' })
})

watch(() => props.modelValue, open => {
  if (!open) return
  query.value = ''
  highlighted.value = 0
  nextTick(() => paletteRef.value?.querySelector<HTMLInputElement>('input')?.focus())
})

function move(step: number): void {
  const total = matches.value.length
  if (total === 0) return
  highlighted.value = (highlighted.value + step + total) % total
}

function pick(key: string): void {
  emit('select', key)
  emit('update:modelValue', false)
}

function commit(): void {
  const entry = matches.value[highlighted.value]
  if (entry) pick(entry.key)
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="t('nav.commandPalette')"
    width="min(560px, 92vw)"
    append-to-body
    @update:model-value="value => emit('update:modelValue', Boolean(value))"
  >
    <div
      ref="paletteRef"
      class="command-palette"
      @keydown.down.prevent="move(1)"
      @keydown.up.prevent="move(-1)"
      @keydown.enter.prevent="commit"
    >
      <el-input v-model="query" :aria-label="t('nav.commandPalette')" :placeholder="t('nav.commandPaletteHint')" />
      <p v-if="matches.length === 0" class="command-palette-empty">{{ t('common.empty') }}</p>
      <ul v-else class="command-palette-list" role="listbox" :aria-label="t('nav.commandPalette')">
        <li
          v-for="(entry, index) in matches"
          :key="entry.key"
          class="command-palette-item"
          :class="{ active: index === highlighted }"
          role="option"
          :aria-selected="index === highlighted"
          :data-menu-key="entry.key"
          @click="pick(entry.key)"
        >
          <span class="icon" aria-hidden="true" v-html="`<svg viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='1.6' stroke-linecap='round' stroke-linejoin='round'>${MENU_ICONS[entry.icon] || ''}</svg>`" />
          <span class="command-palette-label">{{ entry.label }}</span>
          <span class="command-palette-group">{{ entry.group }}</span>
        </li>
      </ul>
    </div>
  </el-dialog>
</template>

<style scoped>
.command-palette { display: flex; flex-direction: column; gap: var(--ns-space-3); }
.command-palette-empty { margin: 0; padding: var(--ns-space-3) 0; color: var(--ns-text-3); font-size: var(--ns-fs-label); text-align: center; }
.command-palette-list { list-style: none; margin: 0; padding: 0; max-height: 320px; overflow: auto; }
.command-palette-item {
  display: flex;
  align-items: center;
  gap: var(--ns-space-2);
  padding: 7px var(--ns-space-2);
  border-radius: var(--ns-radius-sm);
  color: var(--ns-text-2);
  font-size: var(--ns-fs-body);
  cursor: pointer;
}
.command-palette-item .icon { width: 18px; height: 18px; flex: none; display: flex; align-items: center; justify-content: center; }
.command-palette-item .icon svg { width: 16px; height: 16px; }
.command-palette-item:hover { background: var(--ns-hover); color: var(--ns-text); }
.command-palette-item.active { background: var(--ns-accent-subtle); color: var(--ns-accent-fg); }
.command-palette-label { flex: 1; }
.command-palette-group { color: var(--ns-text-3); font-size: var(--ns-fs-meta); }
</style>
