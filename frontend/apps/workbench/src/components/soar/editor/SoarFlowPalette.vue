<script setup lang="ts">
import { NODE_TYPE_ORDER, SOAR_NODE_REGISTRY } from './nodeRegistry'
import { PALETTE_DATA_TYPE } from './types'
import type { SoarFlowApi } from './useDefinitionFlow'
import { useI18n } from '../../../composables/useI18n'

const props = withDefaults(defineProps<{ flow: SoarFlowApi; readOnly?: boolean }>(), { readOnly: false })

const { t } = useI18n()

const items = NODE_TYPE_ORDER.map(type => SOAR_NODE_REGISTRY[type])

function labelOf(item: (typeof items)[number]): string {
  const translated = t(item.labelKey)
  return translated && translated !== item.labelKey ? translated : item.label
}

function descriptionOf(item: (typeof items)[number]): string {
  const translated = t(item.descriptionKey)
  return translated && translated !== item.descriptionKey ? translated : item.description
}

function createFromClick(type: string): void {
  if (!props.flow || props.readOnly) return
  props.flow.addAtViewportCenter(type)
}

function onDragStart(event: DragEvent, type: string): void {
  if (props.readOnly || !event.dataTransfer) return
  event.dataTransfer.setData(PALETTE_DATA_TYPE, type)
  event.dataTransfer.effectAllowed = 'copy'
}

function onDragEnd(): void {
  // nothing to clean up; the canvas drop handler owns creation
}
</script>

<template>
  <aside class="soar-flow-palette" :aria-label="t('soar.paletteTitle')">
    <div class="soar-v2-panel-title">{{ t('soar.paletteTitle') }}</div>
    <button
      v-for="item in items"
      :key="item.type"
      type="button"
      class="soar-palette-item"
      :class="[`tone-${item.tone}`, { disabled: props.readOnly || item.comingSoon }]"
      :disabled="props.readOnly || item.comingSoon"
      :draggable="!props.readOnly && !item.comingSoon"
      @click="createFromClick(item.type)"
      @dragstart="onDragStart($event, item.type)"
      @dragend="onDragEnd"
    >
      <span class="soar-palette-dot" />
      <span class="soar-palette-text">
        <strong>{{ labelOf(item) }}</strong>
        <small>{{ descriptionOf(item) }}</small>
      </span>
      <span v-if="item.comingSoon" class="soar-palette-soon">{{ t('soar.comingSoon') }}</span>
    </button>
    <div class="soar-flow-connect-help">
      {{ t('soar.canvasHint') }}
    </div>
  </aside>
</template>

<style scoped>
.soar-flow-palette {
  min-width: 0;
  padding: 10px;
  border-right: 1px solid var(--ns-border);
  background: var(--ns-bg-subtle);
}

.soar-palette-item {
  display: flex;
  width: 100%;
  gap: 7px;
  align-items: center;
  box-sizing: border-box;
  padding: 7px 6px;
  border: 1px solid transparent;
  border-radius: 5px;
  background: transparent;
  color: var(--ns-text);
  font: inherit;
  text-align: left;
  cursor: pointer;
}

.soar-palette-item:hover:not(.disabled) {
  border-color: var(--ns-border);
  background: var(--ns-bg);
}

.soar-palette-item.disabled {
  color: var(--ns-text-3);
  cursor: not-allowed;
  opacity: 0.62;
}

.soar-palette-dot {
  width: 8px;
  height: 8px;
  flex: 0 0 8px;
  border-radius: 50%;
  background: var(--soar-node-color, var(--ns-accent));
}

.soar-palette-text {
  min-width: 0;
  flex: 1;
}

.soar-palette-item strong,
.soar-palette-item small {
  display: block;
}

.soar-palette-item strong {
  font-size: 11px;
  font-weight: 600;
}

.soar-palette-item small {
  overflow: hidden;
  color: var(--ns-text-3);
  font-size: 10px;
  line-height: 1.25;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.soar-palette-soon {
  flex: 0 0 auto;
  padding: 1px 6px;
  border: 1px solid color-mix(in srgb, var(--ns-warning) 45%, transparent);
  border-radius: 8px;
  color: var(--ns-warning);
  font-size: 8px;
  font-weight: 700;
  letter-spacing: 0.03em;
  text-transform: uppercase;
}

.soar-flow-connect-help {
  margin: 16px 4px 0;
  color: var(--ns-text-3);
  font-size: 10px;
  line-height: 1.5;
}
</style>
