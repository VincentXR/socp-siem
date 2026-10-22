<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { ECharts } from 'echarts/core'
import { loadEcharts } from '../lib/echarts'
import { translate } from '../i18n'
import { useI18n } from '../composables/useI18n'

/**
 * 近 7 日告警趋势折线图（概览页/态势页共用）。
 * props.data: { "2026-08-03": 0, ... }（日期 → 条数）
 * 默认主题随工作台切换；console 是概览深色面板的专用配色。
 */
const props = withDefaults(defineProps<{ data?: Record<string, number>; variant?: 'overview' | 'situation' | 'console' }>(), {
  variant: 'overview',
})
const { locale } = useI18n()

const el = ref<HTMLElement>()
const state = ref<'loading' | 'ready' | 'empty' | 'error'>('empty')
const entries = computed(() => Object.entries(props.data ?? {})
  .filter(([, value]) => Number.isFinite(value))
  .sort(([left], [right]) => left.localeCompare(right)))
const accessibleSummary = computed(() => `${translate('common.alertCount')}: ${entries.value.map(([day, count]) => `${day}: ${count}`).join('; ')}`)
let chart: ECharts | null = null
let renderToken = 0
let themeObserver: MutationObserver | null = null
let resizeObserver: ResizeObserver | null = null
let motionPreference: MediaQueryList | undefined

function cssColor(variable: string, fallback: string): string {
  const value = getComputedStyle(document.documentElement).getPropertyValue(variable).trim()
  return value || fallback
}

function themeColor(key: string): string {
  const dark = document.documentElement.getAttribute('data-theme') === 'dark'
  const map: Record<string, string> = {
    label: dark ? '#e6edf3' : '#1f2328',
    axis: dark ? '#8b949e' : '#57606a',
    accent: cssColor('--ns-accent', dark ? '#8fa6ff' : '#1e46d6'),
    success: cssColor('--ns-success', dark ? '#3fb950' : '#15803d'),
  }
  return map[key] ?? '#57606a'
}

async function render() {
  const token = ++renderToken
  if (!el.value) return
  const rows = entries.value
  if (rows.length === 0) {
    chart?.clear()
    state.value = 'empty'
    return
  }
  state.value = 'loading'
  let echarts: Awaited<ReturnType<typeof loadEcharts>>
  try {
    echarts = await loadEcharts()
  } catch {
    if (token === renderToken) {
      chart?.clear()
      state.value = 'error'
    }
    return
  }
  if (token !== renderToken || !el.value) return
  if (!chart || chart.isDisposed()) chart = echarts.init(el.value, 'socp')
  const dark = document.documentElement.getAttribute('data-theme') === 'dark'
  const days = rows.map(([day]) => day)
  const vals = rows.map(([, count]) => count)
  const animation = !motionPreference?.matches
  if (props.variant === 'console') {
    chart.setOption({
      // A stable operational chart, without a looping pulse or an invented live signal.
      animation: false,
      grid: { left: 8, right: 12, top: 14, bottom: 8, containLabel: true },
      xAxis: {
        type: 'category', data: days, boundaryGap: false,
        axisLabel: { fontSize: 12, color: '#9cb0ce', margin: 12, hideOverlap: true, formatter: (value: string) => value.slice(5) },
        axisLine: { lineStyle: { color: 'rgba(148,180,223,.22)' } },
        axisTick: { show: false },
      },
      yAxis: {
        type: 'value', min: 0, minInterval: 1, splitNumber: 3,
        axisLabel: { fontSize: 12, color: '#9cb0ce', margin: 12 },
        axisLine: { show: false }, axisTick: { show: false },
        splitLine: { lineStyle: { color: 'rgba(148,180,223,.13)', type: 'dashed' } },
      },
      tooltip: {
        trigger: 'axis', renderMode: 'richText', confine: true,
        backgroundColor: 'rgba(11,23,45,.97)', borderColor: '#2c4963',
        textStyle: { color: '#e6f1ff', fontSize: 12 },
        padding: [10, 14],
        axisPointer: { type: 'line', lineStyle: { color: '#7fadd1', type: 'dashed' } },
        formatter: (points: Array<{ axisValue: string; value: number }>) => {
          const point = points[0]
          return point ? `${point.axisValue}\n${new Intl.NumberFormat(locale.value).format(point.value)} ${translate('common.alertCount')}` : ''
        },
      },
      series: [{
        type: 'line', smooth: false, showSymbol: false, symbol: 'circle', symbolSize: 7,
        data: vals,
        lineStyle: { color: '#65d5f6', width: 2.5 },
        itemStyle: { color: '#83dffc', borderColor: '#102039', borderWidth: 2 },
        emphasis: { focus: 'series' },
        areaStyle: { color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
          { offset: 0, color: 'rgba(93,207,244,.3)' },
          { offset: 0.6, color: 'rgba(77,137,245,.1)' },
          { offset: 1, color: 'rgba(77,137,245,0)' },
        ]) },
      }],
    }, true)
    state.value = 'ready'
    return
  }
  if (props.variant === 'situation') {
    // 态势页：带坐标轴、蓝色实线（与旧 sitTrend 图表一致）
    chart.setOption({
      animation,
      grid: { left: 36, right: 12, top: 22, bottom: 24 }, tooltip: { trigger: 'axis' },
      xAxis: { type: 'category', data: days.map((k) => k.slice(5)), axisLabel: { fontSize: 10 } },
      yAxis: { type: 'value', axisLabel: { fontSize: 10 } },
      series: [{
        type: 'line', smooth: true, showSymbol: false, data: vals,
        lineStyle: { color: themeColor('accent'), width: 2 },
        areaStyle: { color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [{ offset: 0, color: dark ? 'rgba(143,166,255,.32)' : 'rgba(30,70,214,.24)' }, { offset: 1, color: 'rgba(0,0,0,0)' }]) },
      }],
    }, true)
    state.value = 'ready'
    return
  }
  chart.setOption({
    animation,
    grid: { left: 10, right: 10, top: 24, bottom: 6, containLabel: false },
    xAxis: {
      type: 'category', data: days,
      axisLabel: { fontSize: 10.5, color: themeColor('label'), formatter: (v: string) => v.slice(5) },
      axisLine: { lineStyle: { color: themeColor('axis') } },
      axisTick: { show: false },
    },
    yAxis: { type: 'value', show: false, minInterval: 1 },
    tooltip: {
      trigger: 'axis',
      formatter: (ps: Array<{ axisValue: string; value: number }>) => {
        const p = ps[0]
        return `${p.axisValue}<br/><b>${p.value}</b> ${translate('common.alertCount')}`
      },
    },
    series: [{
      type: 'line', smooth: true, symbol: 'circle', symbolSize: 6,
      data: vals,
      lineStyle: { color: themeColor('axis'), width: 0 },
      itemStyle: { color: themeColor('label') },
      label: {
        show: true, position: 'top', fontSize: 10.5, color: themeColor('label'),
        formatter: (p: { value: number }) => (p.value > 0 ? String(p.value) : ''),
      },
      areaStyle: {
        color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
          { offset: 0, color: dark ? 'rgba(143,166,255,.34)' : 'rgba(30,70,214,.22)' },
          { offset: 1, color: 'rgba(0,0,0,0)' },
        ]),
      },
      markPoint: {
        data: [{ type: 'max', name: translate('common.peak') }],
        symbolSize: 44, label: { fontSize: 10, color: cssColor('--ns-on-success', '#fff'), formatter: '{c}' },
        itemStyle: { color: themeColor('success') },
      },
    }],
  }, true)
  state.value = 'ready'
}

