<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { ECharts } from 'echarts/core'
import { loadEcharts } from '../lib/echarts'
import { translate } from '../i18n'
import { useI18n } from '../composables/useI18n'

/**
 * 近 7 日告警趋势折线图（概览页/态势页共用）。
 * props.data: { "2026-08-03": 0, ... }（日期 → 条数）
 * 内置 socp echarts 主题 + 深浅色自适应 + 峰值标点 + tooltip 明细。
 */
const props = withDefaults(defineProps<{ data?: Record<string, number>; variant?: 'overview' | 'situation' }>(), {
  variant: 'overview',
})
const { locale } = useI18n()

const el = ref<HTMLElement>()
let chart: ECharts | null = null
let renderToken = 0
let themeObserver: MutationObserver | null = null

function cssColor(variable: string, fallback: string): string {
  const value = getComputedStyle(document.documentElement).getPropertyValue(variable).trim()
  return value || fallback
}

function themeColor(key: string): string {
  const dark = document.documentElement.getAttribute('data-theme') === 'dark'
  const map: Record<string, string> = {
    label: dark ? '#e6edf3' : '#1f2328',
    axis: dark ? '#8b949e' : '#57606a',
    accent: cssColor('--ns-accent', dark ? '#60a5fa' : '#2563eb'),
    success: cssColor('--ns-success', dark ? '#3fb950' : '#15803d'),
  }
  return map[key] ?? '#57606a'
}

async function render() {
  const d = props.data
  if (!el.value || !d) return
  const token = ++renderToken
  const echarts = await loadEcharts()
  if (token !== renderToken || !el.value) return
  if (!chart || chart.isDisposed()) chart = echarts.init(el.value, 'socp')
  const dark = document.documentElement.getAttribute('data-theme') === 'dark'
  const days = Object.keys(d).sort()
  const vals = days.map((k) => d[k])
  if (props.variant === 'situation') {
    // 态势页：带坐标轴、蓝色实线（与旧 sitTrend 图表一致）
    chart.setOption({
      grid: { left: 36, right: 12, top: 22, bottom: 24 }, tooltip: { trigger: 'axis' },
      xAxis: { type: 'category', data: days.map((k) => k.slice(5)), axisLabel: { fontSize: 10 } },
      yAxis: { type: 'value', axisLabel: { fontSize: 10 } },
      series: [{
        type: 'line', smooth: true, showSymbol: false, data: vals,
        lineStyle: { color: themeColor('accent'), width: 2 },
        areaStyle: { color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [{ offset: 0, color: dark ? 'rgba(96,165,250,.35)' : 'rgba(37,99,235,.24)' }, { offset: 1, color: 'rgba(0,0,0,0)' }]) },
      }],
    }, true)
    return
  }
  chart.setOption({
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
          { offset: 0, color: dark ? 'rgba(68,147,248,.4)' : 'rgba(9,105,218,.32)' },
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
}

onMounted(() => {
  void render()
  // 主题切换时重绘（监听 html[data-theme]）
  themeObserver = new MutationObserver(() => { void render() })
  themeObserver.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] })
})

watch(() => props.data, () => { void render() }, { deep: true })
watch(locale, () => { void render() })

onBeforeUnmount(() => {
  renderToken++
  themeObserver?.disconnect()
  themeObserver = null
  chart?.dispose()
  chart = null
})
</script>

<template>
  <div ref="el" class="trend-chart" style="width: 100%; height: 100%"></div>
</template>