function redraw() { void render() }

onMounted(() => {
  motionPreference = window.matchMedia?.('(prefers-reduced-motion: reduce)')
  motionPreference?.addEventListener?.('change', redraw)
  void render()
  // 主题切换时重绘（监听 html[data-theme]）
  themeObserver = new MutationObserver(() => { void render() })
  themeObserver.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] })
  if (typeof ResizeObserver !== 'undefined' && el.value) {
    resizeObserver = new ResizeObserver(() => { chart?.resize() })
    resizeObserver.observe(el.value)
  }
})

watch(() => props.data, () => { void render() }, { deep: true })
watch(() => props.variant, redraw)
watch(locale, () => { void render() })

onBeforeUnmount(() => {
  renderToken++
  themeObserver?.disconnect()
  themeObserver = null
  resizeObserver?.disconnect()
  resizeObserver = null
  motionPreference?.removeEventListener?.('change', redraw)
  chart?.dispose()
  chart = null
})
</script>

<template>
  <div class="trend-chart" :class="`trend-chart--${variant}`" :data-state="state" role="group" :aria-label="state === 'error' ? translate('experience.unavailable') : accessibleSummary">
    <div ref="el" class="trend-chart-canvas" aria-hidden="true"></div>
    <div v-if="state === 'error'" class="trend-chart-fallback">
      <span role="status">{{ translate('experience.unavailable') }}</span>
      <button type="button" @click="redraw">{{ translate('common.retry') }}</button>
    </div>
  </div>
</template>

<style scoped>
.trend-chart { position: relative; width: 100%; height: 100%; }
.trend-chart-canvas { width: 100%; height: 100%; }
.trend-chart-fallback { position: absolute; inset: 0; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 12px; color: var(--ns-text-2); font-size: 13px; }
.trend-chart-fallback button { min-height: 32px; padding: 6px 14px; border: 1px solid currentColor; border-radius: 8px; background: transparent; color: inherit; font: inherit; cursor: pointer; }
.trend-chart-fallback button:hover { background: rgba(148, 180, 223, .12); }
.trend-chart-fallback button:focus-visible { outline: 2px solid currentColor; outline-offset: 3px; }
.trend-chart--console .trend-chart-fallback { color: #b1c7e5; }
</style>
